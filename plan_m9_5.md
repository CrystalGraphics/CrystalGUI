# M9.5 — The Run panel

Detail for the M9.5 row in `plan_syntax.md` §20. A dock panel beside Problems and Notifications
carrying the output of running scripts, plus the indicator that says which files are live.

## Status

| § | Item | State |
|---|---|---|
| 9.5.1 | Capturing output — the thread-local marker | **done** — `ScriptOutput` + `ScriptHost` bracket, 11 tests |
| 9.5.2 | The console as a text area | **rewritten from a ListView** — see below; the list was the wrong shape |
| 9.5.3 | States, and the per-script filter | **done** — `RunState`/`RunSessions` (12 tests) and `RunConsole.setFilter` + the head picker (12 tests). **The rail itself is not built** |
| 9.5.4 | The ring, and `System.in` | **done** — collapsing removed with the list; `ScriptInput` mirrors `ScriptOutput`, 7 tests |
| 9.5.5 | The running indicator | **done** — `RunDecorations`, 6 tests, and it is now *invalidated* so the row actually repaints. Editor-tab half **cut**, see 9.5.7 |
| 9.5.6 | Stack-frame links | **done** — `ConsoleFilter` + `JavaStackFrameFilter`, 10 tests |
| 9.5.7 | The running badge | **done** — `RunIndicators`, 6 tests. Editor tabs cut, see below |

Written before any of it existed; the states above are current. What remains is **the rail** (9.5.3) —
the states, the filter it drives and the input row are all built; what is missing is the column of live
scripts itself.

### All of it lives in `language/`, not in `core/`

It was written into `core.com.crystalgui.run` first, by analogy with `Markers` sitting under
`ProblemsPanel`, and that analogy is wrong. **The test is whether anything in `core/` consumes it.**
`text.lang` is in `core/` for a real forcing reason — `TextEditor` is there and reads it — and
`text.diagnostic` likewise. Nothing in `core/` consumes a script's output: the producer is `ScriptHost`,
and the panel and the running indicator are both new and can equally live beside it.

So the whole feature is one module's: `com.crystalgui.language.run`, where `ScriptHost` already is. The
model ends up in the same package as the thing that fills it, which removes the imports entirely and is
the clearest evidence the placement is right.

The panel and the `FileDecorationProvider` go there too. Neither needs to be in `core/` to work: a dock
panel registers with `DockPanelRegistry` and a decoration provider registers with `FileDecorations`,
both of which are extension points rather than compile-time lists. `core/` never learns that scripts
exist, which is the direction the dependency has to run.

> **The cost, found by paying it: `language/` cannot test a widget yet.** Constructing any `UIElement`
> there needs Taffy and JOML (both `compileOnly` in `core`, so nothing transitive supplies them),
> CrystalGraphics `core` and `platform` (`UIInputHandler` *implements* `CgSystemInput`, and
> `StyleSheet.DEFAULT` reads `default.css` through `CgIO` in a static initialiser), and finally a
> registered `CgPlatform` — which is `TestPlatformService`, and it lives in **core's test source set**,
> where no other module can see it.
>
> The first three are four lines of `testImplementation`. The last is not: duplicating the stub means
> reimplementing `CgInputService`'s fifteen methods beside a copy that already exists, and the original's
> own javadoc warns about the cross-test leakage two copies would reintroduce.
>
> **The right fix is `java-test-fixtures` on `core`**, moving `testsupport/` to `src/testFixtures/` —
> the standard mechanism for exactly this, and it would serve every future module that wants to test a
> widget. It is a restructuring of core's test sources, so it is not something to do casually.
>
> Until then `RunPanel` is covered by its model rather than through a window: `RunConsole` and
> `RunSessions` are thoroughly tested headlessly, and what is untested is the wiring — the deferred
> rebuild and the per-row class swap. Both are real rules with real failure modes, so this is a
> **known gap**, not a decision that they do not need testing.

---

## Why IntelliJ's Run window is the wrong reference

This project's standing rule is to port from IntelliJ or VS Code rather than invent. Here that rule
points at the **wrong window**, and it is worth writing down why so nobody re-derives it.

IntelliJ's Run panel rests on three assumptions, and **two of them are false here**:

| It assumes | Here |
|---|---|
| a **process boundary** | there is none — a script runs inside the game's JVM |
| a **termination**, with an exit code | a tick handler never ends |
| one run at a time per configuration | many scripts are live simultaneously |

The process boundary is the load-bearing one. In IntelliJ the process *is* the output boundary: stdout
belongs to the launched JVM and to nothing else, so capturing it is free and unambiguous. In-process,
redirecting `System.out` would swallow Minecraft's logging and every other mod's. **The output boundary
therefore has to be constructed rather than inherited**, and §9.5.1 is that construction.

**The right reference is Unity's Console.** Unity's situation is ours exactly — one process, many
concurrently-live scripts, per-frame execution, no exit codes — and its Console has precisely the
features that shape forces: Collapse, Clear on Play, severity filters with counts, and double-click to
the logging line.

> Note that VS Code ships **two** surfaces here and they are routinely conflated: the **Terminal**, which
> is process-shaped, and **Output channels**, which are stream-shaped, per-producer, and have no
> lifecycle and no exit code. This is the second one. A screenshot of a Gradle build in IntelliJ's Run
> window is the first one, and copying it is the mistake this section exists to prevent.

## Decisions taken

1. **Output survives the script stopping**, until cleared. Unity's model, and the reason is that the
   most valuable transcript is usually the one from the run that just died. **Bounded, though** — see
   the cycle buffer in §9.5.4; surviving and being kept in full are different promises.
2. **One console per workspace.** Not global and not per-script.
3. **A runtime exception is console-only and never raises a Problems row.** The Problems panel is
   static analysis about *source*, with ranges; a thrown exception is an *event*, and its location is a
   stack trace rather than a range. Every reference draws the line here. It still navigates — see
   §9.5.2.

---

## 9.5.1 Capturing output — the thread-local marker

**`ScriptHost` is the only thing that knows when control is inside a script.** So it sets a thread-local
marker around every invocation, and a `PrintStream` shim installed once consults it: on a thread that is
currently inside a script, route to the console; otherwise pass straight through to the real stream,
untouched.

That single move is what makes `System.out.println` work **in a one-shot script and in a tick handler
running on the game thread**, without stealing Minecraft's logging or any other mod's.

**It cannot be done any other way.** A per-thread rule alone covers a one-shot script on its own thread
and fails for event handlers, which run on the game thread and are otherwise indistinguishable from the
game itself. Requiring authors to use a special `print` binding instead is worse than it sounds: the
first thing anybody writes is `System.out.println`, and it would vanish with no explanation.

The marker carries the script's identity, which is also what gives every message its origin for free
(§9.5.4) and its owner for filtering (§9.5.2).

---

## 9.5.2 The console is a read-only text area, not a list

**This was built as a `ListView` first, and that was the wrong shape.** The disproof is selection: an IDE
console lets you drag from the middle of one line to the middle of another, ten rows down, and copy
exactly that. A row-based list cannot express it — its selection unit is the row — and no amount of
styling gets there. IntelliJ's console is an editor component, and so is VS Code's output panel.

Everything the list version bought is available and better in a text area, and several things it could
never buy come free: character-level multi-line selection, copy of exactly what was dragged, soft wrap,
find-in-console later.

### Repurpose `TextEditor`; do not build a text area

Not a close call. `TextEditor` already has `setReadOnly`, `setGutterVisible`, `selections()`,
`getSelectedText()`, `offsetAt(x, y)`, mouse drag-selection ported from VS Code, the clipboard actions,
virtualised line rendering, scrolling, and a `SyntaxTokenizer` seam. Building a console text area means
reimplementing all of that — which is the exact thing this repository's own rule refuses, except here the
thing to port from is in the next package.

The console is therefore a **configured** `TextEditor`: read-only, no gutter, no language services, no
completion.

### What `RunConsole` becomes

It stops being a list of entries and becomes three things, all headless and all testable:

1. **A thread-safe queue of pending appends.** This is not an optimisation, it is required: output
   arrives on a script's own thread or the game's, and a `TextBuffer` may only be mutated on the UI
   thread. The queue is drained once per frame, which is the deferred-refresh shape the list version
   already needed for a different reason.
2. **The ring**, now trimming whole lines off the front of the document rather than dropping rows.
3. **A per-line level map**, which is what colours the transcript.

### Colouring goes through the tokenizer seam, not a second path

`SyntaxTokenizer.tokenize(document, from, to)` answers tokens in document offsets over the range the
editor actually realised. A console tokenizer reads the level map and emits a token per line — so stderr,
warnings and run boundaries are coloured by the **same** pipeline, the same `.__syntax__::highlight()`
rules and the same editor colour scheme as code. Inventing a per-row colour path instead would give the
console its own palette, drifting from the squiggles describing the same run.

### Collapse is removed

**Third time this rule has moved, and this is where it stops.** It began as fold-by-origin, which deleted
output; became fold-by-text-and-origin, which was correct; and now goes entirely, because IntelliJ does
not collapse and a text area has nowhere to put a `×N` badge without becoming a list again. The flood a
bound is genuinely needed for is answered by the ring, which is where a bound belongs.

### Deliberately phase two

- **The input line.** The last line accepting `System.in` is real and wanted, and nothing routes stdin to
  a script today — `ScriptHost` does not wire it at all. So the console ships read-only, and the editable
  tail lands with the plumbing that would give it something to talk to.
- **Clickable links.** A stack frame's `file:line` should be a link, as in both references. The offsets
  are known when a line is appended; what is missing is the affordance and the hit test.

## 9.5.3 The live-script rail, and the state model

Where IntelliJ lists *runs*, this lists **live scripts**. Not exit codes — states:

| State | Means |
|---|---|
| `Compiled` | built, never run |
| `Running` | executing right now, one-shot |
| `Live (3 handlers)` | loaded, handlers registered, waiting to fire |
| `Stopped` | interrupted through §19.3's kill flag |
| `Failed` | threw, and did not register handlers |

**`Live`, deliberately, and not `Idle`.** Idle reads as "nothing is happening", when what it means is
"this will fire again without you doing anything" — which is the single most important fact about a
running script and the one an exit code cannot express.

**And `Running` is only meaningful for a one-shot.** A per-tick script is genuinely executing twenty
times a second, so surfacing that as `Running` would strobe the indicator twenty times a second and
communicate nothing. Event-driven scripts show `Live` as their steady state, and `Running` is reserved
for work with a beginning and an end.


### The rail's UI, and the one thing the text-area migration made expensive

A `ListView` down the console's leading edge, one row per session: a state glyph, the script's name, and
the state text. `ListView` because it already solves recycling, focus, type-ahead and the roving tab
stop -- and because the rail is a genuine list of rows, which is exactly what the transcript turned out
not to be.

**Hidden until a second script has been seen.** IntelliJ hides its run tree when there is one
configuration, and it is right to: a rail listing one thing is a caption taking a fifth of the panel.
The trigger is *seen this session*, not *live now* -- a rail that appears and vanishes as scripts finish
is worse than one that stays once earned.

**Selection filters the transcript, with an `All` row at the top.** This is where 9.5's "one workspace
console filtered by script" is actually spent, so the rail and the filter are one piece of work rather
than two.

> **FILTERING IS NO LONGER FREE, and that is the price of 9.5.2.** In a list, filtering swaps the row
> source and costs nothing. In a text area the document *is* the transcript, so a filter change has to
> **rebuild it** -- clear the buffer and re-insert the lines that match. That is one insert of a joined
> string rather than n inserts, so it is cheap in absolute terms, but three things follow that would
> otherwise be found the hard way:
>
> - **The ring and the filter must be composed in one place.** The ring trims `lines`; the filter selects
>   from it. `RunConsole` keeps `lines` as the whole truth and derives the document, so eviction and
>   filtering cannot disagree about what the reader is looking at.
> - **Selection is lost on a filter change**, because the offsets it names no longer exist. IntelliJ
>   loses it too when you switch console tabs. Acceptable, and worth saying rather than discovering.
> - **The line map must be rebuilt with the document.** `lineAt(row)` is what the tokenizer and the link
>   lane both read; a filtered document's row 3 is not `lines.get(3)`. Deriving both from one pass is the
>   only arrangement where they cannot drift.

**The state comes from `RunSessions`, never from output arriving.** A tick script prints twenty times a
second and its state is `Live` throughout -- rendering state from traffic is precisely the strobe this
section exists to prevent.

**Placement is a `SplitView`**, so the rail can be widened for long names. Three of its traps apply and
each has cost a session before: a pane is a flex **column** whatever the split's orientation, the divider
must clamp against the pane's **content** `min-width` and not the `__split-pane__` wrapper, and a split
cannot go below two panes -- so the "hidden until a second script" state above is the split being absent,
not a pane sized to nothing.

---

## 9.5.4 The bound is the ring, and collapsing is gone

**This rule moved three times, and the moves are worth keeping** because each was disproved by something
concrete rather than reconsidered.

1. **Fold by call site.** Argued from the per-tick counter: `tick 1`, `tick 2`, `tick 3` produce a
   different string every time, so a text key never matches and the flood arrives in full. Unity's own
   manual recommends Collapse for errors "generated on each frame update", which is the same pressure.
2. **Disproved by the first real script.** `RunTest.java` prints through a helper, so every line in the
   file shared that helper's origin — and thirteen distinct results collapsed into one row reading
   `×13`, twelve of them gone. The premise was right and the conclusion did not follow: three different
   messages are three messages, and a row showing only the newest does not compress a transcript, it
   deletes two thirds of it. **A console that loses output is worse than a console that scrolls.**
3. **Fold by text and origin**, which is Unity's rule with one extra separation, and which was correct.
4. **Gone entirely**, with the list. IntelliJ does not collapse; a text area has nowhere to put a `×N`
   badge without becoming a list again; and the flood a bound is genuinely needed for is answered by the
   ring, which is where a bound belongs.

### The ring, sized in KB

Output deliberately survives its script stopping — the most useful transcript is usually the one from
the run that just died. That is a promise about **lifetime**, not about **volume**: a script printing
without pause would otherwise grow the document until the game dies.

IntelliJ answers this with a **console cycle buffer** — `Settings | Editor | General | Console`,
*"Override console cycle buffer size"*, specified **in KB rather than in lines**, global across every
console, with its own documentation warning that a large buffer "can affect performance in the case of
chatty processes". A chatty process is precisely what a tick handler is, so the reference's own caveat
is our normal case rather than an edge one.

KB and not lines, because one stack trace is worth thirty prints and a line budget would let a single
exception evict a whole run's transcript.

**Trimmed in batches, and that is not premature.** Dropping exactly one line per append makes every
append past the bound an O(n) shift of the line list, twenty times a second forever. Taking a tenth at a
time amortises it away and costs only that the bound is approached in steps.

**Eviction is counted and reported**, never silent. A transcript that quietly begins in the middle reads
as the console having missed something rather than as the ring having done its job.

## 9.5.5 The running indicator — a decoration, not new machinery

*"Which tabs and files are currently active"* is a `FileDecorationProvider`, and the decoration layer was
built for precisely this: independent contributors merged per field, bubbling to ancestor folders.
`DiagnosticDecorations` is the working precedent.

**In the file tree it is free.** `ProjectFileTree`, `StripeView` and `Workbench` already consume
`FileDecorations`, so a `RunDecorations` provider lights up rows with no new plumbing — and inherits the
two invariants that already cost a session each: a bubbled decoration **keeps the colour and drops the
badge** (a folder is not itself running), and the change reaches rows **through the deferred refresh**,
because a provider may fire from inside a click handler and a widget must never rebuild the elements it
is being clicked on.

**On editor tabs it is new.** Nothing in `elements/editor/` or `elements/chrome/` reads `FileDecoration`
today — tabs draw a title and a close control. That hookup is the only genuinely new UI work in this
item, and it is worth doing here rather than later because the tab is where you look when you are *in*
the file.

**The subject is the file, and that is not a simplification.** M7's own exit criterion is that a re-run
**replaces** the loader and nothing pins the old one, so there is exactly one live instance per script
file. File ↔ instance is one-to-one, which is what makes a file-level decoration correct rather than a
lossy summary of several runs.

> **Neither reference does this, and the reason is worth having before building it.** IntelliJ marks the
> *run tab*; VS Code marks the *terminal*. Neither marks a file, because in both a run is a process and a
> process is not a file — the file is just what it was launched from, and it may have been edited or
> deleted since.
>
> That trap is real here too: **edit a script while it is live and the mark now describes a file whose
> text is not what is running.** It is not a reason to drop the indicator — "which of my scripts are
> live" is a question only this project's users have, and no process-shaped tool can answer it — but the
> mark means *"this file's compiled instance is live"*, never *"this text is running"*. The honest
> handling is that an edit to a live script's file is itself worth showing (a modified-since-run state),
> which is the same distinction the editor already draws between a document and what was last saved.

---

## 9.5.6 Stack-frame links — port the Filter, not the special case

A stack frame in the transcript opens the file at the line. It worked when the transcript was a list of
rows, because a row is a click target; the text-area migration removed the affordance and left the whole
chain behind it intact -- `onLineActivated` is declared, forwarded by `RunPanel` and consumed by
`RunPanels`, and **nothing emits it**. So this is a regression to repair, not a feature to invent.

### Two different questions, and IntelliJ answers both separately

`RunMessage` already carries `file` and `line` -- the **origin**, resolved from the first stack frame the
script owns. That answers *"where was this printed from"*. It does not answer *"what does this text point
at"*: a stack trace written by `report(Throwable)` has **one** origin -- the reporter -- and twenty frames
in its text, each pointing somewhere different. Navigating a trace by its origin lands every frame on the
same line.

IntelliJ keeps these apart and so should we. Its console knows nothing about stack frames; it runs a chain
of `Filter`s over each line, each returning `ResultItem(highlightStartOffset, highlightEndOffset,
HyperlinkInfo)`. That is why the same console links a compiler's `file:line`, a JUnit failure and a URL
without a line of code about any of them.

So: a **`ConsoleFilter` SPI** over a line's text, with one implementation to begin --
`JavaStackFrameFilter`, matching `(Name.java:123)`. A GLSL filter and a JS filter follow in M10/M11 with
no change here, which is the point of porting the shape rather than the case.

### The span is RECOMPUTED, not stored — and this reverses what this section first said

**Planned as a decoration lane, and that was the wrong answer for the right reason.** The reasoning was
sound as far as it went: link spans are document offsets, **the ring deletes from the front of the
document**, and that is an edit — so any *stored* offset begins describing the wrong text the moment the
bound is first reached, silently, since the transcript keeps working and only the destinations are wrong.
`TextBuffer.decorations()` maintains tracked ranges across edits and would have solved exactly that.

What it missed is that a filter is a **pure function of a row's own string**, so the spans never have to
be stored at all. Recomputing them cannot desync, because there is nothing to desync — the failure mode
the lane was there to prevent is removed rather than managed. The cost is a regex over the handful of
rows actually on screen, and the editor caches tokens per row on top of that.

> `linksAreStillRightAfterTheRingHasEvicted` is the test that would fail if somebody later "optimised"
> this by caching. It fills past the bound and then checks every surviving row's span against its own
> text.

### Painting it without two tokens fighting

The tokenizer reads the lane, so there is one source of truth. The trap: `Levels` already emits a
per-line capture for stderr and warnings, and a link sits **inside** such a line. Two overlapping tokens
under unrelated names leave the winner to paint order, and both names resolve to real colours -- which is
exactly the shape that read as a scheme bug for two rounds when semantic and grammar tokens overlapped.

**So the level capture is emitted SPLIT AROUND the link span**, never overlapping it. Three tokens for a
stderr line containing a frame, not two that intersect.

`link` and `link.active` are new capture names and must be added to the shipped scheme in the same edit.
A capture with no rule is not an error -- it takes the surface's own foreground -- so a missing entry looks
exactly like the feature not working.

### The click, and the rule that keeps it from fighting selection

`TextEditor.offsetAt(screenX, screenY)` already exists and is what mouse selection resolves through. No
new engine machinery: a listener maps the point to an offset and asks the lane.

**Follow on mouse-UP, and only if the press did not become a drag** -- the same rule a browser uses, and
the reason is the same: a plain press in a console both places the caret and may begin a selection, and a
link that fires on the down steals the gesture from a drag that had barely started. IntelliJ requires a
modifier in the *editor* and none in the *console*; ours is a console, so a plain click follows.

Affordance: coloured **and underlined always**, with only the cursor changing on hover. Planned as
underline-on-hover; the editor settles it — `rowSyntax` is a per-row token cache that is cleared
*wholesale*, so restyling one span under the pointer would discard every realised row's tokens on every
mouse move. IntelliJ's console hyperlinks are permanently underlined anyway, so the constraint and the
reference agree.

The colour is `--run-link-fg`, deriving from the system `--link` rather than from a new `--syntax-link`.
**VS Code draws the same line**: link colour is a workbench colour, not a token-scheme colour, because a
link is an affordance rather than a category of code — which also spares all five shipped schemes having
to define a token they have no opinion about.

---

## 9.5.7 The running badge — the mechanism already exists

IntelliJ marks the **Run tool window's stripe button**, not a file: `modified.svg` over the icon, in
`#5FAD65`, whenever something is running.

**Almost all of this is already built**, and the discovery is the plan: `ViewContainerRegistry.setBadge`,
`ViewContainerRegistry.DOT`, `StripeView.ItemButton.setBadge`, and a `.__activity-bar__ .__badge__.__dot__`
rule that already positions the mark at the glyph's top-right with a deliberate negative offset "so it
sits against the glyph's top-right rather than inside the button's box". VS Code's activity badge, ported
before the Run panel existed.

So the work is a **call**, driven off `RunSessions.onDidChange` — `RunIndicators`.

> **Both writes cross a thread, and that was not in the plan.** `onDidChange` fires from wherever the
> transition happened: a one-shot's own thread, or the game thread inside a tick handler. The badge
> attaches an internal child and invalidating decorations repaints tree rows — both `UIElement` state. So
> the signal only *schedules*, through the **shared `JobScheduler`**, whose `drain()` `UIWindow` already
> calls once a frame. Keyed, so a burst of transitions coalesces into one update.
>
> The scheduler rather than a ticker of the panel's own, because `RunPanel`'s ticker stops when the panel
> is closed — which is exactly when the dot is the only thing left saying anything is running.

> **And the tree mark was never actually appearing.** `RunDecorations` was registered and resolves
> correctly when asked, and **nothing ever called `FileDecorations.invalidate()`** — a provider is *pulled*
> during bind, so the row's colour showed up only when the tree happened to rebind for some unrelated
> reason. Right, and invisible. `RunIndicators` drives both, because they are the same question.

**The one real change is that a badge needs a colour it does not have.** The dot rule is shared by every
container's badge, so writing `#5FAD65` into `.__dot__` would turn the Problems count green. `setBadge`
takes only text, so it grows an optional style class -- VS Code's own `IBadge` carries a type for the same
reason -- and `language/` passes a `__running__` class it styles itself.

> **One token, not two greens — and it went through the governance layer properly.** The first attempt
> wrote `var(--run-live, #5FAD65)` straight into the sheets, which `StyleGovernanceTest` refused on three
> separate counts, each a rule worth knowing: **every `var()` a structure sheet reads must be defined**
> (which also caught four `--run-*` names shipped undefined in the previous commit — the console CSS was
> written without running that suite); **`base.css` derives only**, so a literal cannot live there; and a
> component token may derive only into the **pinned system vocabulary**, which is a spec list rather than
> whatever a theme happens to define.
>
> So: `--success-icon` joins the vocabulary — the `-icon` tier already existed for `error`/`warning`/`info`
> with the stated reason "for a filled MARK rather than for a word", and a running dot is exactly that, so
> the set was simply incomplete. Both themes define it (`#5FAD65` dark, `#3E8E45` light, the same
> darker-for-light relationship the other three have). `base.css` maps `--run-live-fg` to it, and the tree
> row and the activity bar both read that one name.

### Editor tabs: recommend NOT marking them

The exit criteria say "marked on its tree row and its tab". The tree row is done; the tab should be cut,
for reasons that only became clear once the badge was found:

- **IntelliJ is run-CONFIGURATION based**, so a run does not belong to a file at all — there is nothing
  for a tab to be highlighted *as*, which is why there is no active-tab mark to copy in the first place.
- **A tab already carries a dirty dot in that exact place.** A second dot there is one affordance with two
  meanings, and the two are independent -- a live script is very often also unsaved.
- **The fact is already stated twice.** The activity-bar dot answers *"is anything running"*; the tree row
  answers *"which file"*. A tab adds a third statement of the same fact in the place with the least room.
- It is not cheap. Tabs read **no** decorations today, so the honest form -- the tab's label taking the
  decoration colour -- is a real hookup rather than a tweak, spent on the weakest of the three signals.

If it is wanted later, the label-colour route is the one to take, and it wants `FileDecorations` on tabs
as a general capability rather than a Run-panel special case.

---

## 9.5.8 The filter, and what the text area cost

Built ahead of the rail, because a filter is a model question and the rail is the picker for it. The
picker meanwhile is a `Dropdown` in the panel head, at the **leading** edge — the trailing one belongs to
Stop and Clear, and a filter is about what you are looking at rather than about what is happening.

**Hidden below two scripts.** A filter offering one choice is a control that cannot do anything, and it
is *removed* rather than hidden: a hidden child still counts for the head's `gap-all`, so a `display:
none` picker leaves a permanent notch beside a console with nothing to filter.

### The transcript and the document are now two lists

This is the price §9.5.2 quietly incurred. When the console was a list, filtering was a row-source swap.
As a document it is a **re-derivation**, and three things follow that would each have been found the hard
way:

- **`all` is the transcript; `shown` mirrors the document.** A filter makes the document a subset, so
  they cannot be one list.
- **The ring bounds `all`, not the document.** Bounding the document would leave the retained transcript
  unbounded whenever a filter was on — the memory the bound exists to cap, uncapped in exactly the state
  somebody turned a filter on to survive.
- **Eviction walks both together.** `shown` is a subsequence of `all`, so the evicted prefix maps onto a
  prefix of `shown` by identity. If those two ever disagree, `lineAt(row)` describes a different row than
  the one painted — and the tokenizer's colours and the stack-frame links both read it.

**`scripts()` is kept, not derived**, which reverses the first attempt. Deriving it walked the whole
transcript, and the picker compares it on every frame output is flowing. The ring deliberately does not
unwind it either: a script whose every line has aged out still ran, and dropping it from the picker would
make the control's contents depend on how chatty its neighbours have been. Only `clear()` empties it.

**Selection is lost on a filter change**, unavoidably — it names offsets that no longer exist. IntelliJ
loses it switching console tabs too.

---

## 9.5.9 `System.in` — the mirror of the output capture

`ScriptInput` is `ScriptOutput` in the other direction and had to be: same missing process boundary, same
thread-local marker, same passthrough. **The passthrough half is the one that matters** — `System.in`
belongs to the game and every other mod as well, and routing it wholesale would park them on a text field
in a panel that may not even be open.

Two things in the stream are not obvious and both fail as a **hang** rather than as an error:

- **`read(byte[], int, int)` must be overridden to return a short read.** The inherited version keeps
  calling `read()` until the array is full, and a decoder's buffer is kilobytes — so a `Scanner` reads the
  line, then waits for thousands more bytes that are never coming. Line typed, Enter pressed, nothing
  happens.
- **The queue is drained on the INTERRUPT path only.** Draining on entry looked equivalent and was not:
  between one read returning and the next beginning, `awaitingInput` is still true and the field is still
  on screen, so a line typed in that window belongs to the read about to start — and the entry drain threw
  it away. Two reads in a row hung on the second, every time.

A stop reaches a script blocked on input, because the interrupt is the kill switch: it is restored rather
than swallowed, the read reports end of input, and the injected safepoint does the rest. Without that,
waiting for input would be the one state a script could not be stopped from — and the state it is most
likely to be stuck in.

### A row of its own, not the transcript's last line

The sketch was "a text area that is read-only except for the last line", which is what a terminal looks
like. **What it would take is a genuine editable-REGION feature in `TextEditor`**, and that is not three
guard sites: it is a caret that cannot be moved above the boundary, a selection that cannot span it, a
backspace that stops at it, and a paste and an undo that respect it. `setReadOnly` is one flag and none of
that exists.

So the input is a `TextField` — which already has every one of those behaviours for the one line it owns —
attached only while a read is actually blocked, and **detached rather than hidden**, or a console with
nothing to answer would carry an invisible tab stop under it. Focus follows it in, because the field
appearing *is* the prompt: a script that stops dead with a field somewhere below that has to be found and
clicked has not asked a question so much as hidden one.

The cost is that the prompt and the answer sit on different rows. The alternative was an editor that is
*mostly* read-only, which is the state where somebody discovers they have silently edited the transcript.
If the editable region is built later, this row is where it plugs in.

**What was typed is echoed**, attributed to the *waiting* script rather than to whatever is on screen, so
a filter keeps the question and the answer together.

`Ask.java` in the harness workspace exercises it — its own file rather than another `RunTest` section,
because a blocking read would make every future run of that file stop and wait for a keystroke.

---

## What it reuses

`ListView` — virtualised, and a firehose demands it. `TreeSearch` for the filter, in the permanent
presentation rather than the transient one, since a console's filter is how you are expected to start.
`Workbench.openAndReveal` for stack-trace navigation. `FileDecorations` for the indicator. The dock
panel shape of `ProblemsPanel`.

## Exit criteria

- `System.out.println` from a **one-shot** script and from a **tick handler on the game thread** both
  reach the console, and Minecraft's own logging reaches neither.
- A script printing every tick does not flood: the **ring** bounds the transcript and an eviction is
  reported rather than dropped silently. (Collapsing was removed with the list — see 9.5.4.)
- The rail shows a tick-driven script as `Live` and does not strobe.
- Stopping a script through §19.3 moves it to `Stopped` and **leaves its transcript**.
- A stack-trace frame opens the file at the line, is visibly a link before it is clicked, and still
  opens the right line **after the ring has evicted from the front of the transcript**.
- A running script's file is marked in the tree, its folder taking the colour and not the badge; and
  **the Run stripe button carries a dot whenever anything is live**. The editor-tab mark is cut, with
  its reasons in 9.5.7 — three statements of one fact, in the place with the least room.
- A script reading `System.in` gets the line typed into the panel, and a script stopped while waiting
  actually stops; the game's own `System.in` is untouched.
- Output can be narrowed to one script and back, and the ring still bounds the transcript while it is.
- A runtime exception appears in the console and produces **no** Problems row.
- The buffer is bounded: a script printing without pause does not grow it without limit, and when the
  oldest output is evicted the panel says so rather than quietly beginning in the middle.

## Position

**Before M10, and not cosmetically.** M10's exit criteria include *"post-run completion on a live
object"* — which requires running a JS script and observing what it did. Without a console, running a
script is invisible, so M10 would be built and demonstrated blind. JS also needs a `console.log` binding
on day one, and §9.5.1 is where it lands.

Depends on M7 (the execution substrate and §19.3's kill flag) and M9 (the UI vocabulary). Both are done.
