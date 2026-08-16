# M9.5 — The Run panel

Detail for the M9.5 row in `plan_syntax.md` §20. A dock panel beside Problems and Notifications
carrying the output of running scripts, plus the indicator that says which files are live.

## Status

| § | Item | State |
|---|---|---|
| 9.5.1 | Capturing output — the thread-local marker | **done** — `ScriptOutput` + `ScriptHost` bracket, 11 tests |
| 9.5.2 | The console as a text area | **rewritten from a ListView** — see below; the list was the wrong shape |
| 9.5.3 | States, the filter, and the rail | **done** — `RunState`/`RunSessions` (12), `RunConsole.setFilter` (12), `RunRail` + `RunElapsed` (8). The rail replaced the stand-in picker |
| 9.5.4 | The ring, and `System.in` | **done** — collapsing removed with the list; `ScriptInput` mirrors `ScriptOutput`, 7 tests |
| 9.5.5 | The running indicator | **done** — `RunDecorations`, 6 tests, and it is now *invalidated* so the row actually repaints. Editor-tab half **cut**, see 9.5.7 |
| 9.5.6 | Stack-frame links | **done** — `ConsoleFilter` + `JavaStackFrameFilter`, 10 tests |
| 9.5.7 | The running badge | **done** — `RunIndicators`, 6 tests. Editor tabs cut, see below |
| 9.5.8 | The per-script filter | **done** — folded into 9.5.3's rail; the head's stand-in picker is gone |
| 9.5.9 | `System.in` | **done** — `ScriptInput`, 7 tests, and an input row that appears only while something is waiting |
| 9.5.10 | The rail as built | **done** — a lazily-built `SplitView`, elapsed time, the right stripe, and no spinner (and the argument for why) |
| 9.5.11 | The review pass | **done** — 9 defects, 13 gaps and both sweeps, 167 tests passing |

Written before any of it existed; the states above are current. **M9.5 is complete** — every exit
criterion below has been met and the last of them, stop-leaves-its-transcript, confirmed in the harness.

Two items this plan listed as *deliberately phase two* both shipped inside it after all — the input line
and clickable links — because the console stopped being a `ListView`. Neither was a scope decision that
changed; both were waiting on plumbing (`ScriptHost` had no stdin route) or on an affordance a list could
not carry (a hit test over a span of characters). The section below is kept as written, with that noted,
rather than edited to look prescient.

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

### Deliberately phase two — *both of these shipped; see the Status note*

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

## 9.5.10 The rail as built, and the spinner it does not have

### No spinner, and that is a correctness argument rather than a scope one

IntelliJ spins an icon because a process gives it no other sign of life. **Here it would be actively
wrong.** The steady state of an event-driven script is `LIVE` — loaded, handlers registered, waiting to
fire — and in that state *nothing is executing*. A spinner claims work is happening; it would be spinning
hardest for the scripts doing least.

The ticking elapsed time says everything the spinner would and one thing more: not just that it is alive
but for how long. And it has a property the spinner cannot: **it freezes** at the final duration, so a
finished row reads the same whether you watched it end or came back an hour later. A spinner only ever
says "now".

### Elapsed, and where the resolution comes from

Seconds are the smallest unit. Milliseconds change ten times faster than the eye reads and turn a quiet
rail into a flicker; the question people actually ask it is "is this still going, and roughly how long".
Two units, never three — `1 hr, 4 min` and not `1 hr, 4 min, 9 sec`, because the seconds are noise beside
the hours and the row is the width of a filename. Below a second reads `<1 sec` and **never `0 sec`**,
which would say "not started" about the one moment a script is most obviously alive.

> **The timestamps are deliberately outside what counts as a change.** `RunSessions.set` no-ops on an
> unchanged state, and that is the whole reason a per-tick script does not emit a signal twenty times a
> second. Comparing whole records would have defeated it outright — two readings a nanosecond apart are
> never equal — so the comparison is on `state` and `handlers` alone and the clock rides along.
>
> It also survives a handover between two ACTIVE states: a one-shot that registers handlers and settles
> into `LIVE` is one run, not two, and restarting there would report an hour-old script as seconds old.

### A SplitView, built lazily — revised from "a row, not a SplitView"

The first build made the rail a plain child of a row, on the grounds that a split **cannot go below two
panes** and so could not express "no rail yet". That reasoning was sound about `SplitView` and wrong about
the feature: a rail you cannot widen cannot show a long filename, and the width is a real preference.

Both facts still hold, so the answer is that **the split does not exist until the rail does**. The
transcript sits alone in the body until the first script runs; `showRail()` then builds a `SplitView`,
reparents the transcript into its second pane and puts the rail in the first. `hideRail()` reverses it.
Nothing is ever a pane sized to nothing — `applySplit` writes `flex-grow`, which divides only *free*
space, and `setPaneSizeLimits` clamps dragging rather than layout, so neither could have collapsed one.

> **The reparent happens when a script starts, and that is not incidental.** A widget must never rebuild
> the elements it is being clicked or dragged on. The rail appears in response to a *command* — from the
> editor, the menu or a key — so nothing inside the console is under the pointer at that moment. Doing
> the same move from a click in the transcript would detach the element the press is being dispatched
> through.

> **The minimum is on `.__run-left__`, not on the pane.** `split.first()` is the `__split-pane__` wrapper
> the `SplitView` makes for itself, and the divider's clamp reads the pane's **content** — so a
> `min-width` on the wrapper lands one level above where the clamp looks and the drag takes the rail
> below its own minimum. It is stated in pixels rather than as a percentage, because "at least 150px"
> stays true at every window size and "at least 15%" does not.

> And a pane is a flex **column** whatever the split's orientation, so its child grows by *height*.
> Reasoning from the split instead — "this one is horizontal, so grow by width" — collapses whichever
> guess is wrong, and it presents as a sibling problem rather than a sizing one.

The rail shows from the **first** script, keyed on *seen this session* rather than *live now* — one that
appeared and vanished as scripts finished would be worse than one that stays once earned. It was briefly
hidden below two on the IntelliJ analogy; that was wrong here, because a single run is exactly when
somebody wants to see its state and its elapsed time.

### The divider survives the session — `SessionState`

A dock record says where a panel *is*, never what is inside it, so a dragged divider had nowhere to
live and reset on every launch. The fix reuses what the engine already had rather than adding a panel
API: `SplitView` now answers for its own weights through the `writeState`/`readState` hook every widget
has, the split is given a stable id and `setSessionPersistent(true)`, and `UIWindow` hands it its
payload as it joins the tree. Full account in `docs/CGUI_WORKBENCH_SERVICES.md`.

> **The first version was a `PanelViewState` interface the panel implemented, and it was the wrong
> shape** — a second mechanism beside `writeState`, reaching only the panel's root, so a divider three
> levels down had to be proxied out by hand and back again. Worth recording because the interface
> version worked: it passed its tests and persisted the divider. Being made of the wrong parts is not
> something a test can report.

Two halves are specific to this panel:

- **Nothing is parked here any more.** The split is built when the first script runs, and it takes its
  remembered width from `registerElement` as it joins the window — so the rail arrives already the right
  width, with no field on the panel holding a value for a widget that does not exist yet.
- **The filter is deliberately not persisted.** One more line, and wrong: a remembered filter naming a
  script that is not running again opens the console empty for a reason three clicks away.

---

## 9.5.11 The review pass — what a final read of the whole thing found

Written 2026-08-16, after every exit criterion above was met, from a read of every class in
`language/.../run`, the `runpanel` block of `panels.css`, the run tokens in `base.css`, and the core seams
the panel leans on (`Keymap.acceleratorFor`, the editor's read-only paths, `ToolWindowManager.showPanel`).
It is ranked, and the ranking is the recommendation: A and B are the work; C and D are a sweep.

> **State, 2026-08-16 (same day). Everything in this section is done** — A1–A9, B1–B13, and both
> sweeps — across eight commits, with the run package at **167 tests passing and none skipped** (up from
> 133) and the language suite green at 514.
>
> Three entries closed themselves rather than being implemented. **B9** did not need the editor-wide
> context menu it was filed under: `ContextMenu` composes fixed items with contributed ones, so the
> console splices Copy and Select All in rather than declaring twins of them. Two **D** items were
> already gone — the byte-loop `ThreadLocal` hoist shipped with A2, and the per-line stack walk stopped
> being waste the moment B8 gave it a consumer. And two **C** entries stopped being dead by being used:
> `RunRail.showing()` (B5) and `RunConsole.transcriptSize()` (B4).
>
> One entry was **deliberately reduced**. B11 proposed two Preferences rows and shipped one: soft wrap
> did not get a global default, because B3 had just made the panel remember it per workspace and a
> default on top of that is two writers for one value with attach order deciding which wins.
>
> Three of the nine defects have **no test**, and the reason is structural rather than an omission:
> A4 (the soft-wrap mirror), A6 (the All-output tooltip) and A9 (Stop's source) are all widget wiring,
> and `language/src/test` has neither Taffy nor CrystalGraphics on its classpath — a `UIElement` cannot
> be constructed there at all. That is the same wall `TailFollow` was extracted to get around, and the
> same judgement applies: the logic worth pinning gets pulled out to where it can be tested, and pure
> wiring is verified in the harness. Everything with a seam that could be reached headlessly got one.
>
> Two entries changed shape once they were built, and both are recorded at the change rather than
> silently: **B8** was a decision and it went *wire it, not delete it* — see the row for the gesture
> argument, which the absence of reachable modifier state settles. **B9** turned out not to need the
> editor-wide context menu it was filed under: `ContextMenu` composes fixed items with contributed ones,
> so the console splices Copy and Select All in rather than declaring twins of them.

### A. Defects — things that are wrong today

| # | What | Where | Fix |
|---|---|---|---|
| A1 | **Rerun lies about its subject.** The tooltip says *Rerun 'Foo.java'* from the rail's selection and the button is dead without one — but the wiring runs `ScriptCommands.RUN`, which compiles the **active editor**. Select `A.java` in the rail with `B.java` on screen: the button says A and runs B | `RunPanel.describeAction`, `ScriptWorkbench.java:118` | `ScriptCommands` gains a run-this-`Resource` entry (from its open document if open, else disk); rerun passes `selected`. IntelliJ's rerun re-runs *that* configuration |
| A2 | **A prompt without a newline never appears.** `Routed` emits only on `\n` and deliberately not on `flush()`, so `print("Name? "); readLine()` shows the input row with no prompt, and `print("done")` as a script's last statement is lost | `ScriptOutput.Routed` | Emit the thread's partial line at the two moments it can no longer be completed: when `ScriptInput` is about to block, and in `ScriptOutput.exit()`. Cap the partial buffer (64KB) while there, so a script printing a 10MB string without a newline cannot grow it unbounded |
| A3 | **The ring is unbounded while the panel is closed.** `drain()` runs only from `RunPanel.tickFrame`, a hidden panel is *detached*, and a detached ticker unregisters — so `pending` grows without limit for a chatty script whose panel the user closed. The exit criterion "does not grow it without limit" holds only with the panel open | `RunConsole.append/drain` | Bound `pending` in `append` with an `AtomicInteger` char count: evict from the head past the budget and merge the count into `dropped` at the next drain (a `ConcurrentLinkedQueue` tolerates a producer polling) |
| A4 | **Alt+Z desyncs the wrap button.** `TextEditor` binds `editor.toggleSoftWrap` on itself, so the console honours it, but `__on__` is flipped only in the button's own handler | `RunPanel.java:252` | Pull per frame from `view.isSoftWrap()` in `refreshActions` — the same push→pull lesson Stop already paid for |
| A5 | **Running steals focus from the editor.** `onStarted` → `showPanel` → `requestPointerFocus(container)`, deliberately. IntelliJ's default is *activate* the tool window and *not focus* it ("Focus tool window" is off) — Shift+F10 then typing should keep typing | `ScriptWorkbench.install` | In the hook: remember `activeEditor().isFocused()`, show, restore with `requestPointerFocus()`. Expressible from `language/` without naming `UIInputHandler` |
| A6 | The **All output** row's tooltip reads *Never run* | `RunRail.Rows.bind(null, …)` | Say "All output", or attach none for that row |
| A7 | **`ScriptInput.Routed.buffered/position` are shared, not per-thread.** A script that reads one byte of a line and ends leaves the rest for the next script's first `read()` | `ScriptInput.Routed` | A `ThreadLocal`, as `ScriptOutput.Routed` already does; or clear on `ScriptOutput.exit` |
| A8 | **A failure is attributed to the last thing COMPILED, not the thing that threw.** `report()` labels the trace with `lastScript`, which `compileActive` overwrites before it knows the compile succeeded — run A, press Run on a broken B, and A's later exception arrives labelled B | `ScriptWorkbench.lastScript` | The host knows the `ScriptRef` of the run that failed; hand it to `onFailure` (`BiConsumer<ScriptRef, Throwable>`) or let the host append the trace itself |
| A9 | **Two truths for "is anything running".** The Stop *command* is enabled by `host.isRunning()` (a thread is alive); the Stop *button* by `sessions.active()` (a state was reported). A script that swallows the stop leaves `running` null and its session `RUNNING` — the button stays red and pressing it does nothing | `ScriptCommands`, `RunPanel.refreshActions` | One source; the button should mirror the command's enablement, since that is what the menu row shows |

### B. Foundational gaps — what every reference console has and this does not

| # | What | Why it is foundational | Shape |
|---|---|---|---|
| B1 | **No end-of-run line.** A boundary is printed at start and nothing at the end. IntelliJ: *Process finished with exit code 0*; VS Code: *[Done] exited with code=0 in 0.53 seconds* | With All output showing two scripts interleaved you cannot tell where one run's output ended — and a run that printed nothing is indistinguishable from one that never ran | The `sessions.onDidChange` listener that draws the opening divider appends `Main.java finished in 1.2 sec` / `stopped after 4 sec` / `failed after 0.3 sec` as a `comment`-coloured divider, elapsed read from the session |
| B2 | **Scroll-to-End is a button, not a state.** IntelliJ's stays *pressed* while following | There is no visual answer to "will new output pull the view down?", which is exactly the question a reader has after scrolling up | `toEnd` takes `__on__` per frame from `follow.isFollowing()`, as `wrap` does |
| B3 | **Wrap does not persist.** IntelliJ remembers soft-wrap on the console; `SessionState` now exists and the panel does not use it | The one setting a console has, forgotten every launch | `writeState`/`readState` on `RunPanel` for wrap; `setId` + `setSessionPersistent(true)` from `RunPanels.install` |
| B4 | **Clear All is live over an empty transcript.** IntelliJ dims it | Same rule Stop and Rerun already follow: a dead control leaves hit testing | `clear.setEnabled(lineCount() > 0 \|\| transcriptSize() > 0)` in `refreshActions` |
| B5 | **A new run does not select its row.** IntelliJ switches to the new run's tab. Here, with `B.java` selected in the rail, running `A.java` shows nothing new — the console reads as dead | The filter is a view over the transcript, and starting a run is the strongest signal of what you want to see | Per frame (pull — the session signal is on the script thread): if a session became active since the last frame and the rail is not on All, select it. Leave All alone |
| B6 | **A script blocked on `System.in` with the panel closed hangs invisibly.** The panel opens on run; close it, and a later read has nothing on screen to say why the script stopped | The input row *is* the prompt (9.5.9's own argument), and a prompt nobody can see is not one | Reading stdin brings the panel forward the way running does — a `JobScheduler` hop from `awaitInput`, since it is on the script thread; at minimum the stripe badge |
| B7 | **Stop has no subject.** `onStopRequested` is a `Signal.Action`. Correct today only because `ScriptHost` holds exactly one `Running`; `RunSessions`, `RunRail` and `RunState.LIVE` are all built for several live scripts, and when that lands this goes wrong silently | One call site now; several later | `Signal.Value<Resource>` with null meaning "whatever is running" — the seam costs one signature while there is one caller |
| B8 | **The per-line origin is computed and never used.** `ScriptOutput.message` walks the stack for every `println` to record the script's own line, and nothing navigates to it since collapse went — only `Line.file()` (which the `ScriptRef` already knew) is read | Either it is Unity's gesture — double-click a plain output line opens the `println` that produced it, which the plan's own reference does — or it is a stack walk per line for nothing | Decide. Wiring it is a click on a non-link line → `onLinkActivated` with the origin; dropping it removes the walk and `RunMessage.at`'s line. Not both |
| B9 | **No context menu on the console.** `MenuId.EDITOR_CONTEXT` is declared and nothing attaches it, so this is an editor-wide gap and not a Run one — but Copy / Select All / Clear All / Scroll to End on right-click is what people reach for in a console first | Out of 9.5's scope. Recorded so it is not rediscovered as a Run bug | Belongs to the editor; the console inherits it the day it exists |
| B10 | **ANSI escapes are shown, not interpreted.** IntelliJ's console decodes them; a library that colours its own log lines prints `[31m` here | Low for in-JVM Java scripts, higher the moment JS or a logging binding lands | A `ConsoleFilter`-shaped decoder is the wrong seam (filters find spans, they do not rewrite text); it is a pass in `Routed.emit` |
| B11 | **The buffer size and wrap default have no Preferences entry.** IntelliJ exposes the cycle buffer size in Settings; ours is `DEFAULT_BUDGET_KB` | Low; recorded because the constant is the kind that gets edited in code when a setting was wanted | Two rows in `Preferences`, once B3 gives the panel state to write |
| B12 | **A deleted script keeps its rail row.** `RunSessions.forget` exists and nothing calls it | Cosmetic until a workspace deletes files often | The explorer's delete path calls `forget` |
| B13 | **No empty state.** A panel nothing has run in is a black rectangle under a rerun/stop bar with nothing to rerun or stop. IntelliJ centres a note — *To run your code, do one of the following: — Click the Run icon in the editor gutter — Select "Run…" in the editor context menu — Launch a run configuration (Alt+Shift+F10)* — and shows **no toolbar** until there is a run | The first thing a new user sees is the panel with nothing in it, and a blank surface says "broken" where a sentence says "not yet". Every reference tool window with a precondition does this (Problems: *No problems*, Notifications: *No new notifications*) | A centred `UIText` block attached while `sessions.scripts()` is empty and detached on the first run — same attach/detach rule as the rail and the input row, for the same `gap-all` reason. Lines: *To run a script, open a `.java` file and:* — *press Run (Shift+F10)* — *choose Run ▸ Run Script from the menu*. **The accelerator is read from the keymap** (`Keymap.acceleratorFor(this, ScriptCommands.RUN)`), never spelled — the tooltip rule. And the run bar + separator hide with it: rerun and stop over nothing are the dead controls the panel already refuses elsewhere. Wants `--run-empty-fg` (→ `--fg-hint`) and a `.__run-empty__` rule; the note is `setHitTest(false)` |

### C. Cleanups

- **Dead code:** `RunMessage.collapseKey()`, `RunMessage.weight()`, and the `origin` javadoc all describe the
  removed collapse; `ScriptOutput.java:140` still says "it simply does not collapse". `RunPanel.SPACER_CLASS`;
  `RunRail.showing()` (never called); `RunPanel.onFilterRequested` (no listener); `RunConsole.transcriptSize()`
  is test-only; `RunSessions.clear()`/`forget()` unused (see B12 before deleting the second).
- **Dead CSS:** `.__run-filter__` and its `:hover` — the dropdown they style is gone. Stale comments at
  `panels.css:644–657` ("a plain ROW rather than a SplitView … a FIXED width") and the reference to
  `.__run-vsep__`, which does not exist; the "A console control is a glyph" comment is present twice.
- **Inline FQNs** (the house rule is import, never inline): `RunConsole` — `CopyOnWriteArrayList`,
  `LinkedHashSet`, `ArrayBlockingQueue`, `Objects`; `RunRail` — `Map`, `HashMap`, `Locale`, `SelectionMode`;
  `RunSessions` — `LongSupplier`; `ScriptHost` — `Modifier`, `Consumer`; `ScriptOutput` — `IOException`.
- `RunPanel`: unused `import java.util.List`; imports out of order (`Tooltip` after the keymap pair); the
  orphan doc comment at `RunConsole.java:110`; `refreshActions` reads `sessions` twice into `listing` and
  `running`.
- The **What it reuses** section below was written for the list version and still names `ListView` and
  `TreeSearch`; corrected in the same edit as this section.

### D. Optimisations — per frame, none urgent

- `RunRail.writeRow` runs `querySelector` twice per realised row every frame and rebuilds the tooltip string;
  a `Row` holder built in `createTemplate` (glyph/name/time fields) removes both walks — the "slots built in
  `createTemplate`" pattern the tree rows already use.
- `RunSessions.scripts()`/`active()` allocate a fresh list under lock per call, and `refreshRail` +
  `refreshActions` call them three times a frame. A `boolean anyActive()` and a version counter (rebuild
  `known` only when it moved) make the settled frame allocation-free.
- `ScriptOutput.Routed.write(byte[])` fetches the `ThreadLocal` buffer per byte; hoist `pending.get()` once
  per call.
- `ScriptOutput.message` walks the stack per line — see B8; the answer there decides whether this stays.
- `refreshActions` calls `Keymap.acceleratorFor` twice a frame — a parent walk and a map lookup. Fine.

### Not findings — checked and sound

The thread rules (queue in, drain on the frame; pull enablement, never push); the tail-follow lock; the
ring measured on the transcript rather than the document under a filter; links recomputed from row text
rather than stored as offsets; the input queue drained only on interruption; the always-original-stream
rule in both routers; the read-only editor still answering Ctrl+F, Ctrl+A, Ctrl+C and refusing Ctrl+X/V
through its own `enabledWhen`; the prelude keeping the author's line numbers, so a trace's `Main.java:12`
is the line on screen.

---

## What it reuses

`TextEditor` — the transcript is a plain one, configured, and everything a console needs (selection,
drag-select, copy, virtualised rows, find) came with it. `SplitView` for the rail, `ListView` for its rows.
`Workbench.openFile` with a continuation for stack-trace navigation. `FileDecorations` for the indicator.
`SessionState` for the divider. The dock panel shape of `ProblemsPanel`.

## Exit criteria

- `System.out.println` from a **one-shot** script and from a **tick handler on the game thread** both
  reach the console, and Minecraft's own logging reaches neither.
- A script printing every tick does not flood: the **ring** bounds the transcript and an eviction is
  reported rather than dropped silently. (Collapsing was removed with the list — see 9.5.4.)
- The rail shows a tick-driven script as `Live` and does not strobe, and its elapsed time ticks while the
  script is active and freezes at the final duration once it is not.
- The divider between the rail and the transcript is draggable, holds at a width that can still show a
  filename, and comes back where it was left — including when the panel is not opened until after the
  session has been restored, and including across a session that never opened it at all.
- Selecting a rail row narrows the transcript to that script; the All row restores it.
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
