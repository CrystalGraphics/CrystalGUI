# Phase 6 — the remote file, made honest

**Scoped 2026-08-22**, immediately after Phase 5 closed its non-windowing half and a real socket produced
a number nobody liked. Phase 5 answered *can a person use the remote workspace without losing work*.
Phase 6 is the gap between that and **a remote file behaving like a file**: fast enough not to notice,
truthful when it changes underneath you, and legible when it disagrees with what you have.

> **Scope.** The wire's speed, external change, and disagreement. Windowing is `plan_windowing.md` and the
> shader node graph is its own document; neither interacts with this.

---

## The through-line

**A remote file is not a local file, and the UI still pretends it is.** Every item below is one of three
symptoms of that: *latency you cannot see*, *change you are not told about*, and *disagreement with no way
to inspect it*.

---

## Provenance, and what was actually measured

Everything here was checked against the code or measured on 2026-08-21/22. Where a number is quoted it was
produced by a run, and the run is named.

| Established | How |
|---|---|
| A 4 MB read takes 6.5 s over a real socket; the same 4 MB written takes 3.0 s | `CgUiWireProbe`, `runServer` + `runClient -PcgJoin` |
| The asymmetry is **not** the frame ceiling | The same probe's sub-inline control download runs at the upload's rate — 1.42 vs 1.40 MB/s |
| A read over `INLINE_MAX_BYTES` is 16 serial round trips | 131 ticks / 16 chunks = 7.9 ticks each, which is one credit window per round trip and matches 640 KB/s exactly |
| The watcher reads whole files to compute an etag | `WorkspaceWatcher.poll` calls `service.read(actor, path).etag()`; `WorkspaceService.read` says *"the etag comes from the stat"* |
| An etag needs no content | `LocalFileSystem.stat` uses `Files.size` + `Files.getLastModifiedTime` only |
| The client is told about changes and does nothing with them | `Workbench` wires `onFileChanged` to a file-tree refresh and touches no open editor |
| There is no diff algorithm in the repo | `Change`/`ChangeSet` are edit *representations*; nothing computes one from two texts |
| Cancellation is already free | `WorkspaceProtocol`: the client decides the pace, *"so a UI can pause or abandon a download without a cancel protocol"* |
| Reopening an unchanged file transfers nothing | `IF_NONE_MATCH` / `UNCHANGED` in `WorkspaceClient.read` |

---

## Prior art, researched rather than remembered

### Filesystem watching is unreliable by design, on every platform

This is the finding that shapes 6.2 more than any other, and it is not a Java complaint — it is true of
the OS primitives underneath.

- **macOS has no equivalent primitive.** Java's `WatchService` there is still the generic
  `PollingWatchService`: it re-scans, burns CPU, adds latency, and **misses changes that happen faster
  than the scan interval**. [JDK-8293067](https://bugs.openjdk.org/browse/JDK-8293067) to implement it on
  FSEvents is open.
- **Events are dropped under load.** A `WatchKey` raises `OVERFLOW` once its queue exceeds 512 on default
  Linux settings, and the documented recovery is to *re-scan the directory and rebuild*. Windows'
  `ReadDirectoryChangesW` has the same shape with its own buffer.
- **Linux caps watches per user** — 8,192 on a default Ubuntu 20.04.

> **The conclusion is the important part: a watcher can tell you something changed, and can never tell
> you nothing did.** So the etag poll is **not deleted** by this phase. It is demoted from "the
> mechanism" to "the reconciliation", which is exactly what OVERFLOW recovery needs anyway.

### VS Code splits watching by shape, and runs it out of process

- A **separate process**, because watching is compute-intensive.
- **Recursive** watching for the opened folder (`@parcel/watcher`), **non-recursive** `fs.watch` for
  editors holding files outside it. Two mechanisms, chosen per request.
- **Excludes are load-bearing**, not a nicety: `files.watcherExclude` is the single largest source of
  watcher issues in their tracker, in both directions — absent excludes exhaust the OS limit, wrong ones
  silently stop reporting.
- Requests are keyed by `(path, recursive, includes, excludes, correlation, filter)` so identical ones
  dedup rather than stacking.

### Histogram, not Myers

Git ships four: Myers (default), minimal, patience, histogram. Myers and minimal optimise for the
**fewest changed lines**; patience and histogram anchor on lines that appear exactly once, which keeps
moved and reordered blocks intact instead of scrambling them. Histogram is patience with low-occurrence
elements handled, and is **faster than patience, comparable to Myers, and more readable than either** — an
empirical study across Git repositories found it describes code changes more effectively than Myers.

> For a viewer whose entire job is *"show a human what changed"*, optimising for fewest lines is
> optimising for the wrong thing.

---

## 6.1 The watcher reads every byte of every watched file · **done 2026-08-22**

`WorkspaceWatcher.poll()` calls `service.read(actor, path).etag()` — a **full content read** — to obtain an
etag that `stat()` already yields from size and mtime. It runs every 0.5 s, per peer, per watched file,
with `MAX_FILE_BYTES` at 100 MB.

Ten open files is twenty whole-file reads a second, per player, allocated and discarded.

**More urgent since 2026-08-22**, not less: the editor no longer pauses the integrated server, so that I/O
now runs while the world ticks rather than while it is frozen.

Nothing else in this phase should be built on top of a poll that is this expensive, because the honest
version of 6.2 keeps the poll as its reconciliation path.

---

## 6.2 A real filesystem watcher · **done 2026-08-22**

Watch the OS, not the etag: when a file is **changed, saved, moved or deleted** by anything — an external
editor, a git checkout, a resource pack update — the server learns immediately and tells every client that
has it open.

### The design the prior art forces

- **Push where the OS supports it, poll where it does not.** Behind one interface, so the caller never
  learns which. `WatchService` on Linux and Windows is a real event source; on macOS it is a poll wearing
  an interface, and that is a fact to route around rather than discover.
- **The etag poll survives as reconciliation.** OVERFLOW loses events by design and macOS misses fast
  ones, so the only correct recovery is a re-scan — which is 6.1, already written and now cheap. A
  watcher without reconciliation reports *most* changes, which is the worst of the three options because
  it looks like it works.
- **Excludes from day one**, not later. `WorkspaceProject.excludes()` already exists and the manifest
  already honours it; the watcher must use the same list or a `node_modules` will exhaust the inotify
  limit for the whole user.
- **Recursive on the project root**, non-recursive for anything outside it. VS Code's split, and for the
  same reason: one recursive watch on a tree beats N watches on its files, right up until the file is not
  in the tree.
- **No natives.** `language/` exists precisely so tree-sitter's platform natives stay off a dedicated
  server; a watcher is server-side by definition, so it may not reintroduce the problem in reverse. That
  rules out the FSEvents wrappers and settles macOS as the polling tier.

### Watch for

**Coalescing.** A save is often several events (truncate, write, rename-into-place), and an atomic save is
a rename. Reporting three changes for one save makes every consumer debounce independently, and they will
each pick a different interval.

**The write we made ourselves.** `noteWritten` already suppresses the echo for the peer that wrote; a real
watcher sees the write on disk and must not re-report it to that peer as somebody else's change. Presence
(5.6) makes this observable for the first time.

---

## 6.3 The client end of a change · **done 2026-08-22**

The server→client half has worked since Phase 4. `Workbench` receives `fs.changed` and refreshes the
**file tree**. **No open editor is touched.** So today: a file changed externally while your buffer is
clean shows stale content forever; deleted on disk leaves a normal-looking tab; changed-while-dirty is
discovered only when you save.

| State | What should happen |
|---|---|
| Buffer clean, file changed | **Reload silently.** The majority case by a wide margin, and prompting for it is what makes watching feel naggy |
| Buffer dirty, file changed | Keep the buffer, mark the tab, and let the conflict path handle the save — 5.5 already does |
| File deleted | Keep the buffer, mark the tab "deleted on disk". Closing it destroys work the user may want to write back |
| File renamed/moved | Follow it if the identity is knowable; otherwise treat as delete. `WorkingCopies` already tracks open paths across a move |

> **This is what makes 6.2 worth building.** A watcher whose notifications reach nothing is a cost with no
> feature attached.

---

## 6.4 The chunked read is serial · **the 6.5 seconds**

A read above `INLINE_MAX_BYTES` is a **pull**: the client asks for each `CHUNK_BYTES` (256 KB) piece and
waits a full round trip. 4 MB is 16 of those, measured at 7.9 ticks each.

**Not a constant to raise.** `CHUNK_BYTES` is 256 KB because it matches `DEFAULT_WINDOW_BYTES` — *"a chunk
is in flight as a single burst rather than stalling halfway for a `WINDOW_UPDATE`"* — which is still true,
so enlarging it trades a round trip for a mid-chunk stall.

**Pipeline the pull**: request chunk N+1 before N arrives, with a small window of outstanding requests.
The ordering and reassembly are the work; the transport already carries them on independent streams.

**Watch for:** the sender's admission budget (5.9) is denominated in bytes and gates *fragmenting*
messages. Several chunks in flight is several messages, and the interaction has never been measured.

---

## 6.5 A file that is loading should say so

Today a slow open is indistinguishable from a hung one, and a failed one has nowhere to report itself.

- **Progress, not a spinner.** A chunked read knows byte counts exactly; a spinner discards information
  already in hand. `ProgressBar` and `JobScheduler` exist, and `AGENTS.md` already cites wget's *"refresh
  the ETA about once a second"*.
- **Not under ~150–200 ms**, or every cached open flashes — and after 6.1 and `IF_NONE_MATCH` most opens
  are cached.
- **The tab needs states**: loading, loaded, **failed with retry**, stale. The failure state is the one
  with nowhere to live today.
- **Cancellation is already free.** The pull design means abandoning is not asking again; the server's
  transfer TTL cleans up. This needs a test, not a feature.

---

## 6.6 A differ · **done 2026-08-22**

There is no diff algorithm in the repo. This is the dependency under 6.7 *and* 6.8, which is the argument
for building it once, properly, rather than inside the viewer.

**Histogram**, for the reasons in the prior art above: it anchors on unique lines, keeps moved blocks
intact, is faster than patience and comparable to Myers, and reads better than either. Myers optimises for
the fewest changed lines, which is the wrong objective for something a person reads.

`LineDiff` answers in **line ranges**, which is what a viewer needs; `TextDiff` converts to a `ChangeSet`
over the existing `Change` type, so it composes with `writeDelta`, `UndoStack` and the editor's own edit
path rather than being a second representation. One representation, not two — the alternative is how a
delta read and the diff on screen come to disagree about what changed.

**The round trip is the correctness net**: applying the change set must reproduce the new text exactly.
It is the only assertion a subtly-wrong diff cannot pass, and it is run over 200 seeded random edits as
well as the written cases.

> **A trailing newline is not a line**, so a line diff cannot see it change — `"a\nb\n"` and `"a\nb"`
> split to the same two lines. That is the granularity being honest rather than a bug, and the offset seam
> is the only place with enough information to fix it. **Found by the round trip, not by reading**: the
> hunk count was right, so nothing that counted hunks would ever have noticed.

> **The rarity scoring is not pinned by any test here, and that was checked rather than assumed.**
> Replacing it with "take the first common run" leaves every test green, because prefix/suffix trimming
> resolves any fixture small enough to write out before `findAnchor` is consulted. It earns its place on
> large messy input, which is where a fixture stops being readable as a test. Recorded rather than left
> as an implied claim.

---

## 6.7 The MERGER · **engine + first view done 2026-08-22**

> **Scope changed mid-flight, on the user's call: "maybe I actually want a full diff merger not just a
> viewer".** It is the right call and it changes the centre of gravity. A viewer answers *what differs*; a
> merger answers *what do I end up with*, and only the second one resolves the conflict it was opened from.
>
> **The consequence is three-way, not two.** A two-way diff can only ask "which of these two", once per
> difference, and over a file where both sides have moved on that is dozens of questions with mechanical
> answers. With a common ancestor a region only one side touched resolves itself, and so does a region both
> sides changed identically — what is left is the handful they changed *differently*. That reduction is the
> whole value, and it is why the apply-chevrons this section previously ruled out ("a viewer is a reader")
> are now the point rather than out of scope.
>
> **And the base was already on the client**: `WorkspaceClient.cachedContent` is the bytes last read from the
> server, which is exactly the ancestor of both the editor's buffer and the server's current copy. No
> protocol, no extra read — one accessor (`baseContent`).

### Shipped

| Piece | Where | Notes |
|---|---|---|
| The merge itself | `text/diff/ThreeWayMerge` | Regions, four `Kind`s, five `Resolution`s, `merged()` answerable at any time |
| Three-pane view | `ui/elements/workbench/MergeView` | mine │ result │ theirs, conflict navigation, scroll-synced |
| The third button | `ConflictDialog` → `Workbench.openMerge` | "Merge…" reads before the destructive pair; omitted with no base |
| Geometry | `ua/workbench.css` | 900×520, both fill idioms, no `border-width-*` |

**The unit is a REGION, not a hunk.** The two sides have no hunks in common, only a base — so every hunk
from either side touching the same span of base lines is grouped and classified once. Two small edits of
mine against one spanning edit of theirs is *one* conflict. Getting this wrong is not a miscount: overlapping
regions each claim the same base lines, so the assembled output contains text twice or drops it. Pinned by a
non-overlap invariant over 300 random three-way cases, and **verified by mutation** — narrowing the grouping
test from `<=` to `<` kills 8 of the 16 tests.

**`Kind` is a fact; `Resolution` is a decision, and they are separate fields.** Folded together, a UI could
never mark a resolved conflict differently from an auto-merge, and re-analysis would silently discard every
choice already made.

**A conflict pre-pointed at mine is not a conflict somebody resolved to mine.** They produce identical text,
so nothing about the output distinguishes them — hence the `settled` flag. Without it the merge reports
itself finished before anybody has read it and Save is live on arrival.

**The hand-edit latch.** Resolution buttons rewrite the result pane wholesale, so typing into it and then
pressing one would lose the typing silently — the worst failure available to a merge tool. The first hand
edit latches: the controls disable and say why, and `mergedText()` reads the *pane*, not the merge, so the
save includes it. Both halves are needed and both are mutation-verified. Mapping a hand edit back onto its
region is what IntelliJ does; `Region.acceptCustom` is the seam for it and the engine already carries it.

**Line granularity, deliberately.** Two people editing different words of one line is a conflict here. A
word-level merge would resolve it silently, which is how a tool produces a line neither author wrote. Git
draws it in the same place.

### Still to do

Bands, ribbons, collapsed regions and intra-line marks — the reading affordances below. The merger is
usable without them and unreadable-at-scale with them missing, so they are next rather than optional. The
one genuinely new engine piece is a **whole-line background band**, which needs an `EditorViewPart`:
`TextEditor` has exactly one decoration lane (`diagnostic`) and nothing generic behind it.

## 6.7 (reference) The diff viewer · **and the third button**

A reader, not a merger: two panes, aligned, with the changed ranges marked. Its first use is the third
choice on `ConflictDialog` — **look at it** beside *keep mine* and *take theirs* — which is the choice a
person actually wants before destroying either side.

Its second use is *"what have I changed since I opened this"*, which needs no protocol at all: the client
already holds the last-read bytes in `cachedContent`.

> **Build it against a `ChangeSet`**, so the source of the two sides is the caller's business. A viewer
> that knew about conflicts specifically would have to be rewritten for its second user.

### The shape, from IntelliJ's diff editor

Supplied as a reference screenshot 2026-08-22 and taken as the target. Reading it off rather than
inventing one, in the order the eye meets it:

| Element | What it is | What it needs here |
|---|---|---|
| **Two synchronised panes** | Left is the older revision, right is *Current version*, each with its own line-number gutter | Two `TextEditor`s. Scroll is **aligned, not merely synced** — see below |
| **Connecting ribbons** | Filled, slanted shapes across the centre gutter joining a left block to its right counterpart | `ctx.curve()` / `ctx.triangle()` already draw filled vector shapes for `CgUiSvg`; this is the same path |
| **Apply chevrons** (`»`) | Per change, in the gutter, pushing one side to the other | A viewer is a **reader**, so these are out until there is somewhere to write |
| **Line bands** | Whole-line background on changed and inserted lines | Existing decoration/band painting |
| **Word-level marks inside a line** | The changed *fragment* is darker than the line band — `WindowFrame` against `Tooltip` on line 5 | A **second, finer diff within the line**. 6.6 answers lines; this is a follow-on |
| **Collapsed unchanged regions** | Zigzag separators with a breadcrumb of the enclosing scope: `ToolWindowFrame > WINDOWED_CLASS` | `FoldingModel` folds; the breadcrumb is the enclosing symbol, which the language services can name |
| **Toolbar** | Viewer mode, whitespace handling, highlight granularity, `3 differences` | Ordinary chrome |
| **Change marks in the scrollbar track** | Where the differences are in the whole file | New: `ScrollerView` has no track markers today |

**Alignment is the part that is not obvious.** IntelliJ does *not* insert blank gaps to line the two sides
up — the panes run at their own line positions and the **ribbons** carry the eye across the offset. That
is why the connectors slant. A viewer that instead padded one side with blanks would be easier to build
and would misreport line numbers, which are on screen.

**Order within 6.7:** two panes with bands and aligned scrolling first, then ribbons, then the collapsed
regions, then intra-line marks. Each is legible on its own; the intra-line pass is the only one needing
more from 6.6.

---

## 6.8 Delta reads · **the other half of the speed problem**

`writeDelta` exists, so writes are already deltas. **Reads are always whole-file**, so a file that changed
by three lines re-fetches all 4 MB — and after 6.2, external changes will happen far more often, so this
gets worse as the phase succeeds.

The client holds the etag and the bytes it last read. The server can compute the delta against that etag's
content and send a `ChangeSet`. Same differ as 6.6, opposite direction to `writeDelta`.

**Watch for:** the server must be able to reproduce the bytes the client holds. That means either keeping
the previous content, or accepting that a delta is only offered when it can be — and falling back to a
whole read, which must be indistinguishable to the caller.

---

## 6.9 A probe that runs with the editor open

**Argued for by a bug this week.** `CgUiWireProbe` proved the protocol was healthy over a real socket and
*missed* the fault that made the workspace unusable, because — like `CgUiRemoteWorkspaceProbe` and the
two-process test before it — it closes the GUI. The editor pausing the integrated server was invisible to
every check we had, and every check we had was built the same way.

Everything in this phase is a UI-facing property: a spinner that appears, a tab that reloads, a diff that
opens. None of it can be verified by a probe with no screen.

---

## Deliberately out of scope

**A real 3-way merge.** The viewer is a reader; merging is a decision surface with conflict markers and
its own editing model, and `WorkspaceRpc` refuses to merge on principle — *"merging is a decision with a
UI attached and does not belong in a write path"*. Ship *keep mine / take theirs / look at it* and find
out whether it is enough, which it may well be for a long time.

**A watcher outside the workspace.** Watching arbitrary paths is how the OS limits get exhausted. The
workspace root is the boundary, as the project registry already is for everything else.

---

## Order, and why

1. ~~**6.1**~~ **done.** `stat`, not `read`. Pinned by counting filesystem reads rather than by timing,
   because the work was invisible: a throughput assertion passes whether or not the file is read.
2. ~~**6.2 → 6.3**~~ **done, as the pair they are.**
   - `CgFileEventSource` / `CgFileEvent` / `NioFileEventSource`, one source **per project** rather than
     per peer (a watch is an OS handle and Linux caps them per *user*), drained **once** on the server
     tick and fanned out, since draining is destructive.
   - `drain()` rather than a listener: the implementation may use a thread, but nothing it owns crosses
     that boundary — a signal emitted by a watcher thread carries that thread into every consumer, which
     this codebase has already paid for once.
   - **The etag stays the arbiter even when an event prompted the look.** `ENTRY_MODIFY` fires for a
     touch that changed no bytes, and one save is often three events (truncate, write, rename), so
     trusting the event would report a save three times.
   - **OVERFLOW falls through to the full re-scan**, which is the entire reason 6.1 came first. Ignoring
     it fails a test.
   - Client end: clean reloads silently, dirty is marked and left alone, deleted keeps its buffer. The
     mark goes through `FileDecorations`, so it reaches the tab **and** the tree from one place and
     bubbles to the folder — colour climbing, badge not.
3. **6.6 → 6.7 and 6.8.** The differ unlocks both, and 6.7 is the visible one.
4. **6.4 and 6.5** are independent of all of the above and of each other.
5. **6.9** should exist before 6.5 is called done.

> **One thing 6.3 taught, recorded because the first test asserted the opposite.** A plain save of an
> externally-changed file is **refused**, and the mark correctly stays: the write quotes an etag the
> server no longer has, so it is a conflict and 5.5 asks which version survives. The mark clears on a
> write that *lands* — which is why "keep mine" is now `Workbench.overwriteActiveFile()` rather than a
> branch living inside a modal's callback, where it could not be tested without driving the modal.

---

## Carried in from Phase 5

- **5.3, persist the retained set** — still blocked on the windowing registry.
- **The editor captures its `WorkspaceClient` once** and never rebinds, so leaving a world and rejoining
  leaves it on a dead wire. `Mc1710Workspace` has the rebinding logic and nothing calls it. Same problem
  as windowing's reconnect-on-restore; recorded in `AGENTS.md` and left to that lane.
- **Two machines for 5.9.** Localhost has no jitter and no MTU worth the name.
- **Nothing gates on `:core:test`.** A process decision, not a code one — and the reason a fix was
  silently unwound this week while its own test sat red.
