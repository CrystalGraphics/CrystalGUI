# CrystalGUI — Agent Knowledge Base

**Project type**: Platform-agnostic retained-mode UI engine, shaped like a lightweight web browser
(DOM + CSS cascade + Taffy layout + immediate-mode painting).
**Authored in**: Java 25, with a Java 8 copy of every engine module for consumers below it · **Layout**: Taffy · **Backend**: CrystalGraphics
**Runs**: in any application that hosts it on CrystalGraphics (the GL debug harness is one), and also inside Minecraft: one jar for Forge 1.7.10–26.3, NeoForge 1.20.2–26.3 and Fabric 1.14.4–26.3, plus an optional language jar — see [Build and run](#build-and-run)

---

# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

## Required reading

Read the files relevant to your task scope **before** doing any work.

**Guides load themselves.** A folder with a `CLAUDE.md` puts its guide into your context the first time you Read a
file anywhere under it: `ui/dom`, `ui/box`, `ui/service`, `style`, `render`, `widget`, `net`, `lifecycle`,
`text/cursor`, `core/cursor`, `core/cache`, `runtime/mc`, `harness-scenes`, `download`, and every CrystalGraphics package. Nothing under `resources/` has
one, since all of it ships: before touching an asset or a shader, read the doc in the table below. **So Read a file before you change it, and never change one through the
shell that you have not Read**: a shell edit loads nothing, and the guide holds the rules you would then break.

| When | Read |
|---|---|
| Always | `docs/CGUI_INVARIANTS.md` — **the section for whatever you are about to touch**, not the whole file |
| Touching style, CSS, painting, drawables, compositing | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Writing or modifying a widget | `docs/CGUI_WIDGETS.md` |
| Touching `serialization/` or `net/` | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Touching `dock/`, `workbench/` or `editor/` | `docs/CGUI_WORKBENCH_SERVICES.md` — **and add any new service API to it in the same commit** |
| Touching `fs/`, `document/` or `workbench/editor/` | `docs/CGUI_WORKBENCH_SERVICES.md` §Resources and §Opening things — the same rule applies |
| Touching any rendering/buffer/shader/VAO/mesh code | `CrystalGraphics/AGENTS.md` — it loads itself with any CrystalGraphics file and any file under CrystalGUI's `render/` or `lifecycle/`; read it by hand for rendering work anywhere else |
| Working inside a package | its guide, the folder's `CLAUDE.md`, which loads itself (above). A package's guide is always `CLAUDE.md`; only a repository root keeps `AGENTS.md` |
| Touching anything in `core/src/main/resources/assets/crystalgui/` — a shader, a sheet, an icon, a font | [`docs/CGUI_SHIPPED_ASSETS.md`](docs/CGUI_SHIPPED_ASSETS.md), and for a shader `CrystalGraphics/docs/SHADERS.md` |
| Finding where something lives | [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) — every module and the package map of `core/` |

## NO RE-DELEGATION

**Subagents MUST NOT delegate their assigned work to another agent.**

When you are assigned a task — by an orchestrator, a plan, or a user — you execute it yourself with
the tools you have (Read, Edit, Write, Bash, Glob, Grep). You do not spawn a child agent, fire a
background task, or hand off via `task()`.

**Absolute prohibition. No exceptions.** Complexity is not a reason to re-delegate. The only
cross-agent tool use permitted is asking the orchestrator a clarifying question, inline.

**If you are a subagent and find yourself writing a `task()` call: STOP. Do the work yourself.**

---

# What this was built for

CrystalGUI was started for one product: a **node-based shader graph for Minecraft**, cross-version,
true to GLSL, on a modern GL 3.x+ pipeline with instancing as the default draw path — Unity's Shader
Graph without the lies about what the GPU is doing.

**That shipped.** The graph is `com.crystalgui.app.shadergraph`, it opens as a `DocumentKind`, and the
engine under it is general enough that it is now one application among several rather than the reason
for the rest. What the goal leaves behind is the standard: every widget here was built to survive a
node editor, which is why the box tree lays out once, why `transform` never reflows, and why a canvas
can hold ten thousand nodes.

The rendering principles everything here stands on are CrystalGraphics' `AGENTS.md` § *Project philosophy*.

# Build and run

CrystalGUI is an engine (`core/`, `language/`, `taffy/`) plus a host per Minecraft era under
`runtime/mc/`, shipped as two jars. **CrystalGraphics is the parent and owns the shared build
mechanism** — the node tree, the toolchains, stub mode and the merge — so its docs come into most build
questions too.

| Read | When |
|---|---|
| **[`docs/CGUI_BUILD.md`](docs/CGUI_BUILD.md)** | **First, for anything about the build**: layout, commands and flags, what each check can see, releasing, and adding a Minecraft version |
| [`CrystalGraphics/docs/BUILD.md`](CrystalGraphics/docs/BUILD.md) | The node tree, the toolchain per node, the pin catalog, stub mode — and step one of adding a Minecraft version |
| [`docs/CGUI_CROSS_VERSION.md`](docs/CGUI_CROSS_VERSION.md) · `/cross-version` skill | Code or a platform service that must run on every version |
| [`docs/CGUI_PROFILING.md`](docs/CGUI_PROFILING.md) · `/profiling` skill | Measuring anything — a slow frame, a hitch, an action, a before/after. After [`CrystalGraphics/docs/PROFILING.md`](CrystalGraphics/docs/PROFILING.md) |
| **[`CrystalGraphics/docs/MINECRAFT_RENDERING_CONVENTIONS.md`](CrystalGraphics/docs/MINECRAFT_RENDERING_CONVENTIONS.md)** | **Anything drawn into Minecraft's frame, and every new Minecraft version**: how each version changed that frame — 26.2's reversed depth and float depth, the samplers and scissor Minecraft leaves bound — and what the engine does about each |
| [`CrystalGraphics/singlejar-logic/README.md`](CrystalGraphics/singlejar-logic/README.md) | How one jar serves every loader. Before touching `singlejar-logic/`, relocation, remapping or the class-major ceiling |
| [`CrystalGraphics/singlejar-logic/STUBS.md`](CrystalGraphics/singlejar-logic/STUBS.md) | Before adding a node, changing its pins, or touching a branch script's toolchain |
| [`runtime/mc/modern/README.md`](runtime/mc/modern/README.md) | Before touching a modern node; each branch has its own `AGENTS.md` |
| [`download/README.md`](download/README.md) | Every address the runtime downloads from, and pinning a new version's script names |
| [`docs/CGUI_SETUP.md`](docs/CGUI_SETUP.md) | Setting up a *consumer* mod on CrystalGUI |
| `gl-debug-harness/AGENTS.md` | Writing a harness scene |

## The engine

```bash
./gradlew :taffy:test                            # the vendored layout engine's own regression tests
./gradlew :core:compileJava                      # enforces the Minecraft/Forge/LWJGL import guard
./gradlew :core:test --tests "<Class>"           # CrystalGraphics ON the classpath; name classes --
                                                 # a `com.crystalgui.ui.*` wildcard never reports
./gradlew :core:headlessTest                     # server-side tests: no GL context, no fonts
./gradlew :core:trackedTest                      # every shipped shader and keyword variant, linked as on Vulkan
./gradlew :runtime:mc:1710:compileJava           # not in :core:check -- what a deletion from core/ breaks silently
```

## Every version, and the shipped jars

```bash
./gradlew checkAllTargets                                       # every node compiles -- before every commit
./gradlew singleJar languageJar checkSingleJar checkLanguageJar # build/libs/
./gradlew deploySingleJars                                      # both, plus CrystalGraphics', into every Prism instance
```

| Jar | Carries |
|---|---|
| `crystalgui-<version>.jar` (~13 MB) | the engine, the workbench and every loader host. **Requires `crystalgraphics-<version>.jar` beside it** |
| `crystalgui-language-<version>.jar` (~50 MB) | the optional `crystalgui_language` mod: tree-sitter grammars and natives, ECJ and Rhino per Java band, `language/`. The host jar never names it |

## Running Minecraft

A loader module is the only thing that sees what crosses the loader seam — networking, the workspace
over a wire, platform services, class loading on a server. `headlessTest` reaches no loader and the
harness is a client by design.

```bash
./gradlew :runtime:mc:modern:<branch>:<version>:serverSmoke -PcgAcceptEula   # boot a server, assert, stop
./gradlew :runtime:mc:1710:serverSmoke
./gradlew :runtime:mc:modern:<branch>:<version>:runClient                    # a dev client
./gradlew :runtime:mc:1710:runClient -PcgProbe -PcgJoin=localhost:25565      # the connection probe, two processes
./gradlew :runtime:mc:modern:<branch>:<version>:connectionProbe              # the same, driven to a verdict file
./gradlew prodSmoke                                                          # THE SWEEP: the shipped jars on 30 real clients
./gradlew prodSmoke -PcgTargets=<label>,<label>                             # just these
```

Every flag, which nodes have a dev run, and how to read a run: `docs/CGUI_BUILD.md` § *Commands* and
§ *Verification*.

## Rules the build will not tell you

1. **CrystalGraphics first.** A version, a node or a new platform capability is added there, then here.
2. **Nodes compile from `CrystalGraphics/singlejar-logic/stubs.zip` by default.** Code changes never touch
   it; a node added or re-pinned means regenerating it. A run task makes its node real.
3. **Abstract modules are Java 25** (`core`, `language`, `taffy`, and CrystalGraphics' `core`, `platform`,
   `runtime/lwjgl/*`), each with a Java 8 copy that consumers below 25 resolve. Never lower one to suit a
   consumer — and javac does not check the API: a Java 9+ call fails on a Java 8 instance unless jvmdg
   stubs it.
4. **The host jar may not name the language stack**, and a source set enforces it: `:language` is on each
   host's `lang` source set only. Where the host needs the other jar, it publishes a seam it cannot name
   (`CgUiAutoTest.onFrame`).
5. **`serverSmoke` first** for anything that is a runtime property — a client-only class constructed on a
   server, a service built eagerly. It also asserts no client-only class was *loaded*.
6. **Only `prodSmoke` sees packaging** — relocation, remapping, downgrading, merged descriptors — since a
   dev run reads source-set directories. **Read every new target's capture**: a capture is not a paint,
   and `desktop painted: false` fails the run.
7. **A failure on an installed client is reproduced in that node's dev run**, never by redeploying through
   `prodSmoke` (ten minutes a cycle against two). `prodSmoke` confirms once, at the end.
8. **A wide check is the sweep** — one client per Minecraft major, four at a time, oldest first — never
   every instance (`-PcgTargets=all`, over a hundred clients).
9. **A probe that never ran is not a pass.** The driven tasks delete their verdict file first and require
   it after; its first line is the verdict.
10. **Switching the active Stonecutter node rewrites `src/` in place.** Switch back before committing.

## Supported versions

A node per (loader, Minecraft version); a node claims the versions it was booted on (`variant.minecraft`
in the pin catalog).

| Loader | Versions | Not supported, and why |
|---|---|---|
| Forge | 1.7.10 · 1.8.8–1.12.2 (legacy tree) · 1.13.2–1.21.11 · 26.1.1–26.3 | 1.8 (no MixinBooter boots it) · 1.21 (Forge 51 has no HUD event) · 26.1 (Forge 62 fails in Minecraft's own bootstrap, before any mod loads) · never published: 1.14, 1.14.1, 1.16, 1.17, 1.20.5, 1.21.2 |
| NeoForge | 1.20.2–1.21.11 · 26.1–26.3 | nothing for 1.20.1; 1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1, 26.1.1 and 26.3 run its only builds, betas |
| Fabric | 1.14.4–1.21.11 · 26.1–26.3 | 1.14–1.14.3, 1.16, 1.16.1, 1.21.9 — their only Fabric APIs lack a module the hosts use |

- **Java 8** runs Forge 1.13–1.16, legacy Forge and 1.7.10, dev runs included (`uniminedDevRun` swaps in
  the Java 8 copies). Below 1.17 the nodes are built by Loom and Unimined, above by ModDevGradle.
- **26.x is Java 25 and unobfuscated**: every loader runs Mojang's names, so a Fabric node from 26.1 ships
  as compiled, with no intermediary. On 26.2 Blaze3D may run on Vulkan, and CrystalGraphics then draws
  through its own Vulkan device hosted on Minecraft's (`Blaze3dVulkanHost`); a dev client picks the API
  with `-PcgGraphics=vulkan|opengl`. 26.3 under Vulkan still stands down (`CgGraphicsLifecycle.standDown`).
- **26.3 windows through SDL3, and ships no GLFW**: its keys are SDL scancodes and its mouse buttons SDL's.
  A 26.3 node registers CrystalGraphics' `runtime/lwjgl/sdl` services instead of the GLFW ones, a host
  names a key through `CgUiInput.hostKey`, and Fabric's input chain is SDL's event filter. Blaze3D's GPU
  layer moved to `com.mojang.renderpearl`, a `replacements.string` in both Stonecutter scripts.
- **Below 1.19.3 Minecraft ships no JOML**, and those instances take CrystalGraphics' `crystalgraphics-joml`
  companion (`prismInstanceJoml` in `local.properties`).
- **Forge 1.13.2 and 1.14.2–1.14.3 compile against Mojang names carried back from 1.14.4**, since Mojang
  published none; their scripts resolve through MCP's.
- The per-loader node lists: `runtime/mc/modern/{forge,neoforge,fabric}/AGENTS.md`.

---

# Render testing — the GL debug harness

For anything visual, **prefer the harness over Minecraft**: it boots in seconds, needs no Minecraft context, and
gives you a real GL surface. What it cannot see is anything that crosses the loader seam.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"                   # start here
./gradlew :gl-debug-harness:runHarness --args="--list"                                # all scenes
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery --device=vulkan"   # on CrystalGraphics' Vulkan device
```

The harness is LWJGL 3 and GLFW; `--device=gl|tracked|vulkan` picks what `CgGL` runs on, `gl` by default —
`gl-debug-harness/AGENTS.md` § *`--device`*.

| Mode | Covers |
|---|---|
| `cgui-gallery` | Every widget, a page each, with an Ore ⇄ default theme toggle — the default smoke test |
| `cgui-desktop` | CrystalOS: windows, taskbar, the editor as a window, the frame readout (F7) and the Frame Profiler (F9), and a dozen scripted `-Dcrystalgui.harness.desktop.*` runs |
| `cgui-text`, `cgui-text-stress` | `UIText` wrapping and measurement; shaping and layout cost |
| `cgui-styling` | Cascade, selectors, transitions |
| `cgui-visual-layers` | FBO layer opacity and masking |
| `cgui-timeline` | The profiler's navigation surfaces under load |
| `gpu-trace-probe` | Diagnostic for `CgGpuTrace`; exits on its own |

**Every scene in full, with each one's switches: [`harness-scenes/CLAUDE.md`](harness-scenes/CLAUDE.md)**, which
loads itself when you open a scene. Scenes live in `harness-scenes/src/main/java/com/crystalgui/harness/scene/` and
are registered in `CrystalGuiHarness`; the harness itself is CrystalGraphics-only and reaches them as a
`HarnessExtension`. Authoring rules are `gl-debug-harness/AGENTS.md` — never call raw GL.

---

# Start Here By Task

Guides below are relative to `core/src/main/java/com/crystalgui/` and load themselves when you open a file in their folder.

| I need to… | Read |
|---|---|
| Not repeat something already paid for | `docs/CGUI_INVARIANTS.md` |
| Add or change a widget | `widget/CLAUDE.md` · `docs/CGUI_WIDGETS.md` |
| Add a panel, a file type or a command to a workbench | `docs/CGUI_WORKBENCH_EXTENSIONS.md` |
| Add a CSS property | `style/CLAUDE.md` § *Adding a CSS property* · `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Change how something paints | `render/CLAUDE.md` · `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8 |
| Work on layout / Taffy | `ui/box/CLAUDE.md`, and `style/CLAUDE.md` § *`BoxStyle`* — the defaults diverge from CSS |
| Work on events, focus, hover, drag | `ui/service/CLAUDE.md` |
| Serialize a tree / send UI over a wire | `net/CLAUDE.md` · `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Understand a frame | [Frame lifecycle](#frame-lifecycle) |
| Debug "my selector doesn't match" or "my layout is wrong by default" | [Load-bearing invariants](#load-bearing-invariants) |
| Work on a Minecraft host | `runtime/mc/CLAUDE.md` — loader modules are wiring, never logic |
| Ship an asset, a shader or a theme | `docs/CGUI_SHIPPED_ASSETS.md` · `CrystalGraphics/docs/SHADERS.md` |
| Add or move a runtime download | `download/CLAUDE.md` |
| Find where something lives | `docs/CGUI_ARCHITECTURE.md` |
| Add a rendering backend capability | [CrystalGraphics boundary](#crystalgraphics-ownership-boundary) · `CrystalGraphics/AGENTS.md` |

---

# Module layout — what actually compiles

`settings.gradle.kts` includes the engine modules, the loader trees and the harness. `taffy`, `gl-debug-harness` and
`CrystalGraphics` are git submodules (clone with `--recursive`); CrystalGraphics is a composite `includeBuild`
resolved to local source. Each row in full: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md).

| Module | What it is |
|---|---|
| `core/` | The engine. Java 25, an abstract module with a Java 8 copy |
| `language/` | The language stack: grammars, engines, Run. Ships as its own mod, `crystalgui_language`; `core/` and the host jar never name it |
| `taffy/` | The layout engine, vendored and forked (`taffy/MODIFICATIONS.md`) |
| `gl-debug-harness/`, `harness-scenes/` | The harness (CrystalGraphics-only) and CrystalGUI's scenes for it |
| `CrystalGraphics/` | The rendering backend. Consumed, never reimplemented |
| `runtime/mc/1710/`, `legacy/`, `modern/` | The hosts: Forge 1.7.10; Forge 1.8–1.12.2; Forge 1.13.2+, NeoForge 1.20.2+ and Fabric 1.14.4+ |
| `runtime/mc/shared/`, `launchwrapper/`, `modern-shared/`, `forge-bootstrap/` | What several hosts share, merged once |

`core/build.gradle.kts` runs an **import guard** on `compileJava`: any import of `net.minecraft.*`, `cpw.mods.fml.*`,
`net.minecraftforge.*` or `org.lwjgl.*` fails the build. There are no exemptions.

## Four test source sets, and they are not interchangeable

| Source set | CrystalGraphics on classpath? | What belongs there |
|---|---|---|
| `core/src/test/` | ✅ `testImplementation` | Anything needing `CgIO`, fonts, `StyleSheet`, sprites, drawables |
| `core/src/headlessTest/` | ✅ `core` and `platform`, **no GL context and no fonts** | Everything a dedicated server must run: `serialization/`, `net/`, tree/state logic, and **`text.lang` — the language SPIs, which run here precisely because no engine and no grammar is on this classpath** |
| `core/src/trackedTest/` | ✅ `core`, `platform` and `vulkan`, with LWJGL 3 | CrystalGUI's shaders on CrystalGraphics' tracked backend over shaderc: every `.shader`, pass and keyword variant, linked as a Vulkan device will link them. Its own task, `:core:trackedTest` — the backend it installs is process-wide |
| `language/src/test/` | ✅ (plus the tree-sitter natives) | Grammars, queries, the tokenizer. Skips cleanly when a native will not load on the running platform |
| harness scenes | ✅ full GL | Anything visual |

**The classpath is a dedicated server's.** A server ships CrystalGraphics `core` and `platform`, so both are here:
`core` holds shared utilities the engine names in field and signature types (`com.crystalgraphics.easing`, which the
style engine's transitions and the `Animation` service use). What a server lacks is a GL context and fonts, and no
test here has either, so code that reaches the GPU outside a paint-method body fails here rather than in production.
`HeadlessClasspathSanityTest` pins what must be present. *(Until 2026-10-02 `core` was excluded, and the guard was a
`NoClassDefFoundError` on any core type; moving easing into core ended that.)*

JOML and Taffy **must stay** on the headless classpath: `UINode` and `ElementStyle` have *fields*
of those types (`Matrix4f`, `NodeId`, `TaffyStyle`), and field descriptors resolve at class load —
unlike method-body references, which don't. Someone will eventually try to strip them; don't.

> **`StyleSheet` loads headlessly now.** `StyleSheet.DEFAULT` reads `default.css` through `CgIO` at class-init,
> which made the whole class unloadable while `core` was off this classpath; `HeadlessClasspathSanityTest` now
> asserts it loads.

---

# Frame lifecycle

```
UIDocument.frame(delta, w, h):
  JobScheduler.drain()          // answers from off-thread work land HERE, on the frame thread
  input().beginFrame()          // invalidate the hover cache -- never READ it
  animation().tick(delta)       // timelines and per-frame hooks, on the DELTA the host passes
  calculateStyle(delta)         // drain dirty-match parents-first, cascade, tick transitions
  layout(w, h)                  // sync the box tree, compute ONCE, read boxes, compose matrices
  settleAfterLayout(...)        // afterLayout hooks -- may move a box, may not add one
  input().endFrame()            // hover diff + dispatch of the frame's accumulated mouse events
  (style + layout + settle)     // only when that diff moved the hover, so :hover lands this frame --
                                // and it SETTLES, because an enter dispatched above is what shows a
                                // tooltip, and this is that tip's first layout
```

**Animation before style before layout is load-bearing**, and it is why an ordinary per-frame hook
cannot read geometry: at the moment it runs, this frame's layout has not happened. Anything positioned
FROM a measured box uses `Animation.afterLayout` instead.

**`update(w, h)`** runs style then layout — for a geometry assertion that should not need a frame.
**`layout(w, h)` is the BOX TREE ALONE and runs no cascade**, so a test that reaches for the obvious
name asserts against a tree no stylesheet has touched: every rule appears not to match, which reads as
a broken selector rather than as a skipped pass. It cost a session in RPG-Core's first layout test.

**`beginFrame()` only INVALIDATES the hover cache; it must never read it.** A mouse-move already
invalidated it before `beginFrame` ran, so reading there is an eager recompute against the NEW position
mislabelled as the old one. That was the original stuck-hover bug; the baseline is a plain field
snapshotted at the end of the dispatch.

**Settling is bounded** (`MAX_SETTLE_PASSES`), which is the whole difference from the old engine's
`while (isLayoutDirty())`: a post-layout pass that keeps dirtying layout terminates instead of
converging by luck.

---

# Load-bearing invariants

**Moved out: [`docs/CGUI_INVARIANTS.md`](docs/CGUI_INVARIANTS.md).** 298 rows, grouped by subsystem —
threading, coordinates, the cascade, dispatch, GL, widgets, the workbench, the wire, the editor, the
language stack, the build. It was a 560-line section here, a quarter of a file that is read at the start
of every session, and most of it was one bug in one class rather than a rule. **Read it when you are
about to touch one of those areas; do not read it front to back.**

Eight that bite most often, and each is one line because the full row is in that file:

| | |
|---|---|
| `box()` is **nullable** | A node that is hidden, frozen, `display: none` or not in a document has no box at all |
| `Box.x()` is **parent-relative** | `a.x() - b.x()` means nothing unless they share a parent. Use `Box.centreIn`/`originIn` |
| `toLocal` puts the box's **own origin at zero** | So a caller wanting an absolute coordinate adds `box().x()` deliberately |
| The **frame thread owns the tree**, per tree | Anything touching a node runs there; anything that is a pure function of a snapshot must not |
| `flex-shrink` defaults to **0** here | A `flex-grow: 1` child overflows its parent rather than shrinking. The fill idiom is `width: 100%; height: 0; flex-grow: 1` |
| `font-size` does **not** effectively inherit | `default.css` opens with `* { font-size: 10 }`, which is a candidate on every element |
| A listener on a shadow host can **never see its own parts** | `getTarget()` is retargeted before it runs. Attach inside the shadow tree |
| A subscription held by hand dies on the first detach | `disconnected()` drops what a node holds and nothing remakes it. Use `whileConnected` |
| What GL permits is **not** what the host tolerates | Blaze3D models twelve texture units; binding above it corrupts unit 0 for whoever samples it next |

# Global coding rules

## Port, don't reinvent

**Anything that has been solved thousands of times — text editing, cursor movement, click and drag
selection, undo coalescing, layout — is ported from a battle-tested source and fine-tuned for this
codebase. It is not derived from first principles.**

These behaviours are *conventions, not derivable answers*. Each is one line, each is invisible when
wrong, and each was learned by shipping to millions of users. Four from `text/cursor/` alone:

| Rule | What happens without it |
|---|---|
| Auto-close fires on an **allowlist** (`;:.,=}])> \n\t`), never a denylist | "suppress before a letter" still opens a pair before `$foo` and `#define` |
| A plain arrow collapses a selection to its **edge**, regardless of which way the gesture went | Left-then-right on a backwards selection walks the caret instead of collapsing |
| A partly-commented block **comments out**, it does not half-toggle | Selecting a block with one commented line inverts half of it |
| A backwards word-drag **unions with the anchor word** | Word-granularity drag eats into the word it started on and stops feeling like words |

### Licences are load-bearing here

| Source | Licence | What you may do |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file. |
| **Chromium** | **BSD-3-Clause** | **Port the code.** Attribute in the class javadoc and in `THIRD-PARTY.md`. `RateEstimator` is one. |
| **Zed**, **wget** | **GPL** | **Read for shape only.** Copying would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; the progress channel takes wget's *refresh the ETA about once a second" and not a line of its code. |

### Port the module boundaries too

A port keeps its source's module boundaries as well as its algorithms: they are what keeps it testable. `text.cursor`'s mapping onto VS Code is its own guide, `core/src/main/java/com/crystalgui/text/cursor/CLAUDE.md`.

## CrystalGraphics ownership boundary

**CrystalGraphics owns the rendering backend. CrystalGUI consumes it.**

- CrystalGUI may define renderer-facing abstractions and UI draw orchestration.
- Fonts, shaders, framebuffers, VAO/VBO, draw submission, GPU resource ownership, and modern GL
  pipeline capability belong in **CrystalGraphics**.
- Never write raw GL, raw `float[]` vertex packing, or a hand-rolled buffer in `core/`.

Because CrystalGraphics lives in this repo and is directly writable, if CrystalGUI needs a new backend
capability we **add it to CrystalGraphics** and integrate against the new API — we do not reimplement
the backend here.

> The full infrastructure ownership map (`CgStreamBuffer`, `CgStagingBuffer`, `CgVertexWriter`,
> `CgBufferWriter`, `CgShaderBuffer`, `CgMesh`,
> `CgShaderProgram`, and the decision tree for picking between them) lives in
> **`CrystalGraphics/AGENTS.md`**, which loads itself with any CrystalGraphics file and with any file under `render/`. Read it there;
> it is not duplicated here.

## Platform-agnostic core

`core/` must stay fully cross-platform — the import guard enforces no `net.minecraft.*`,
`cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`.

**The platform seam is CrystalGraphics'.** CrystalGUI has no registry of its own — it reads everything
through `CgPlatform`, which has two halves: a loader registers exactly one `CgPlatformService` bundle
(**closed** — nine methods, no defaults, so the compiler forces a new loader to answer every one), and
fills any number of `CgService` **slots** (**open** — for contracts the rendering framework must not name,
each carrying its own absent-value):

| Need | Reached via | Lives in |
|---|---|---|
| Modifier, key and button state, the clipboard, UI sounds, the cursor — **from UI code** | `PlatformPort.current()` | `ui/service/PlatformPort`: the running document's port, which a document on its own thread routes to the render thread (plan engine-threaded-ui). Calling the services below directly from a widget bypasses that |
| Key/mouse codes, modifier state, **and the clipboard** | `CgPlatform.input()` — hosts and `PlatformPort.INLINE` only | `platform/service/CgInputService` |
| UI sounds | `CgPlatform.sound()` — hosts and `PlatformPort.INLINE` only | `platform/service/CgSoundService` |
| Raw event sink (`Input` implements it) | — | `platform/input/CgSystemInput` |
| Code constants | — | `platform/input/CgKeyCodes`, `CgMouseCodes`, `CgModifiers` |
| **Presenting a cursor** | `CursorService.setCursor(...)` | **`core.cursor`, ours** — its guide, `core/src/main/java/com/crystalgui/core/cursor/CLAUDE.md`, has why the cursor is split |

> **The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
> is reached the same way and needed by exactly the code that handles keys — two methods do not earn a
> registration slot. Both are abstract, like everything in the bundle.

**No method in the BUNDLE has a default, and `CgSoundService` ships no `NOOP` constant.** A default is
an answer chosen for someone who never saw the question: a new platform compiles cleanly while silently
inheriting "no sound, no clipboard", and inheriting a no-op is indistinguishable from deciding on one.
Abstract methods make the compiler the reminder — and a platform with nothing to offer still says so, with
an empty body in its own source.

**A `CgService` slot is the deliberate opposite**, and the cursor is why the distinction exists: an
unpresented cursor is *cosmetic*, and the engine runs where there is nothing to present to — a dedicated
server, a headless test, a fixture with no window. Those must not register a stub to stay silent, so the
slot answers `CursorService.NONE` and `CgService` logs the absence once, on first read.

> **Why this stopped being CrystalGUI's own registry.** `CrystalGuiCore` used to hold four static fields
> with setters. CrystalGraphics is the parent project and is always present, so two registries meant a
> loader had to find both — and could wire up one, leaving a UI with a working GL backend and no keyboard.
> One bundle makes a platform either registered or not. `CrystalGuiCore` now holds only `LOGGER`.

## Lombok

Prioritize Lombok to eliminate handwritten accessor boilerplate. It generates Java 8-compatible
bytecode and is `compileOnly` — no runtime dependency.

| Annotation | Use when |
|---|---|
| `@Data` | Simple POJOs, all fields in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — apply at field level when only some fields need accessors |
| `@RequiredArgsConstructor` | Immutable classes — pairs with `@Getter` only |
| `@Builder` | 4+ constructor parameters, or many optional ones |
| `@Value` | Fully immutable data carriers |
| `@ToString` / `@EqualsAndHashCode` | When you need one without full `@Data` |
| `@Accessors(chain = true)` | Fluent setters — used widely here |

Do **not** use `@Data` on classes with inheritance — use explicit annotations and always
`@EqualsAndHashCode(callSuper = true)` on subclasses.

---

# Package map

Every package of `core/` and what it owns: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *Package map*.
The top level:

```
com.crystalgui.core            utilities every layer names: data, dispose, property, signal, storage, cache,
                               command, cursor, trace, undo, window
com.crystalgui.ui              the engine: dom (nodes), box (layout), service (input, focus, animation),
                               contract, event, input, text, data
com.crystalgui.style           the cascade
com.crystalgui.render          painting: CgUiPaintContext records, UiGpu executes; drawables in .texture
com.crystalgui.widget          the widgets, layered (LayeringTest)
com.crystalgui.desktop         CrystalOS: the compositor, windows, taskbar, launcher, applications
com.crystalgui.workbench       the IDE shell and its extension seam
com.crystalgui.app             the product manifests: crystaleditor, shadergraph, machine
com.crystalgui.text            the headless document model: buffers, cursors, syntax, folding, wrap
com.crystalgui.document        what an open document is, headless
com.crystalgui.fs              resources, filesystems, the workspace and its wire
com.crystalgui.serialization   JsonOps and the style codecs
com.crystalgui.net             the networked UI layer, over CrystalGraphics' com.crystalgraphics.net
com.crystalgui.lifecycle       the one CgLifecycleListener
com.crystalgui.probe           what a running game is asked to prove about itself
```

`render/` is top-level, not under `core/`; there is no `core/input/`, `core/sound/` or `core/event/`.

---

# Documentation index

`ls docs/*.md` is the list; this says which one to open. Each is written to be read on its own, so a
row here says what a doc is **for** and nothing about what it contains — the doc's own header does that
better and does not go stale when it changes.

| Doc | For |
|---|---|
| **`CGUI_SETUP.md`** | **Setting up a mod on CrystalGUI**: one Minecraft version (the `com.crystalgui` plugin) or one jar across many (`targets {}`), against Maven or a checkout. What a consumer reads first |
| **`CGUI_BUILDING_UIS.md`** | **Using CrystalGUI rather than building it.** A client-only UI, a networked one, and how to choose. The whole `Networked` authoring surface by example, ending in a symptom→cause table for the failures that are silent |
| **`CGUI_BUILD.md`** | The build: layout, commands, what each check can see, and adding a Minecraft version |
| **`CGUI_CROSS_VERSION.md`** | Code against every Minecraft version and loader — seams, eras, directives, verification. The `cross-version` skill is its checklist |
| **`CGUI_PROFILING.md`** | Profiling CrystalGUI in one run: its channels, what a frame records, the Frame Profiler, scenes and the game, what is not yet instrumented. Read after CrystalGraphics' `docs/PROFILING.md`; the `profiling` skill is its checklist |
| **`CGUI_WORKBENCH_EXTENSIONS.md`** | The other user-facing guide: getting a panel, a file type, a command or a status entry into somebody else's workbench |
| **`CGUI_ARCHITECTURE.md`** | Where everything lives: each module, and the package map of `core/` |
| `CGUI_SHIPPED_ASSETS.md` | Everything under `assets/crystalgui/`: sheets, themes, icons, fonts, shaders |
| **`CGUI_INVARIANTS.md`** | What is invisible from any single class and expensive to rediscover, by subsystem. **Read the section for what you are touching** |
| `CGUI_STYLE_RENDER_PIPELINE.md` | The cascade and the paint path in full — origins, selectors, transitions, drawables, compositing, `background:` grammar, the visual-layer FBO pass |
| `CGUI_WIDGETS.md` | Per-widget API, `::part()` names, pseudo-classes, and the harness scene that covers each |
| `CGUI_WORKBENCH_SERVICES.md` | What a widget may *ask* rather than reach through the application for: `Disposer`, `DataContext`, `Resource`, the document layer, `Workspace`, `EditorService`. **New service API is added here in the same commit** |
| `CGUI_SERVER_AND_SERIALIZATION.md` | Codecs, descriptions, content hashing, sessions and RPC — and the headless contract underneath them |
| `CGUI_NETWORKING_PRIMER.md` | Networking from the bottom up, ELI5 first: what a frame, a session and a peer each are, how a `CgProtocolConnection` is established, and how to define a packet contract on both halves |
| `CGUI_THEMING.md` | Themes, editor colour schemes, the token vocabulary. Its token table is generated and machine-checked — regenerate it from the failing test, never by hand |
| `CGUI_COMMANDS.md` | Every command the codebase declares, by area, with its menus and keys — the sweep behind the menu-icon pass. **A snapshot, not a contract**: it is regenerated, not maintained, so trust the code where the two disagree |
| `CGUI_NEW_ENGINE.md` | Reading a commit or a comment that still names the old engine: what replaced what, and the six habits that are now wrong |
| `CGUI_MODERN_UI_RENDERING_RESEARCH.md` | The primary sources behind glass, blur, gradients and the taskbar, with their exact numbers. **Read the relevant section before touching any of them** — each was first built from memory and each was wrong in a way only the source showed |

## Plans

**`plan/README.md` is the index, and it is generated** — by area, by status, nested by parentage, from
each plan's own front matter. This file used to carry fifteen rows describing plans, which was a second
copy of a listing that can now regenerate itself, of documents most readers cannot open.

`plan/` is a separate **private** repository and is not part of this checkout. Absent is normal and
nothing here depends on it. When present, a citation like `plan/engine-port.md` §2.6 resolves inside it
at whatever depth the plan sits, and `python plan/tools/verify.py --repo .` checks that every one of
them still does — which `PlanCitationsResolveTest` runs under `:core:check`.

## External references

- **LDLib2** — pattern prior art for widgets and the Ore theme. An **in-repo checkout** at
  `research_repos/LDLib2`, never a dependency. Stylesheets at
  `research_repos/LDLib2/src/main/resources/assets/ldlib2/lss/` (`gdp.lss`, `mc.lss`, `modern.lss`).
  Java sources under `src/main/java/com/lowdragmc/lowdraglib2/`; note `bin/` also holds compiled
  `.class` files, so search `src/` explicitly. *(Was documented as a sibling checkout at `../LDLib2`,
  which does not exist.)*
- **Taffy** — consumed as the Gradle artifact `dev.vfyjxf:taffy` (version in `gradle.properties`), but
  **extracted Java sources are checked in** at `research_repos/taffy/dev/vfyjxf/taffy/`. Read them
  directly — there is no need to decompile through the IDE, and no need to guess at layout semantics
  (containing blocks, absolute positioning, flex-wrap cross-sizing) that the engine's own behaviour
  depends on. *(Previously documented as "no source checkout"; it exists.)*
- **Monaco** — an in-repo checkout at `research_repos/monaco`, which is where every "VS Code does
  X" claim in `com.crystalgui.text.cursor` was read rather than remembered. See *Port, don't reinvent*.
- **Minecraft sources** — not extracted at the paths the MC modules would produce
  (`runtime/mc/modern/*/build/mc-src/`, `build/rfg/minecraft-src/java`), since neither MC module is in the build.
  **But an extracted 1.20.1 tree is checked in** at `research_repos/mc1201_sources/`
  (`com/`, `mcp/`, `net/`). Cite that path, not the build ones.

---

# For future reference

- **Cg** → CrystalGraphics
- **Cgui** → CrystalGUI
