---
name: profiling
description: Profile or measure anything in CrystalGraphics or CrystalGUI — a slow frame, a hitch, a slow action (opening a file, a search), startup, GPU cost, a before/after of a change — with the CgTrace engine, in the GL harness, a Minecraft dev run or an installed client. Use whenever the task is "why is X slow", "measure X", "profile X", "did this change make it faster", or adds trace zones, counters or spans.
---

# Profiling in one run

The failure this exists to prevent: instrument, run, find the time somewhere unzoned, add a zone, run
again, find a worker thread, run again. **Every one of those gaps is findable from the code before the
first run.** References: `CrystalGraphics/docs/PROFILING.md` (the engine, the rule, reading a report) and
`docs/CGUI_PROFILING.md` (CrystalGUI's channels, frame, window and scenes).

## 1. The question, written down

Which frames (steady state, first open, one hitch, a resize), which operation, and which number answers it:
CPU per frame, GPU per frame, a count per frame, an action's duration. A comparison needs both sides from
the same machine, back to back — or two ranges of one run.

## 2. Map the whole path — from code, before any zone

Read from the host's frame loop down to every leaf the question touches, and **write the list**:

- every phase on the frame thread — **follow every call site** of the suspect, not the one you found;
- every **thread**: worker pools, async glyph generation, uploads, parses on an executor, jobs whose
  completions land in `JobScheduler`;
- **first use vs steady state**: shader compiles, atlas growth, VAO/FBO creation, cache misses;
- the **GPU**: is it GPU-bound? Is there a `CgGpuTrace` zone around that work?
- the **count** behind every loop and cache — items, hits, misses;
- **one-off events** (resize, reload, a window opening) → markers;
- work that **crosses frames** (open, search, load) → spans.

For a UI frame, hang the path off the skeleton in `docs/CGUI_PROFILING.md` § *The frame* — jobs, hooks,
style, layout, after-layout, hover, paint — including each widget's own paint and hooks on the path.

Don't know where the time goes at all? **Sample first**: `-Pharness.jvmArgs="-XX:StartFlightRecording=duration=60s,filename=build/x.jfr"`
then `jfr view hot-methods build/x.jfr`, and map from the hot methods.

## 3. Check what exists

`PROFILING.md` / `CGUI_PROFILING.md` § *Instrumented today* and § *Not instrumented*, then
`grep -n 'CgTrace\.\|UiTrace\.' <files on the path>`. Note each zone's **channel** — a zone on a channel
you will not record does not exist for this run.

## 4. Instrument every gap in one pass

- Zones down to a leaf you could act on; in the **callee**, so every caller is covered.
- `CgTrace.add` for per-item counts (never a zone per glyph/quad/element); `CgTrace.counter` for readings.
- `CgGpuTrace.begin/end` around GPU work (GL thread; they do not nest).
- Spans (`spanBegin`/`spanEnd`) for chains; markers for events; `UiTrace.blame` where you need *who*.
- A sleep, fence or swap is a **wait**: name it with `CgTrace.waitName`, or it ranks as the top cost.
- **The host's loop too** — what runs between `frameEnd` and the next `frameBegin` is idle no zone covers.
- Names are constants: CrystalGraphics `subsystem.phase`, CrystalGUI `area:phase`, counters kebab-case
  with the unit. A new owner gets its own channel.
- A new counter a hint should read → a rule in `UiHints`. Renaming a counter → update the hint.

Show the path list and what you added (file, zone, channel) before running.

## 5. Set up the run so it cannot come back partial

- `-Dcrystalgraphics.trace.channels=<every channel the map touches>` — e.g.
  `crystalgui.frame,crystalgui.flow,crystalgraphics,gpu`. Never `crystalgui.blame` in a timing run.
- Ring: `-Dcrystalgraphics.trace.frames=<n>`, `.firstFrames=<n>` for startup/first-open questions;
  `.zones=<n>` if the last report warned of dropped zones; `CgTrace.stopAfterHitch` for a rare spike.
- A repeatable workload that ends by itself: a harness scene with
  `-Dcrystalgraphics.harness.profile=300` (it sizes the ring for you), or the game's autotest (size
  `-Dcrystalgraphics.trace.zones` yourself). Headroom questions: `-Dcrystalgraphics.harness.fps=0`.
- **Noise** (`PROFILING.md` § *Noise*): a warm-up long enough for the scene
  (`-Dcrystalgraphics.harness.profile.warmup=<n>` — the desktop needs hundreds); nothing heavy beside the
  run (other agents' builds, another harness); blame and images off while timing; plan to run twice.
- A scene that does not reproduce the workload → write one first (`gl-debug-harness/AGENTS.md`).

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=<scene>" \
    -Dcrystalgraphics.harness.profile=300 \
    -Dcrystalgraphics.trace.channels=crystalgui.frame,crystalgui.flow,crystalgraphics,gpu
# game: :runtime:mc:modern:<loader>:<version>:runClient with the same -D flags
```

## 6. Run once; read coverage before numbers

Read `harness-output/<scene>/profile-harness-<n>f/report.txt` (game:
`<game>/crystalgui/cache/trace/latest/report.txt`); `tree.txt` beside it has every thread's call tree.

1. **Header** — `NOT recording`, and any `WARNING` (zones dropped, frames holding no zones or no
   counters). Any one invalidates a conclusion that touches it: resize or enable, then run.
2. **Noise** — are the slowest frames the first profiled ones (warm-up too short)? A `GC` column on the
   worst frame? Was anything else running? Two runs' medians apart by more than the effect? Then the numbers
   are noise, and the report must say so. `unexplained` idle is machine noise only once the host's loop is
   zoned.
3. **Coverage** — each frame's `unzoned` cpu and `unexplained` idle with their **longest stretch** (the
   zones either side are where the missing zone goes), its `GAP` lines, the `SELF TIME` kinds. If any is
   as large as the effect you are chasing, the map was wrong: **fix every gap the report names,
   together**, then run again. Say so explicitly; do not draw a conclusion over an uncovered frame.
4. **The verdict** — over by cpu is work; over by wall alone is idle, and its last line names what filled
   it. Then the slowest by cpu, the slowest by wall, the typical frame, counters, hints. Absent is not zero.

## 7. Report

The question, the run (scene, flags, frames), the coverage (unzoned %, unexplained idle, remaining GAPs), the answer with
numbers and `File.java:line`, and — for a change — before/after from ranges of one run or back-to-back
runs, with the run-to-run spread. **Leave the zones in**; list what you added. A gap you found only at
runtime goes into § *Not instrumented* or § *Instrumented today* of the right doc, in the same change.
