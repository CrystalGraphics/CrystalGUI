# P6.1.13 — Progress: long work that the user can see, and stop

**Status:** planned, not started · **Planned:** 2026-08-19

The engine-band download decision (see `plan_m12.md` §26.14 and the band-size analysis) needs a progress
UI, and so does P6.1.10's chunked transfer, and so does something that already ships and is silent today.
This is the plan for building it once.

> **Provenance.** The IntelliJ and VS Code sections below are written from their **published API surface
> and observable behaviour**, not from a source checkout — neither is in `research_repos/`. Everything
> stated as an API signature is checkable against their public docs; everything stated as *behaviour*
> (timings, what the status bar shows) is from use and is marked where it should be verified before it is
> relied on.

---

## Contents

- [Why now](#why-now)
- [What exists to build on](#what-exists-to-build-on)
- [IntelliJ, classified](#intellij-classified)
- [VS Code, classified](#vs-code-classified)
- [Where they disagree, and which side to take](#where-they-disagree-and-which-side-to-take)
- [Decisions](#decisions)
- [The design](#the-design)
- [What this deliberately does not include](#what-this-deliberately-does-not-include)
- [Risks](#risks)
- [Work order](#work-order)
- [How this stays correct](#how-this-stays-correct)

---

## Why now

Three consumers, and they are not hypothetical.

| Consumer | State today | Shape |
|---|---|---|
| **MCP mapping fetch** (`PlatformMappings`) | **Ships. Silent.** Runs on a bare `new Thread(...)`, outside `JobScheduler` entirely, reporting one line to stderr | determinate bytes, ambient |
| **Engine band download** | Decided, not built. Band 8 bakes into the jar; 17 is fetched on a GTNH/lwjgl3ify host | determinate bytes, ambient, ~16 MB |
| **Chunked file transfer + manifest resolve** (P6.1.10 D11) | Deferred; protocol shape reserved. Hard cap 100 MB | determinate bytes *and* indeterminate, inline |

The first is the argument. A first launch already stalls on a network fetch with nothing on screen, and
the mechanism it uses is the one thing `JobScheduler`'s own javadoc argues against — *"one pool, not one
per feature — three pools compete for the same cores and none of them knows about the others."*

P6.1.10 names the need in a single line and leaves it there:

> **Progress** — determinate bar during chunked transfer; *Loading project…* while a manifest resolves

That is a requirement, not a design. This document is the design.

---

## What exists to build on

**`JobScheduler` is the right substrate and is already the right shape.** One shared pool; every decision
made on the UI thread inside `drain()`; `JobKey` replacement semantics; cancellation by generation bump;
`onDone` documented to run on the UI thread. It was built for the analyser and it is general.

**`JobContext` has cancellation and no progress channel.** `isCancelled()` and `throwIfCancelled()`
exist; there is nothing to report *forward* through.

**There is no progress widget anywhere in `core/`.** `ProgressFunctions` is easing maths and unrelated.

**`StatusBarView` is the host and is ready for one** — left/right regions, `__status-item__`, a
clickable-item convention, a hide menu, `refresh()`.

**`Popover` + `AnchoredPlacement`** are what the processes popup is made of, and `AnchoredPlacement` is
the only thing permitted to write `left`/`top` on a promoted popup.

---

## IntelliJ, classified

The reference the screenshots come from, and the closer of the two to what we want.

### The indicator

`ProgressIndicator` is the whole surface a task writes to:

| Member | Meaning |
|---|---|
| `setText(String)` | The primary line — *what is happening* |
| `setText2(String)` | The secondary line — *which item*, usually a file or artifact name |
| `setFraction(double)` | **Absolute**, 0..1 |
| `setIndeterminate(boolean)` | Switches between a bar and a moving stripe |
| `isCanceled()` / `cancel()` | The flag, readable by the worker and settable by the UI |

`ProgressManager.checkCanceled()` is the cooperative checkpoint, and it **throws**
`ProcessCanceledException` — a control-flow exception that must not be swallowed by a broad `catch`.
IntelliJ ships an inspection for exactly that mistake, which is a fair signal of how often it is made.

### The task

`Task.Backgroundable` / `Task.Modal`, with `onSuccess` / `onCancel` / `onThrowable` delivered on the EDT.
A modal task may carry a **"Run in Background"** button that demotes it to the status bar mid-flight —
the single best idea in their model, and the one that makes a modal tolerable.

### The presentation — what the screenshots show

The status bar carries **one** inline indicator: text, a determinate bar, and a `×`. Clicking it opens the
**Processes** popup, which lists *every* running process with its own bar and its own cancel, and a
`Hide processes (N)` link at the foot. So the chrome cost is constant regardless of how many things run,
and the full set is one click away.

> **To verify before relying on it:** IntelliJ does not show a background task immediately — short work
> never reaches the status bar at all. The delay is on the order of a few hundred milliseconds. The exact
> figure is not published as API and should be treated as ours to choose, not ours to copy.

### Nesting

Indicators nest: a sub-task's 0..1 maps into a slice of its parent's. Powerful, and the source of most of
the fraction arithmetic bugs in plugins.

---

## VS Code, classified

A smaller API that makes two decisions worth stealing and two worth refusing.

```ts
window.withProgress(
  { location: ProgressLocation.Notification, title: "…", cancellable: true },
  (progress, token) => Thenable<T>
)
progress.report({ message: "…", increment: 10 })
```

| Location | Presentation |
|---|---|
| `Window` | **Status bar: a spinner and a title. No determinate bar at all.** |
| `Notification` | A toast with a determinate bar and an optional cancel button |
| `SourceControl` | The SCM view's own spinner |

Three properties worth naming:

- **`increment` is a DELTA percentage**, and omitting it entirely leaves the bar indeterminate. There is
  no absolute setter.
- **Progress ends when the returned promise settles.** There is no `done()` to forget.
- **`CancellationToken`** is passed in rather than polled off a manager — `isCancellationRequested` plus
  an event.

---

## Where they disagree, and which side to take

| # | Question | IntelliJ | VS Code | **Take** |
|---|---|---|---|---|
| 1 | Fraction | absolute `setFraction` | delta `increment` | **IntelliJ.** A delta that is dropped or double-counted desyncs the bar *permanently and silently*; an absolute value cannot drift. Streams have a byte counter already — reporting `done` costs nothing over reporting `delta` |
| 2 | Text slots | `text` + `text2` | one `message` | **IntelliJ.** "Downloading engine band 17" and "org.eclipse.jdt.core-3.46.0.jar" are different sentences, and one line of chrome has room for the first with the second as detail. A single slot forces every caller to concatenate, and they concatenate differently |
| 3 | Lifetime | the task must end | tied to a promise | **VS Code.** A bar that outlives its work is the classic progress bug. Ours can be impossible by construction: progress lives exactly as long as the `Job`, and `JobScheduler` already owns that lifetime |
| 4 | Cancellation | `checkCanceled()` throws | token flag + event | **Already ours.** `JobContext.throwIfCancelled()` / `JobCancelledException` is IntelliJ's shape and it predates this plan |
| 5 | Determinate in the status bar | yes, a real bar | no, spinner only | **IntelliJ.** It is what the screenshots show and what was asked for. VS Code's refusal is a defensible quietness argument, and we are choosing the other side deliberately |
| 6 | Many processes | one inline + a popup listing all | one notification each, stacking | **IntelliJ.** Constant chrome cost. Stacking toasts for background work nobody asked for is noise |

---

## Decisions

| # | Decision | |
|---|---|---|
| **D1** | Reporting channel | **`JobContext.progress()`.** Every long job already receives a context, cancellation already lives there, and the scheduler is already the one place that crosses the thread boundary correctly |
| **D2** | Absent-value | **`Progress.NONE`, never null.** A job never branches on whether anyone is watching. Same principle as `CgService`'s absent-value, and it is what lets the band download report unconditionally |
| **D3** | Write-only | **No `isCancelled()` on `Progress`.** `JobContext` already answers that; two answers to one question is the smell removed from `ScriptServices` |
| **D4** | Fraction | **Absolute** — `advance(long done)` against the `total` given at `begin` |
| **D5** | Indeterminate | **`total < 0`**, not a second type and not a flag to forget |
| **D6** | Text | **Two slots**: `begin(what, total)` sets the primary; `detail(String)` sets the secondary |
| **D7** | Threading | **The UI PULLS on the frame.** The worker writes volatile fields on a per-job record; nothing the worker calls ever reaches a listener. See the risk below — this is the one non-negotiable |
| **D8** | Visibility | **Opt-in and delayed.** A job appears only once it calls `begin()`, and only after a short delay; short work never reaches the chrome |
| **D9** | Minimum visible time | **Once shown, stays shown briefly.** Without it a 40 ms tail flickers a bar in and out |
| **D10** | Status bar | **One inline indicator** — primary text, determinate bar, `×` — plus a count when there are more |
| **D11** | The popup | **A `Popover`** listing every active job with its own bar and cancel, placed by `AnchoredPlacement` |
| **D12** | Ordering | **Most recently begun first.** Not by progress, which reorders under the cursor |
| **D13** | Nesting | **Flat in v1.** Sub-progress is where the fraction bugs live; a phase name (`detail`) covers the cases we have |

---

## The design

### The channel — `core/async`

```java
public interface Progress {
    Progress NONE = …;                     // a no-op sink

    void begin(String what, long total);   // total < 0 → indeterminate
    void advance(long done);               // ABSOLUTE, against total
    void detail(String item);              // the secondary line
}
```

`JobContext.progress()` returns one, never null. A job that reports nothing costs one field read.

### The record, and why the UI pulls

The scheduler keeps one record per job with `volatile` fields (`what`, `detail`, `done`, `total`,
`begunAtMillis`). `Progress` writes them from the worker. `JobScheduler.active()` returns an immutable
snapshot, and the status bar reads it **during its own frame** like any other widget.

No signal is emitted from a worker thread, because there is no signal. That is not fastidiousness — see
[Risks](#risks).

### The widgets

- **`ProgressBar`** in `ui/elements` — determinate and indeterminate, geometry in `default.css`,
  appearance in the theme, indeterminate motion as a CSS transition or a `UIFrameTicker`. No pixel value
  and no duration in Java.
- **A status-bar item** in the right region: primary text, a `ProgressBar`, a cancel glyph, and a count
  when `active().size() > 1`. Clickable, per `StatusBarView`'s existing convention.
- **`ProcessesPopover`** — a `Popover` over the item, one row per active job, each with its own bar and
  cancel, and a *Hide processes (N)* foot.

### Cancellation

The `×` calls `JobScheduler.cancel(key)`, which already exists and already bumps the generation so a
result in flight is discarded. The worker notices at its next `throwIfCancelled()`. Nothing new.

---

## What this deliberately does not include

- **Modal progress**, and the *Run in Background* demotion with it. It is IntelliJ's best idea here and it
  is a dialog-lifecycle feature, not a progress feature. Named so it is not re-derived; deferred so this
  ships.
- **Nested progress.** D13.
- **Per-job history.** A finished job disappears. The Run panel is where transcripts live.
- **Throughput or ETA.** Both need a rate estimator to be anything other than a lie on a stalled socket.

---

## Risks

1. **The worker-thread crash, and it is documented.** `AGENTS.md` records it: a signal emitted by a worker
   thread carried that thread into `invalidateStyleMatch()`, which mutated `StyleEngine`'s dirty-match
   `HashSet` while the UI thread copied it — `ArrayIndexOutOfBoundsException` out of `HashMap.keysToArray`,
   thrown inside `advanceFrame`, **with nothing about the originating feature in the trace**. A `Progress`
   that lets a worker call a listener reproduces it exactly, and it will present as a bug in an unrelated
   widget. D7 makes it unreachable rather than merely discouraged.
2. **Chrome strobe.** `JobScheduler` runs an analysis on every keystroke. If every job appeared, the status
   bar would flicker continuously. D8 and D9 are the whole answer, and they are the two most likely things
   to be got wrong.
3. **Reporting cost.** A `detail()` per 8 KB chunk is ~2,000 string allocations for a 16 MB download, on a
   worker, for a bar that redraws 60 times a second. Reports must be rate-limited at the source — bytes
   accumulate, text changes per *file*, not per chunk.
4. **A 16 MB fetch must not block the editor opening.** It is a `Job`, it degrades to grammar-only
   colouring while it runs, and the editor opens regardless. That is a property of where the download is
   started, not of this API — noted here because it is the failure the API makes tempting.

---

## Work order

| # | Step | Why here |
|---|---|---|
| 1 | `Progress` + `NONE` + `JobContext.progress()` + the per-job record | Headless, no UI, fully testable |
| 2 | `JobScheduler.active()` snapshot, D8/D9 timing | The policy, with a fake clock — before anything draws |
| 3 | **Move `PlatformMappings` onto `JobScheduler`** and report | The consumer that already ships. Fixes the bare thread in the same change |
| 4 | `EngineSource.downloadedFrom(...)` — digest-verified, reporting | The decision that prompted this. Maven Central publishes `.sha1`, so unlike the mappings this can be properly pinned |
| 5 | `ProgressBar` + `default.css` | First pixels |
| 6 | The status-bar item | D10 |
| 7 | `ProcessesPopover` | D11 |
| 8 | Harness scene | Two fake jobs, one determinate and one not |

Steps 1–4 are useful with no UI at all: the mapping fetch stops being a rogue thread and the band download
becomes possible, both before a bar exists.

---

## How this stays correct

Testing the spine, per the project's rule — never pixel geometry.

- **`Progress.NONE` is never null**, and a job that reports nothing behaves identically to one that does.
- **A job that never calls `begin()` never appears** in `active()`. The strobe test.
- **Delay and minimum-visible**, on a fake clock: work shorter than the delay never appears; work that
  ends immediately after appearing stays for the minimum.
- **A superseded job's progress is discarded**, matching the existing rule that its *result* is.
- **Cancel reaches the worker** — `cancel(key)` then `throwIfCancelled()` throws.
- **No listener on a worker thread**, asserted structurally: `active()` is a snapshot and `Progress` has
  no subscribe method to misuse.
- **Headless throughout.** Steps 1–4 need no GL and no window; the harness covers the drawing.
