# CrystalGUI — profiling

What CrystalGUI adds to the trace engine: its channels, what a UI frame records, the Frame Profiler, and where to
run. **Read [`CrystalGraphics/docs/PROFILING.md`](../CrystalGraphics/docs/PROFILING.md) first**: the engine, the
one-run rule, the checklist, reading a report and the conventions are there. The invocable checklist is the
`profiling` skill.

## The rule, restated for a UI

One run answers the question. A slow paint is often a cascade two phases earlier, a widget's hook, a job's completion
or text measured in layout, so **map the frame before instrumenting it**, on [§ The frame](#the-frame), record every
channel the map names, and read the coverage (`unzoned`, `unexplained`, `GAP`, `SELF TIME`) before any number.

## Channels

| Channel | Records | Cost | On for |
|---|---|---|---|
| `crystalgui.frame` | the frame's phases and counters | two clock reads a zone | every timing question |
| `crystalgui.flow` | spans for chains across or outside frames: open a file, close a tab, an edit, a search | the same | "this action is slow" |
| `crystalgui.fs` | the workspace server's filesystem: `fs:stat`, `fs:reconcile.sweep`, `watch-rechecked`, `watch-sweep-busy` | cheap | a watcher hitch, or a sweep that stopped (`watch-sweep-busy` climbing) |
| `crystalgui.blame` | a stack walk per invalidation, as `invalidated-by` markers | **expensive** | who causes churn. **Never while timing** |

`CgTrace.enable("crystalgui")` takes them all. The default for a UI question:

```
-Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,crystalgraphics,gpu
```

## The frame

| Phase | Zones (`crystalgui.frame`) | Counters | Inside, possibly needing its own zone |
|---|---|---|---|
| Jobs | `frame:jobs`, and `done:<job>` per completion | `jobs-busy` | the completion callbacks |
| Hooks | `frame:hooks` | — | every per-frame `Animation` hook |
| Style | `style:drainDirtyMatch`, `style:prematch` (a large round on workers), `style:transitions`, `style:warmValues` (a sheet's values parsed on a worker) | `rematched`, `whole-window-invalidations`, `style-matched`, `style-shared`, `style-prematched`, `style-match-us`, `style-apply-us` | who invalidated: `crystalgui.blame` |
| Layout | `frame:layout` | — | Taffy and every `Measurable.measure`; CrystalGraphics' text zones show shaping |
| After layout | `frame:afterLayout` | — | every `afterLayout` hook |
| Input | `frame:input` | — | the hover diff and every listener the frame's pointer events reach |
| Hover | `frame:hover` | — | the re-style, layout and settle when the hover moved |
| Paint | `paint:tree`; `glend:build`; `svg.loadSource`, `svg.parse` | `drawcalls`, `culled`, `layers-opacity`/`-mask`/`-elided`, `masks-elided`, `clips-rounded`, `segments`, `segments-unchanged`/`-changed`/`-dynamic`, `segments-replayed`, `segments-kept`/`-unkept`, `segments-unkept-<reason>`, `replay-check-differs`, `surfaces-kept`/`-painted`/`-new`, `surface-miss-<reason>` | every box's paint, the canvas recording and the seal |
| Editor | `ed:*`, `ln:*`, `part:<name>` | — | zoned per phase; `ed:*` spans on `flow` for edits |
| GPU | `gpu:ui` (the whole paint, composite included) | — | — |

`UIDocument.frame` calls `UiTrace.frameBegin()` and the paint context's `endFrame` calls `UiTrace.frameEnd()`. A host
frame with no document framed (a world with no screen open) is framed by CrystalGraphics' `tickFrame`. **One document
per frame**: a second one framed in the same real frame splits every frame in two, halving the wall times silently.

### Blame

`UiTrace.blame("rematch", "com.crystalgui.style.")` attributes an occurrence to the first caller outside those
packages, capped and strided per frame, giving `invalidated-by Tooltip.reposition:214 x280`. Add one where a count
says how much churn and you need from where.

### Hints

Rules over counter names, run on every slow frame: `RETENTION-MISSED`, `CASCADE-CHURN`, `COLLECTED`, `FIRST-DRAW`,
`GPU-BOUND`, `BLOCKED-ON-WORKER` (`core.trace.UiHints`). **A hint reads a counter by name**: rename both together.

## Where it runs

### The harness — the default

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop" \
    -Dcrystalgraphics.harness.profile=300 \
    -Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,gpu
```

- **The channels property is required**: profile mode turns on only CrystalGraphics' channels.
- **`cgui-desktop` needs a long warm-up** (`-Dcrystalgraphics.harness.profile.warmup=600`): it generates glyphs and
  starts the language stack for seconds. Check the first profiled frames are not the slow ones.
- Read `gl-debug-harness/harness-output/<scene>/profile-harness-<n>f/report.txt`; the last run is beside it as `.prev`.
- `cgui-desktop`'s slow-by-wall frames include `scene:workspacePump`, the in-process workspace's tick, stalling about
  45 ms every few seconds after the cpu mark; the verdict names it.

| Scene | Profile it for |
|---|---|
| `cgui-desktop` | the real shell: windows, taskbar, the editor, the Frame Profiler |
| `cgui-gallery` | one widget, a page each |
| `cgui-text-stress` | text shaping and layout under load |
| `cgui-timeline` | the profiler's own tracks under 10,000 spans |
| `cgui-visual-layers` | layers, opacity, masks |

`cgui-desktop`'s probes, each a `-Dcrystalgui.harness.desktop.<name>=true`:

| Switch | Measures |
|---|---|
| `profiler` | drives the profiler window itself |
| `traceCost` | the engine's own cost, channels off and on |
| `graphCost` | the scratch shader graph: without it, open and idle, six constant edits, closed again (`ShaderGraphCostProbe`, `graph-cost.txt`) |
| `baseline` | the desktop as a user feels it (`DesktopBaselineProbe`, `baseline.txt`): the editor's first open, three applications, steady state, a theme switch every 30 frames, a 20,000-line file; the render thread's frame, each window's real rate, input to screen, and what ran during the longest freeze |
| `hoverSweep` | 600 frames parked, then 600 sweeping the editor; work median and p95 per block (`HoverSweepProbe`) |

A workload no scene reproduces belongs in a scene (`gl-debug-harness/AGENTS.md`): a hand-driven session cannot be
compared. Every scene gets a run directory, `gl-debug-harness/crystalgraphics/cache/trace/latest/`.

### The game

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:runClient \
    -Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,crystalgraphics,gpu \
    -Dcrystalgraphics.trace.firstFrames=600 \
    -Dcrystalgraphics.trace.zones=4194304
```

- **Size the zones by hand**: with `crystalgraphics` on a frame writes ~5,000 zones a thread, so allow frames ×
  5,000. `WARNING … frames hold no zones` says it was not enough.
- Every dev run forwards `-Dcrystalgui.*` and `-Dcrystalgraphics.*`; an installed client takes them as JVM arguments.
- On exit: `<game>/crystalgraphics/cache/trace/latest/report.txt`, `meta.json`, `trace.log`.
- A rare hitch: `-Dcrystalgraphics.trace.hitchDumpMs=<ms>` writes the 240 frames before each slower frame to
  `latest/hitch-<frame>/`.
- A repeatable workload is a gauntlet: `-Dcrystalgraphics.gauntlet=crystalgraphics:boot,crystalgui:desktop`
  (`CrystalGraphics/docs/GAUNTLET.md`).
- Two phases of one run: `-Dcrystalgraphics.trace.splitAt=<marker>` also writes `report-before.txt`,
  `report-after.txt` and `report-compare.txt` (`splitFrames`, 300, each at most).
- JFR: `-PcgJvmArgs="-XX:StartFlightRecording=filename=build/client.jfr,settings=profile"`.
- `-Dcrystalgui.frameprofile=true` (1.7.10: `-PcgFrameProfile`) echoes each slow frame to the console and turns the
  frame channel on; `.floor=<ms>` (8), `.every=<ms>` (1000).

### By hand — the Frame Profiler

**F9** or the start button opens it; **F7** shows the frame readout (F8 expands it), and pressing a bar opens that
frame.

- **Record** turns on the channels in its settings (`crystalgui.frame`, `crystalgui.flow`, `gpu`,
  `crystalgraphics.async`, `crystalgraphics.world`); the channel menu adds more live.
- **The gear**: ring size, frames kept from the start, record from launch, stop after a hitch, frame images; saved
  to `apps/crystalgui.frameprofiler/settings.json`.
- **Tabs**: Hints, Zones, Call tree, Counters, Chains, Compare, Screen, Buffers.
- **Lanes**: one per thread and one per sequence (`sequence:<name>` zones), since a document's turns land on any free
  UI pool thread.
- **Buffers** is live: every buffer a compute pass binds; pick one and its elements are read after that pass and
  decoded by the kernel's declaration (`CgBufferInspector`); **Follow** keeps reading.
- **Export** writes the range to `<cache>/trace-exports/` (opens in `ui.perfetto.dev`; Compare's **Load trace as B**
  reads it back).
- The footer states the viewer's own cost; its work (`trace.viewer`) is hidden unless asked.

## Adding zones in CrystalGUI

- **Names**: zones `area:phase` (`paint:tree`); counters kebab-case with the unit (`surface-miss-revision`); spans read
  as the action (`open:file`). Constants, always.
- **Channel**: a phase in a frame → `UiTrace.FRAME`; a chain → `UiTrace.FLOW`; worker work no frame owns → a span on
  `FLOW`, or `crystalgraphics.async` when it is CrystalGraphics'.
- **Split begin/end**:
  ```java
  long t = CgTrace.stamp(UiTrace.FRAME);      // 0 while the channel is off: no clock read
  layout(width, height);
  CgTrace.zoneDone(UiTrace.FRAME, "frame:layout", t);
  ```
- A widget's paint is zoned inside the widget; a per-instance name (`part:<name>`) only for few, differing instances.
- A new counter a hint should read needs a rule in `UiHints`.
- Thousands per frame is a counter, not a zone.

## Instrumented today

| Area | Channel | Where |
|---|---|---|
| The frame's phases | frame | `ui/dom/UIDocument`, `style/StyleEngine`, `ui/box/BoxTree`, `BoxPainter` |
| Paint, segments, surfaces | frame, gpu | `render/CgUiPaintContext`, `PaintSegments`, `Surface`, `ui/box/BoxPainter` |
| SVG | frame | CrystalGraphics' `CgSvg` (parse, `svg.tier`) |
| Jobs | `crystalgraphics.async` | `CgJob`: `job.then`, `job.task`; `frame:jobs` is the drain |
| The editor | frame, flow | `widget/texteditor/TextEditor`, its `part/`, `fold/`, `lang/`, `doc/` |
| Text model | flow | `text/TextBuffer`, `text/wrap/ProjectedLines` |
| Markup, syntax | flow | `widget/text/MarkupView`, `SyntaxHighlighting` |
| Workbench actions | flow | `workbench/DocumentTabs`, `dock/DockArea`, `dock/WorkbenchOpener`, `search/GoToFile`, `chrome/palette/QuickPick`, `explorer/ExplorerCommands` |
| Language stack | frame, flow | `language/…/grammar`, `engine/AnalysedLanguageServices`, `java/*`, `js/JsLanguageServices` |
| Node graph, shader graph | frame | `canvas:cull`, `graph:tick`, `graph:paintNode`, `graph:paintWires`, `blackboard:tick`, `sg:previews`, `sg:mainPreview`, `sg:toShaderGraph`, `sg:compile`, `sg:paintPreview`; counters `graph-*`, `sg-*` (`sg-preview-lost`: a preview that blinked) |
| Hooks | frame | `anim-hooks`, `anim-after-layout-hooks`: a count climbing while idle is a leak |
| The document's thread outside frames | flow | `app:launch`, `profiler:open`, `lang:analyzeNow` |
| The desktop's screen, render thread | flow | `screen:build`, `screen:shown`, `screen:tick`, `screen:paint` (`screen/CgUiScreen`) |

**Not instrumented**: zone these before a question that touches them.

- Input dispatch (`ui/service/Input`) inside `frame:input`, and key events, which dispatch as the host delivers them.
- The desktop compositor (`desktop/**`): window motion, the taskbar, snapshots, the switcher.
- The commit path (`CgUiViewport`, `CommitQueue`); `DesktopBaselineProbe` measures it from outside.
- Individual widgets' paint outside the editor and SVG.
- Taffy's compute inside `frame:layout`, and `UIText` measurement.
- The wire and the workspace (`net/**`, `fs/**`).
- Individual hooks: only phase totals and counts.
