# CrystalGUI's harness scenes

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs.

## Render testing — the GL debug harness

For anything visual, **prefer the harness over Minecraft**: it boots in seconds, needs no Minecraft
context, and gives you a real GL surface. What it cannot see is anything that crosses the loader seam.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"                   # start here
./gradlew :gl-debug-harness:runHarness --args="--list"                                # all scenes
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery --device=vulkan"   # on CrystalGraphics' Vulkan device
```

The harness is LWJGL 3 and GLFW; `--device=gl|tracked|vulkan` picks what `CgGL` runs on, `gl` by default —
`../gl-debug-harness/AGENTS.md` § *`--device`*.

| Mode | Scene class | Covers |
|---|---|---|
| `cgui-gallery` | `CgUiGalleryScene` | Every widget at once, a page each, with an Ore ⇄ default theme toggle — the default smoke test, and where a single widget is checked |
| `cgui-text` | `CgUiTextScene` | `UIText` wrapping/measurement |
| `cgui-text-stress` | `CgUiTextStressScene` | Many text nodes — shaping/layout cost |
| `cgui-styling` | `CgUiStylingScene` | Cascade, selectors, transitions |
| `cgui-visual-layers` | `CgUiVisualLayersScene` | FBO layer opacity + masking |
| `cgui-desktop` | `CgUiDesktopScene` | **CrystalOS** — stacking windows, drag, resize, clamp, cascade, taskbar, per-window modality, maximise, **the editor running as a window**, **a tool window torn out into an owned float** (F3, or drag a rail button into the editor area) **the frame readout** (F7, F8 to expand its phases) and **the Frame Profiler** — from the taskbar's **start button**, or F9 -- `profiler.open`, a command on every surface with a desktop, so the scene's key is the game's -- or a press on a bar of the F7 readout's sparkline, which opens that frame. **`-Dcrystalgui.harness.desktop.profiler=true` drives the profiler by itself**: opens it, records, then clicks the strip, a zone, wheels, pans, drags ranges (paused and live), steps with the arrow keys, presses Worst frame twice, toggles Record, ticks a channel, drags the split, opens the settings gear, sets 300 frames kept-first and waits for the ring to fill and stop, wheels the strip in and presses Home to reach frame #0, then Restore defaults, opens Hints and follows a link, pins two ranges and reads Compare, shows and hides the viewer's own work from the footer, opens Chains, presses a readout bar and checks the frame it opens, toggles the window with F9, presses Export and loads the file back as Compare's B, then ticks `images`, reads what the captures cost a frame, hovers the strip for a frame's picture and opens the Screen tab -- all through the real `Input` path -- printing what the model says each gesture did (`[profiler-shot]` lines) and writing `cgui-desktop-profiler-*.png` after each. ~15s, exits on its own. **`-Dcrystalgui.harness.desktop.traceCost=true`** instead measures what the trace engine costs a frame with every channel off (`TraceCostProbe`: blocks of 300 frames alternating off and on, `[trace-cost]` lines, ~40s). Its document records on its own sequence, presented by the render thread, like every harness scene's (`-Dcrystalgui.ui.async=false` on the render thread, `-Dcrystalgui.ui.sequence=true` in lockstep). **`-Dcrystalgui.harness.desktop.hoverSweep=true`** measures moving the pointer: parked, then swept across the editor every frame (`HoverSweepProbe`, `[hover-sweep]` lines). Run it after any change to the window; a gesture that stopped working shows as a wrong number, not a subtle picture **`-Dcrystalgui.harness.desktop.follow=true`** makes the document take 60 ms a frame and drags a window across it: `follow-mid` shows the window under the pointer, moved by the compositor (what the drag declared with `Drag.follows`, as every one-for-one mover does); `-Dcrystalgui.ui.compositorMotion=false` shows it lagging. **`-Dcrystalgui.harness.desktop.minimise=true`** minimises the active window and restores it, photographing each flight halfway (`minimise-mid`, `restore-mid`) and the minimised window's taskbar preview (`minimised-preview`): what flies, and what the preview draws, is the window's surface. `.minimise.window=<title>` picks the window, `.minimise.action=maximise` maximises and restores it instead. **`-Dcrystalgui.harness.desktop.presentAgain=true`** paints only even frames from 61 and re-presents the odd ones (`UiGpu.presentAgain`); `present-again` must match `presented`. **`-Dcrystalgui.harness.desktop.nodes=true`** (with `-Dcrystalgui.ui.async=false`) raises a window, then moves it and scrolls its content by writing the presented frame's property values and calling `UiGpu.redraw` (`nodes-redraw`), and records the same changes in the document (`nodes-recorded`): the two match but for the band the scroll revealed, which the first recording culled, and the scrollbar thumb, which is document state. For a picture wrong for one frame at a time: **`-Dcrystalgui.harness.desktop.everyFrame=A..B`** photographs every presented frame in that range, and **`-Dcrystalgui.harness.desktop.splitLoop=true`** drags the first split back and forth with real pointer events from presented frame 100 to 400, which makes the document commit as fast as it can. *Grows with `plan/shell-windowing.md`: every W with something visible adds its demonstration here in the same commit* |
| `gpu-trace-probe` | `CgGpuTraceProbeScene` | **DIAGNOSTIC, exits on its own** — T7's gate for `CgGpuTrace`: light frames painted without waiting, each fenced, where a `gpuNanos` must land within one boundary of the GPU finishing its frame; then frames bracketed by a spun fence wait, cycling heavy/light/empty, where heavy-minus-empty GPU time must be within 5% of heavy-minus-empty waited time. **Compare by difference**: a bracket costs a fixed 5-9 ms round trip on its own, which compared whole reads as a query missing a quarter of the work. Prints `[gpu-trace-probe]` lines ending PASS or FAIL, ~20 s |
| `cgui-timeline` | `CgUiTimelineScene` | **The profiler's navigation surfaces under load** — 10,000 nested spans on one shared axis, 600 frame bars, and two counter rows on the strip's own columns (one deliberate gap per row, since an unrecorded frame must not read as a measured zero). Wheel zooms about the pointer, drag pans, a click selects a span, a drag across the strip selects a range, ←/→ step frames, R refits, G reseeds. The status line prints THIS SCENE's own frame time, p50 and p99, so the gate is read off the screen it gates |

CrystalGUI's harness scenes live in `harness-scenes/src/main/java/com/crystalgui/harness/scene/`; register new
ones in `CrystalGuiHarness`. The harness itself is CrystalGraphics-only and reaches them as a `HarnessExtension`.
Harness authoring rules are in `../gl-debug-harness/AGENTS.md` — never call raw GL.

---

Also loaded with this folder:

@../gl-debug-harness/AGENTS.md
