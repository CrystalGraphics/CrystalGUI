# M9.5 — The Run panel

Detail for the M9.5 row in `plan_syntax.md` §20. A dock panel beside Problems and Notifications
carrying the output of running scripts, plus the indicator that says which files are live.

## Status

| § | Item | State |
|---|---|---|
| 9.5.1 | Capturing output — the thread-local marker | **done** — `ScriptOutput` + `ScriptHost` bracket, 11 tests |
| 9.5.2 | The console as a text area | **rewritten from a ListView** — see below; the list was the wrong shape |
| 9.5.3 | States | **done** — `RunState`/`RunSessions`, reported from `Running.invoke`, 12 tests. **The rail itself is not built** |
| 9.5.4 | The ring | **done** — collapsing removed with the list; see below |
| 9.5.5 | The running indicator | **done in the tree** — `RunDecorations`, 6 tests. **Editor tabs still read no decorations** |

Written before any of it existed; the states above are current. What remains is the rail, the per-script
filter, the editable input line, clickable stack frames, and the editor-tab half of the indicator.

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

## What it reuses

`ListView` — virtualised, and a firehose demands it. `TreeSearch` for the filter, in the permanent
presentation rather than the transient one, since a console's filter is how you are expected to start.
`Workbench.openAndReveal` for stack-trace navigation. `FileDecorations` for the indicator. The dock
panel shape of `ProblemsPanel`.

## Exit criteria

- `System.out.println` from a **one-shot** script and from a **tick handler on the game thread** both
  reach the console, and Minecraft's own logging reaches neither.
- A script printing every tick does not flood: identical *origins* collapse with a count, and a
  per-tick overflow is reported rather than dropped silently.
- The rail shows a tick-driven script as `Live` and does not strobe.
- Stopping a script through §19.3 moves it to `Stopped` and **leaves its transcript**.
- A stack-trace frame opens the file at the line.
- A running script's file is marked in the tree **and** on its editor tab; its folder takes the colour
  and not the badge.
- A runtime exception appears in the console and produces **no** Problems row.
- The buffer is bounded: a script printing without pause does not grow it without limit, and when the
  oldest output is evicted the panel says so rather than quietly beginning in the middle.

## Position

**Before M10, and not cosmetically.** M10's exit criteria include *"post-run completion on a live
object"* — which requires running a JS script and observing what it did. Without a console, running a
script is invisible, so M10 would be built and demonstrated blind. JS also needs a `console.log` binding
on day one, and §9.5.1 is where it lands.

Depends on M7 (the execution substrate and §19.3's kill flag) and M9 (the UI vocabulary). Both are done.
