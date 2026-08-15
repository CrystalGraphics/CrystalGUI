# M9.5 — The Run panel

Detail for the M9.5 row in `plan_syntax.md` §20. A dock panel beside Problems and Notifications
carrying the output of running scripts, plus the indicator that says which files are live.

## Status

| § | Item | State |
|---|---|---|
| 9.5.1 | Capturing output — the thread-local marker | not started |
| 9.5.2 | One console, filtered | `RunPanel` written and compiling; **its UI test is deferred — see below** |
| 9.5.3 | The live-script rail and its states | not started |
| 9.5.4 | Collapse by call site | not started |
| 9.5.5 | The running indicator | not started — free in the tree, new on tabs |

Nothing exists today. `ScriptHost` has no output mechanism of any kind and `ScriptControl` is only the
safepoint checkpoint, so this is a clean slate rather than a retrofit.

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

## 9.5.2 One console, filtered — not a tab per run

Scripts are concurrently live, and the question actually being asked is *"what just went wrong"*, which
needs one place to look rather than a tab hunt. Every message carries its script, and the panel
**filters** by script — which is the per-script view on demand, without paying for it always.

This is Unity's and the browser's choice; IntelliJ's tab-per-run only works because a run is a process
and a process is the thing you were watching.

**Navigation:** a stack-trace frame resolves through `Workbench.openAndReveal`, which M11 §24.7 already
built for exactly this shape of caller. Double-click on a frame opens the file at the line.

---

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

## 9.5.4 Collapse by call site, not by message text

Unity's Collapse *"displays only the first instance of recurring error messages"* and its manual
recommends it for *"run-time errors, such as null references, that are sometimes generated on each frame
update"* — the same pressure a tick handler puts on this panel, which is the strongest evidence the
model is the right one.

> **Its manual does not say what it matches on**, and this section originally asserted it was message
> text. That was not checked and is not documented. The argument does not need it: whatever Unity keys
> on, **collapsing by text alone does not solve our case**, because a handler printing `tick 1`,
> `tick 2`, `tick 3` produces a different string every time and would not fold at all.

Collapse by **origin**: `foo.js:12 ×340`. The origin is already known at log time from §9.5.1's marker,
so it costs nothing, and text-identical collapse falls out of it as the special case where one call site
also says one thing.

**A per-tick budget, with an honest tail.** Beyond some count in one tick, drop and say so —
`… 340 more from foo.js this tick`. Never silently. That is this repository's own rule: *if a workflow
bounds coverage, log what was dropped; silent truncation reads as "covered everything" when it didn't.*

**Clear on run**, matching Unity's Clear on Play, as a toggle rather than a default.

### The buffer is bounded, and "survives" does not mean "unbounded"

The decision above is that output survives a script stopping. **That is not the same as keeping it all**,
and a per-tick script would otherwise grow the buffer until the game dies.

IntelliJ answers this with a **console cycle buffer** — `Settings | Editor | General | Console`,
*"Override console cycle buffer size"*, specified **in KB rather than in lines**, global across every
console, with its own documentation warning that *"large buffer size can affect performance in the case
of chatty processes."* A chatty process is precisely what a tick handler is, so the reference's own
caveat is our normal case rather than an edge one.

So: a ring, sized in KB, exposed as a setting. **KB and not lines**, because one stack trace is worth
thirty prints and a line count would let a single exception evict a run's whole transcript. Dropping the
oldest is reported the same way a per-tick overflow is — the panel says the transcript was truncated
rather than quietly beginning in the middle.

---

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
