# CrystalGUI — profiling

What CrystalGUI adds to the trace engine: its channels, what one UI frame records, the Frame Profiler, and
how to profile a scene, the desktop or the game in one run. **Read
[`CrystalGraphics/docs/PROFILING.md`](../CrystalGraphics/docs/PROFILING.md) first** — the engine, the
one-run rule, the pre-run checklist, reading a report and the conventions are all there, and none of it is
repeated here. The invocable checklist is the `profiling` skill.

---

## The rule, restated for a UI

One run answers the question. A UI frame is wider than it looks — a paint that seems slow is often a
cascade two phases earlier, a hook a widget registered, a job's completion, or a text measurement buried
in layout — so **map the frame before instrumenting it**, with [§ The frame](#the-frame) as the skeleton
and every widget, hook and job on your path hung off it. Then record every channel the map names, and read
the coverage (`unzoned`, `unexplained`, `GAP`, `SELF TIME`) before any number.

---

## Channels

| Channel | Records | Cost | On for |
|---|---|---|---|
| `crystalgui.frame` | the frame's phases and counters | two clock reads a zone | every timing question |
| `crystalgui.flow` | spans: chains that cross frames or happen outside one — open a file, close a tab, an edit, a search | the same | "this action is slow", never "this frame is slow" |
| `crystalgui.fs` | the workspace server's filesystem: each `fs:stat` and its path checks, `fs:reconcile.sweep` on the worker, `watch-rechecked`, `watch-sweep-busy` | cheap | a hitch from the watcher, or a sweep that has stopped (`watch-sweep-busy` climbing) |
| `crystalgui.blame` | a stack walk per invalidation, written as `invalidated-by` markers | **expensive** — once a quarter of the frames it measured | *who* causes churn. **Never on while timing** — take the numbers in one run and the attribution in another, or record both and ignore its frame times |

`CgTrace.enable("crystalgui")` takes all three. The one-run default for a UI question:

```
-Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,crystalgraphics,gpu
```

---

## The frame

A `UIDocument` frame, and what each part records. Use this as the skeleton of the map.

| Phase | Zones (`crystalgui.frame`) | Counters | What sits inside that may need its own zone |
|---|---|---|---|
| Jobs | `frame:jobs`, and `done:<job>` per completion inside it | `jobs-busy` | the completion callbacks' own work |
| Hooks | `frame:hooks` | — | every `Animation` per-frame hook a widget registered — no per-hook zones |
| Style | `style:drainDirtyMatch`, `style:prematch` (a large round matched on workers), `style:transitions`, `style:warmValues` (a sheet's values parsed on a worker) | `rematched`, `whole-window-invalidations`, `style-matched`, `style-shared`, `style-prematched`, `style-match-us`, `style-apply-us` | who invalidated: `crystalgui.blame` |
| Layout | `frame:layout` | — | Taffy's compute and every `Measurable.measure` (text measurement); CrystalGraphics' text zones show shaping |
| After layout | `frame:afterLayout` | — | every `afterLayout` hook |
| Input | `frame:input` | — | the hover diff and every listener the frame's pointer events reach |
| Hover | `frame:hover` | — | the re-style, layout and settle when the hover moved |
| Paint | `paint:tree`; `glbegin:*`, `glend:flush`, `glend:image:*`; `layer:clear`, `layer:blit`, `layer:mask`; `backdrop:capture`, `backdrop:blur`; `svg.*`, `svg-raster:build` | `drawcalls`, `layers`, `layers-d`, `layers-elided`, `masks-elided`, `clips-rounded`, `clips-square`, `segments`, `segment-chunks`, `segments-unchanged`/`-changed`/`-dynamic`/`-unkeyed` (whether a box's own paint would come out as last frame's: replay's measure), `culled`, `retain-*`, `layer-*-kpx`, `backdrop-capture-kpx`, `scissors`, `*-switches`, `layer-fbos`, `svg-*` | **each widget's `paintContent`/`paintDecoration`** — a slow widget shows as a `GAP` in `paint:tree` |
| Editor | `ed:*`, `ln:*`, `part:<name>` | — | already zoned per phase; `ed:*` spans on `flow` for edits |
| GPU | `gpu:ui` (the whole paint, composite included) | — | — |

**Who brackets the frame**: `UIDocument.frame` calls `UiTrace.frameBegin()` (committing the previous
frame) and the paint context's `endFrame` calls `UiTrace.frameEnd()` (the CPU mark). **One document per
frame**: a second `UIDocument` framed in the same real frame splits every frame in two, halving the wall
times with no error — frames on a host with two documents are not comparable. Parked as P1 in
`plan/crystalgraphics/platform-trace-engine.md`.

### Blame

`UiTrace.blame("rematch", "com.crystalgui.style.")` attributes one occurrence to the first caller outside
the given packages, capped and strided per frame so the totals hold. The frame then carries
`invalidated-by Tooltip.reposition:214 x280` — the call site to change, rather than a count. Add a blame
call wherever a count tells you *how much* churn and you need to know *from where*.

### Hints

Rules over counter names, run on every slow frame — in the report and the window: `LAYER-BOUND`,
`RETENTION-REFUSED`, `CASCADE-CHURN`, `COLLECTED`, `ICONS-DIRECT`, `FIRST-DRAW`, `GPU-BOUND`,
`BLOCKED-ON-WORKER` (`core.trace.UiHints`). **A hint reads a counter by its name**: renaming a counter
silently disarms the hint that reads it — change both together.

---

## Where it runs

### The harness — the default

Any CrystalGUI scene, profiled with no code, one folder:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop" \
    -Dcrystalgraphics.harness.profile=300 \
    -Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,gpu
```

**The channels property is not optional here**: profile mode switches on only CrystalGraphics' own
channels, so without it a CrystalGUI scene records none of CrystalGUI's phases. **Nor is a longer warm-up
for `cgui-desktop`**: it generates glyphs and starts the language stack for seconds, so 30 frames profile
its startup — add `-Dcrystalgraphics.harness.profile.warmup=600` and check that the first profiled frames
are not the slow ones (CrystalGraphics' `PROFILING.md` § *Noise*). Read
`gl-debug-harness/harness-output/<scene>/profile-harness-<n>f/report.txt` first; the last run is beside it
as `.prev` (CrystalGraphics' `PROFILING.md` § *Where it runs*).

**`cgui-desktop`'s slow-by-wall frames are its own**, not the machine: `scene:workspacePump`, the
in-process workspace's network tick, stalls about 45 ms every few seconds (2026-09-30). It runs after the
cpu mark, so it shows as idle — the verdict names it.

| Scene | Profile it for |
|---|---|
| `cgui-desktop` | the real shell: windows, taskbar, the editor, the Frame Profiler. `-Dcrystalgui.harness.desktop.profiler=true` drives the profiler window itself; `-Dcrystalgui.harness.desktop.traceCost=true` measures the engine's own cost with channels off and on; `-Dcrystalgui.harness.desktop.graphCost=true` measures the scratch shader graph: the desktop without it, open and idle, six constant edits, and closed again, in one process (`ShaderGraphCostProbe`, writes `graph-cost.txt`); `-Dcrystalgui.harness.desktop.hoverSweep=true` parks the pointer for 600 frames, then sweeps it across the editor window for 600 and prints work median and p95 per block plus the per-zone comparison (`HoverSweepProbe`, `[hover-sweep]` lines) |
| `cgui-gallery` | one widget, a page each |
| `cgui-text-stress` | text shaping and layout under load |
| `cgui-timeline` | the profiler's own tracks under 10,000 spans |
| `cgui-visual-layers` | layers, opacity, masks |

A workload the scenes do not reproduce belongs in a scene (`gl-debug-harness/AGENTS.md`) — a profile of a
hand-driven session cannot be repeated, so it cannot be compared.

A desktop scene gives the trace a run directory too: `gl-debug-harness/crystalgui/cache/trace/latest/`
(`trace.log`, and `meta.json` and `report.txt` on exit).

### The game

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:runClient \
    -Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,crystalgraphics,gpu \
    -Dcrystalgraphics.trace.firstFrames=600 \
    -Dcrystalgraphics.trace.zones=4194304
```

- **Size the zones** here by hand — only the harness's profile mode does it for you. With
  `crystalgraphics` on, a frame writes ~5,000 zones a thread, so the default 65,536 holds a dozen frames;
  frames × 5,000, rounded up, holds them all (an arena grows as it is used, 24 bytes a zone). The report's
  `WARNING … frames hold no zones` says when it was not enough.
- Every dev run forwards `-Dcrystalgui.*` and `-Dcrystalgraphics.*`; an installed client takes the same
  flags in its instance's JVM arguments.
- On exit the run directory holds the report: `<game>/crystalgui/cache/trace/latest/report.txt` (the
  breakdown, or the tier `-Dcrystalgraphics.trace.report` names), `meta.json` (what was and was not
  recording) and `trace.log` (a line per slow frame).
- A repeatable in-game workload is the autotest: `-Dcrystalgui.autotest=true` loads a world, opens the
  editor, photographs it and quits (`docs/CGUI_BUILD.md`). Profile that rather than a hand-driven session.
- `-Dcrystalgui.frameprofile=true` (1.7.10: `-PcgFrameProfile`) echoes each slow frame's line to the
  console too, and turns the frame channel on; `.floor=<ms>` (8) and `.every=<ms>` (1000) tune it.

### By hand — the Frame Profiler

For a human, or to look at a frame an agent's report named. **F9** or the taskbar's start button opens it
on any surface with a desktop; **F7** shows the frame readout (F8 expands it), and pressing a readout bar
opens that frame in the profiler.

- **Record** switches on the channels in its settings (`crystalgui.frame`, `crystalgui.flow`, `gpu`,
  `crystalgraphics.async`, `crystalgraphics.world` by default); the channel menu adds more live.
- **The gear**: ring size, frames kept from the start, *Record from launch*, *stop after a hitch*, frame
  images. Saved to `apps/crystalgui.frameprofiler/settings.json`.
- **Tabs**: Hints, Zones, Call tree (callers beside it), Counters, Chains (the `flow` spans), Compare,
  Screen (the frame's picture, with `images` on).
- **Export** writes the selected range, or every frame held, to `<cache>/trace-exports/` and copies the
  path — `ui.perfetto.dev` opens it, and Compare's **Load trace as B** reads it back, across restarts.
- The footer states what the viewer itself costs and hides its own work (`trace.viewer`) unless asked.

---

## Adding zones in CrystalGUI

- **Names**: zones `area:phase` (`paint:tree`, `layer:clear`, `ed:rebind`); counters kebab-case with the
  unit in the name (`layer-clear-kpx`, `retain-dynamic`); spans read as the action (`open:file`,
  `close.layout.closePanel`). Constants, always (`PROFILING.md` § *Conventions*).
- **Channel**: a phase inside a frame → `UiTrace.FRAME`; a chain → `UiTrace.FLOW`; worker work that no frame
  owns → a span on `FLOW`, or `crystalgraphics.async` when it is CrystalGraphics'.
- **Split begin/end** — the shape most of the frame uses:
  ```java
  long t = CgTrace.stamp(UiTrace.FRAME);      // 0 while the channel is off: no clock read
  layout(width, height);
  CgTrace.zoneDone(UiTrace.FRAME, "frame:layout", t);
  ```
- **A widget's paint** is zoned inside the widget (`paintContent`), with its own name, so every instance is
  covered; a per-instance name (`part:<name>`) only when instances differ in cost and there are few.
- **A new counter a hint should read** needs a rule in `UiHints` — or it is a number nobody is told about.
- A zone that runs once per element per frame (thousands) is a counter instead.

---

## Instrumented today

| Area | Channel | Where |
|---|---|---|
| The frame's phases | frame | `ui/dom/UIDocument`, `style/StyleEngine`, `ui/box/BoxTree`, `BoxPainter` |
| Paint, layers, backdrop, images | frame, gpu | `render/CgUiPaintContext`, `LayerPool`, `CgUiBackdrop` |
| SVG | frame | `render/texture/svg/SvgDocument`, `render/SvgRasterCache` |
| Jobs | frame | `core/async/JobScheduler` (`done:<job>`) |
| The editor | frame, flow | `widget/texteditor/TextEditor`, its `part/`, `fold/`, `lang/`, `doc/` |
| Text model | flow | `text/TextBuffer`, `text/wrap/ProjectedLines` |
| Markup, syntax | flow | `widget/text/MarkupView`, `SyntaxHighlighting` |
| Workbench actions | flow | `workbench/DocumentTabs`, `dock/DockArea`, `dock/WorkbenchOpener`, `search/GoToFile`, `chrome/palette/QuickPick`, `explorer/ExplorerCommands` |
| Language stack | frame, flow | `language/…/grammar`, `engine/AnalysedLanguageServices`, `java/*`, `js/JsLanguageServices` |
| Node graph, shader graph | frame | `canvas:cull`, `graph:tick`, `graph:paintNode`, `graph:paintWires`, `blackboard:tick`, `sg:previews`, `sg:mainPreview`, `sg:toShaderGraph`, `sg:compile`, `sg:paintPreview`; counters `graph-*`, `sg-*` -- `sg-preview-lost` is a preview that had a picture painting none, a blink on screen. `widget/canvas`, `widget/graph`, `app/shadergraph`; the renderers under them are CrystalGraphics' `shadergraph` channel |
| Hooks | frame | `anim-hooks`, `anim-after-layout-hooks`: live per-frame and post-layout hooks, a count that climbs while nothing happens being a leak (`UIDocument`) |

**Not instrumented** — zone these before any question that touches them:

- **input dispatch** (`ui/service/Input`) inside `frame:input` — hit testing, the three-phase walk, the
  keymap — and key events, which dispatch as the host delivers them rather than in the frame;
- **the desktop compositor** (`desktop/**`): window motion, the taskbar, snapshots, the switcher;
- **individual widgets' paint** outside the editor and SVG;
- **Taffy's compute** inside `frame:layout`, and `UIText` measurement;
- **the wire and the workspace** (`net/**`, `fs/**`) — spans where an action waits on them;
- **per-frame hooks and `afterLayout` hooks**, only as their phase totals and counts — a hook of its own is
  zoned by its owner.
