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

## No re-delegation

**A subagent does its assigned work itself** with Read, Edit, Write, Bash, Glob and Grep — it never spawns
another agent or hands off via `task()`, however complex the work. The only exception is asking the
orchestrator a clarifying question, inline.

---

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
| [`runtime/mc/modern/README.md`](runtime/mc/modern/README.md) | Before touching a modern node; each branch has its own `CLAUDE.md` |
| [`download/README.md`](download/README.md) | Every address the runtime downloads from, and pinning a new version's script names |
| [`docs/CGUI_SETUP.md`](docs/CGUI_SETUP.md) | Setting up a *consumer* mod on CrystalGUI |
| `gl-debug-harness/AGENTS.md` | Writing a harness scene |

## Commands, checks and the rules the build will not tell you

All in [`docs/CGUI_BUILD.md`](docs/CGUI_BUILD.md): every command and flag, the two jars, what each check can see,
running Minecraft (`serverSmoke`, `runClient`, `prodSmoke` and the sweep), and its caveats. **`checkAllTargets`
before every commit**, and name test classes: `./gradlew :core:test --tests "<Class>"` — a `com.crystalgui.ui.*`
wildcard never reports.

## Supported versions

Forge 1.7.10 · 1.8.8–1.12.2 · 1.13.2–1.21.11 · 26.1.1–26.3; NeoForge 1.20.2–26.3; Fabric 1.14.4–26.3 — with gaps. **Before code that differs by version**, read [`docs/CGUI_BUILD.md`](docs/CGUI_BUILD.md) § *Supported versions*: which versions are refused and why, which run Java 8, 26.x's Vulkan and unobfuscated names, 26.3's SDL3 input (`CgUiInput.hostKey`), and JOML below 1.19.3.

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

`cgui-gallery` is the default smoke test and `cgui-desktop` the one-scene check. **Every scene, with each one's switches: [`harness-scenes/CLAUDE.md`](harness-scenes/CLAUDE.md)**, which
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

## Four test source sets, and they are not interchangeable

| Source set | CrystalGraphics on classpath? | What belongs there |
|---|---|---|
| `core/src/test/` | ✅ `testImplementation` | Anything needing `CgIO`, fonts, `StyleSheet`, sprites, drawables |
| `core/src/headlessTest/` | ✅ `core` and `platform`, **no GL context and no fonts** | Everything a dedicated server must run: `serialization/`, `net/`, tree/state logic, and **`text.lang` — the language SPIs, which run here precisely because no engine and no grammar is on this classpath** |
| `core/src/trackedTest/` | ✅ `core`, `platform` and `vulkan`, with LWJGL 3 | CrystalGUI's shaders on CrystalGraphics' tracked backend over shaderc: every `.shader`, pass and keyword variant, linked as a Vulkan device will link them. Its own task, `:core:trackedTest` — the backend it installs is process-wide |
| `language/src/test/` | ✅ (plus the tree-sitter natives) | Grammars, queries, the tokenizer. Skips cleanly when a native will not load on the running platform |
| harness scenes | ✅ full GL | Anything visual |

**The classpath is a dedicated server's**: CrystalGraphics `core` and `platform`, no GL context, no fonts. JOML and Taffy **must stay** on it — `UINode` and `ElementStyle` have fields of their types. Why each: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *The headless classpath*.

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
a broken selector rather than as a skipped pass.

**`beginFrame()` only invalidates the hover cache, never reads it, and settling is bounded (`MAX_SETTLE_PASSES`)** — `ui/service/CLAUDE.md` has why.

---

# Load-bearing invariants

**[`docs/CGUI_INVARIANTS.md`](docs/CGUI_INVARIANTS.md)**, grouped by subsystem: read the section for what you are about to
touch, never front to back. The nine that bite most often:

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

These behaviours are conventions, not derivable answers: each is one line, invisible when wrong, and learned by shipping to millions. `text/cursor/CLAUDE.md` has four of them.

### Licenses are load-bearing here

| Source | Licence | What you may do |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file. |
| **Chromium** | **BSD-3-Clause** | **Port the code.** Attribute in the class javadoc and in `THIRD-PARTY.md`. `RateEstimator` is one. |
| **Zed**, **wget** | **GPL** | **Read for shape only.** Copying would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; the progress channel takes wget's *refresh the ETA about once a second* and not a line of its code. |

### Port the module boundaries too

A port keeps its source's module boundaries as well as its algorithms: they are what keeps it testable. `text.cursor`'s mapping onto VS Code is its own guide, `core/src/main/java/com/crystalgui/text/cursor/CLAUDE.md`.

## CrystalGraphics ownership boundary

**CrystalGraphics owns the rendering backend. CrystalGUI consumes it.**

- CrystalGUI may define renderer-facing abstractions and UI draw orchestration.
- Fonts, shaders, framebuffers, VAO/VBO, draw submission, GPU resource ownership, and modern GL
  pipeline capability belong in **CrystalGraphics**.
- Never write raw GL, raw `float[]` vertex packing, or a hand-rolled buffer in `core/`.

A missing backend capability is **added to CrystalGraphics** and integrated against, never reimplemented here.
The buffer and mesh ownership map is `CrystalGraphics/AGENTS.md`.

## Platform-agnostic core

`core/` names no `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*` or `org.lwjgl.*`: the import guard on
`compileJava` fails the build, with no exemptions.

**The platform seam is CrystalGraphics' `CgPlatform`**; CrystalGUI has no registry of its own. **UI code reaches
keys, modifiers, the clipboard, sounds and the cursor through `PlatformPort.current()`**, never `CgPlatform`
directly — that bypasses a document's own thread. Presenting a cursor is `core.cursor`'s `CursorService`. The
bundle and its slots, and what is reached where: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *The platform seam*.

## Lombok

Use it for accessors, constructors and builders (`compileOnly`, Java 8 bytecode); `@Accessors(chain = true)` for
fluent setters. Never `@Data` on a class with inheritance, and always `@EqualsAndHashCode(callSuper = true)` on a
subclass.

---

# Package map

Every package of `core/` and what it owns: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *Package map*. `render/` is top-level, not under `core/`; there is no `core/input/`, `core/sound/` or `core/event/`.

---

# Documentation index

Each doc in `docs/` is written to be read on its own; [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *Docs* says when to open each. The ones the tables above do not route to:

- **`CGUI_SETUP.md`**, **`CGUI_BUILDING_UIS.md`**, **`CGUI_WORKBENCH_EXTENSIONS.md`** — the user-facing guides: setting up a consumer mod, building a UI on CrystalGUI, extending somebody else's workbench
- `CGUI_THEMING.md` — its token table is generated: regenerate it from the failing test, never by hand
- `CGUI_COMMANDS.md` — a regenerated snapshot of every command; trust the code where they disagree
- `CGUI_NEW_ENGINE.md` — reading a commit or comment that still names the old engine
- `CGUI_NETWORKING_PRIMER.md` — networking from the bottom up
- `CGUI_MODERN_UI_RENDERING_RESEARCH.md` — **read the relevant section before touching glass, blur, gradients or the taskbar**

## Plans

`plan/` is a separate **private** repository, absent from most checkouts; `plan/README.md` is its generated index. A citation like `plan/engine-port.md` §2.6 resolves inside it, and `PlanCitationsResolveTest` checks every one under `:core:check` (by hand: `python plan/tools/verify.py --repo .`).

## External references

In-repo checkouts under `research_repos/`, never dependencies: **LDLib2** (widget and Ore-theme prior art), **taffy** (the layout engine's extracted sources — read them rather than guess), **monaco** (where every "VS Code does X" in `text.cursor` was read), **mc1201_sources** (an extracted 1.20.1 tree — cite it). Paths and what to search: [`docs/CGUI_ARCHITECTURE.md`](docs/CGUI_ARCHITECTURE.md) § *External references*.

---

# For future reference

- **Cg** → CrystalGraphics
- **Cgui** → CrystalGUI
