# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

---

## 📂 Required Reading — Additional Context Files

This repository has multiple context files beyond this one. **Before doing any work, you must read the files relevant to your task scope.**

### Always read (every session):
- `docs/CRYSTALSHADER_MANIFESTO.md` — Grand goal, rendering philosophy, architecture principles

### Read when working on CrystalGraphics:
- `CrystalGraphics/AGENTS.md` — CrystalGraphics module: full infrastructure ownership map, class inventory, package guide, rendering rules. **Mandatory before touching any rendering, buffer, shader, VAO, or mesh code.**

### Read when working on specific subsystems (if it exists):
- Any `AGENTS.md` found inside the package you are modifying — these contain authoritative package-level guidance

---

## NO RE-DELEGATION

**Subagents MUST NOT delegate their assigned work to another agent.**

When you are assigned a task — whether by Sisyphus, a plan, or a user — you execute it yourself using the tools available to you (Read, Edit, Write, Bash, Glob, Grep, etc.). You do not spawn a child agent, fire a background task, or use `task()` to hand off the work.

**This is an absolute prohibition. No exceptions.**

Violation examples (all forbidden):
- Receiving a "test and fix" task, then calling `task(category="unspecified-high", ...)` to do the testing
- Receiving an implementation task, then calling `task(subagent_type="explore", ...)` to explore and never implementing
- Delegating "because it's complex" — complexity is not a reason to re-delegate

The only tool use that touches another agent is asking Sisyphus (the orchestrator) a clarifying question, which must be done inline, not as a background task.

**If you are a subagent and you find yourself writing a `task()` call: STOP. Do the work yourself.**

---

# THE GRAND GOAL — READ THIS FIRST
> **Every line of code in this repository exists to serve one end goal:**
> A **node-based shader graph for Minecraft (cross-version: 1.7.10 and 1.20.1)** — like Unity's Shader Graph, but staying true to GLSL, running on a modern GL 3.x+ pipeline, with instancing as the default draw path from day one.
>
> The full architecture, principles, file format, instancing strategy, compilation pipeline, and ordered roadmap are defined in the manifesto. **Read it before making any rendering or shader-related decision.**
>
> 📄 **[CrystalShader Manifesto](docs/CRYSTALSHADER_MANIFESTO.md)**

---

# Crystal GUI:

The idea of this mod is to be UI engine similar to a lightweight web browser.

> ⚠️ The section below reflects the actual code in `core/src/main/java/com/crystalgui/` as of 2026-07-22.
> No `AGENTS.md`/`package-info.java` files exist yet inside any `core/` package — this section is
> currently the only package-level guidance. If you add one, link it here.

## Module layout (as actually wired in Gradle)

`settings.gradle.kts` at repo root currently includes only **`core`** and **`gl-debug-harness`** as
subprojects; `includeBuild("mc1710")` / `includeBuild("mc1201")` are commented out. So:

- **`core/`** — the platform-agnostic UI engine (`com.crystalgui.core`, `com.crystalgui.render`,
  `com.crystalgui.style`, `com.crystalgui.ui`). Java 21 authored (Jabel-desugared toward Java 8 bytecode
  target), depends on CrystalGraphics `core`/`platform` (`compileOnly`) + Taffy + JOML. A `doLast` hook on
  `compileJava` fails the build if any source line imports `net.minecraft.*`, `cpw.mods.fml.*`,
  `net.minecraftforge.*`, or `org.lwjgl.*` — this is enforced, not aspirational.
- **`mc1710/`** — MC 1.7.10/Forge loader. Has its own `build.gradle.kts` but is **not currently included**
  in the root build. Its only source file, `CrystalGUI.java`, is a bare `@Mod` stub (`preInit`/`init`/
  `postInit` that only log) — no rendering hook, no input forwarding, no CrystalGUI integration yet.
- **`mc1201/`** (`common/`, `forge/`, `neoforge/`, `fabric/`) — present on disk with real subproject
  structure but likewise not wired into the root `settings.gradle.kts` yet.
- **`gl-debug-harness/`** — a submodule (branch `crystalgui`) for GL testing without Minecraft, same tool
  as CrystalGraphics' harness.

**Practical implication:** right now the only thing you can actually compile/test end-to-end is `core/`
in isolation (`./gradlew :core:compileJava`, `./gradlew :core:test`). There is no working Minecraft
integration to run the UI in-game yet — don't claim otherwise.

## Core library — actual package map

```
com.crystalgui.core            CrystalGuiCore (global LOGGER + CgUiInputAdapter registry)
  .data                        CacheCell/IntCacheCell/LongCacheCell (dirty-flag memoization), ReadOnlyVec2f
  .input                       CgUiInputAdapter (SPI), SystemInput (raw Mouse.Event/Keyboard.Event records)
    .keyboard                  CgUiKeyCodes (LWJGL2-shaped constants), Modifiers (bitmask, no LWJGL import)
    .mouse                     CgUiMouseCodes
  .property                    Property<T> (binding)
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
com.crystalgui.render          CgUiPaintContext, CgUiRenderer, ScissorStack
  .texture                     CgUiDrawable (SPI), CgUiQuad, CgUiSprite (9-slice), CgUiTransformDrawable (stub)
  .texture.geometry            Position, Size (small int value types)
com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin, TaffyBridge, PsuedoClasses [sic]
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue, IValueInterpolator
    .general.{bools,enums,floats,ints,strings}   scalar StyleValue/StyleProperty flavors
    .layout.{dimension,grid,length}              LayoutProperties + Taffy-shaped value types
    .visual.{color,texture}                      OverflowClip, ColorProperty, TextureProperty
com.crystalgui.ui              UIElement, UIWindow, Ui, EventListenerGroup
  .tree                        UITreeTraversal (stateless ancestor/tab-order queries)
  .event                       UIEvent, PropagationPhase, DOMEvent, FocusEvent, KeyboardEvent, MouseEvent
  .input                       UIInputHandler, FocusPolicy, ButtonState
  .elements                    (empty — no widgets implemented yet)
```

**Important naming/location corrections vs. older notes:** `render/` is a top-level package
(`com.crystalgui.render`), **not** nested under `core/`. `core/input/` is now the *raw platform I/O*
layer only (key/mouse codes, modifiers, the `CgUiInputAdapter` SPI) — the dispatch/focus logic that used
to be described as living there is in `ui/input/`. The three-phase event types live in `ui/event/`, not
`core/event/` (there is no `core/event/` package). `CgUiKeyCodes`/`Modifiers` live in `core/input/keyboard/`.

### DOM tree — no `UIContainer`

There is **no `UIContainer` class**. `UIElement` alone is both leaf and container, exactly like a real DOM
`Element` (its own Javadoc: "a general-purpose, styleable, extensible container, conceptually like an HTML
`<div>`"). Key pieces:
- **`UIElement`** — tree (`parent`/`children`, `addChild`/`removeChild`/reparenting), identity (`id`,
  `classes`), state flags (`isEnabled`/`isPressed`/`isFocused`/`isHovered`), `focusPolicy`, one owned
  `ElementStyle`, pre-bound `EventListenerGroup<T>` fields for the common event types
  (`onMouseDown`/`onMouseUp`/`onMouseMove`/`onMouseEnter`/`onMouseLeave`/`onMouseScroll`/`onFocus`/`onBlur`),
  hit-testing (`isMouseOverElement`/`isMouseOverContent`), `drawSubtree(CgUiPaintContext)` (final —
  `paintSelf`/`paintOverlay` are the overridable extension points), and an inner `RuntimeCache` of
  `CacheCell`s (sorted children by z-index, local↔world matrix, depth).
- **`UIWindow`** — the runtime engine: owns the live Taffy tree, a `CgUiPaintContext`, a `UIInputHandler`,
  screen/layout dimensions. `init(w,h)` attaches the root `UIElement`; `calculateLayout()` drives
  `taffyTree.computeLayout(...)`; `paintFrame()` is the per-frame entry point (layout → paint → input
  begin/end frame); `getHoveredElement(x,y)` does the z-order/clip-aware hit test. Its own Javadoc: it
  "deliberately does NOT implement any platform (LWJGL2/LWJGL3/MC) widget or Screen interface itself" —
  MC-side adapters are still future work.
- **`Ui`** — trivial immutable `{ rootElement }` holder; a minimal seed for a future declarative
  description layer, no runtime/layout/GL state of its own.

### Signals, properties, caching (`core/signal`, `core/property`, `core/data`)

- `core/signal/` and `core/property/` match the original design intent closely: `Signal.Action` (0-arg),
  `Signal.Value<T>` (1-arg), `Signal.Pair<A,B>` (2-arg) all extend `SignalBase` (slot list, deferred
  disconnect-during-emit, optional `DebugHook`). `Property<T>` is one `Signal.Pair<T,T> changed` field
  plus equality-suppressing `set()`, `bindTo()` (one-way), `bindBidirectional()` (two-way, reentrancy-guarded)
  — both return `Connection`s.
- `core/data/` is new, undocumented-until-now plumbing used pervasively by `UIElement`/`ElementStyle`:
  `CacheCell<T>`/`IntCacheCell`/`LongCacheCell` are dirty-flag memoization cells (`set`/`invalidate`/
  `get(Function)`), and `ReadOnlyVec2f` is an immutable view over a mutable JOML `Vector2f` (used for
  `MouseEvent.position`).

## UI Render Architecture — immediate-mode (the old "V3.1 Draw-List" design is gone)

There is no draw-list/executor/batch-slots layer anymore. `CgUiDrawList`, `CgUiDrawListExecutor`,
`CgUiDrawState`, `CgUiBatchSlots`, and `CgScissorRect` do not exist in the current codebase — do not
reference them. Rendering is now **fully synchronous immediate-mode**, built directly on CrystalGraphics:

- **`CgUiPaintContext`** — per-frame 2D paint surface. Its own Javadoc is explicit that every
  `fillRect`/`drawImage` call draws immediately; there is no recording phase to flush. `beginFrame(w,h)`/
  `endFrame()` save/restore GL state via CrystalGraphics' `CgGlScope`, set up an ortho projection, bind a
  shared `crystalgui:shaders/gui_quad.shader` material, reset the `ScissorStack`. Public draw API:
  `fillRect`, `drawImage`, `submitQuad`+`flush` (used by 9-slice sprites), `bindTexture` (elides redundant
  rebinds), `text()` → a CrystalGraphics `CgTextRenderer` wired to this context's `PoseStack`.
- **`CgUiRenderer`** — thin wrapper around CrystalGraphics' `CgBatchRenderer` (`CgVertexFormat.UI`);
  `begin`/`end`/`flush`/`submitQuad`; its nested `VertexWriter` is `PoseStack`-aware (transforms vertices
  through the current matrix before delegating to `CgVertexWriter`).
- **`ScissorStack`** — allocation-free nested clip stack (`int[64]`, 16 levels × 4 ints), same shape as
  before, but now applies via CrystalGraphics' `CgGL` facade, not raw LWJGL.
- **`render/texture/`** (new) — `CgUiDrawable` is the pluggable "paint yourself into a rect" SPI
  (`draw(ctx, mouseX, mouseY, x, y, w, h)`; static `EMPTY` instance). `CgUiQuad` is a flat solid-color fill.
  `CgUiSprite` is a full 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`, lazy UV/border cache).
  `CgUiTransformDrawable` is currently an empty marker class for future transform-aware drawables.
- **`render/texture/geometry/`** (new) — `Position`/`Size`, small Lombok `@Data(staticConstructor="of")`
  int value types used by `CgUiSprite`.

### Frame lifecycle (current)
```
window.calculateLayout()                 // drives taffyTree.computeLayout() while dirty
paintContext.beginFrame(screenW, screenH) // GL state save, ortho projection, bind gui_quad material
  poseStack.push(); scale(uiScale)
  ui.rootElement.drawSubtree(paintContext) // recursive: paintSelf → children → paintOverlay, immediate draw
  poseStack.pop()
paintContext.endFrame()                   // GL state restore
inputHandler.beginFrame() / endFrame()    // beginFrame() invalidates the hover cache; endFrame() does hover diffing + dispatch
```

## Style / cascade system (new — supersedes the old bare "Stylesheet support" bullet)

There is now a real CSS-cascade-shaped architecture wired into Taffy layout:
- **`StyleOrigin`** — priority-ordered enum `DEFAULT(0) < STYLESHEET(2) < INLINE(3) < ANIMATION(4) <
  IMPORTANT(5)`.
- **`StyleSlot<T>`** — `record(property, origin, specificity, sourceOrder, value)` with full CSS-cascade
  `compareTo` (origin → specificity → source order).
- **`ElementStyle`** — per-`UIElement` cascade: `candidates` (every value ever set, by origin) →
  `computeCandidateSlot` picks the winner → `computedSlots` cache, `dirtyProps` bitset.
- **`StyleGroup<TYPE>`** (base) / **`GeneralGroup`** (background/overlay/opacity/color/zIndex/overflow/mask)
  / **`LayoutGroup`** (~150-method fluent CSS box-model/flex/grid API) — fluent setters that ultimately call
  `StyleGroup.set(property, value)`.
- **`TaffyBridge`** — mutates the live Taffy `TaffyStyle` and marks the tree dirty; `style/property/layout/
  LayoutProperties.init()` wires a `TaffyBridge`-calling listener onto every layout `StyleProperty`, so
  `LayoutGroup.width(100)` flows straight into the live layout.
- **`PsuedoClasses`** *(sic, misspelled in source — preserve spelling when referencing the file)* — enum of
  state predicates (`HOVER`/`ACTIVE`/`FOCUS`/etc.) bound to real `UIElement` getters, ready for a future
  selector engine.

**Caveat — don't overclaim:** `StyleOrigin.STYLESHEET` exists only as a priority level. There is **no
stylesheet text parser and no CSS-selector matcher anywhere in the codebase yet.** The cascade
infrastructure is stylesheet-ready, but "stylesheet support" itself is not implemented.

## Events, input, and focus

Three-phase (capture/target/bubble) dispatch is real and implemented, but moved and renamed:
- **`ui/event/`** — `UIEvent` (abstract base: `target`, `phase`, `stopPropagation`/`stopPhasePropagation`/
  `preventDefault`), `PropagationPhase` (`CAPTURE`/`TARGET`/`BUBBLE`), `DOMEvent` (`ElementAdded`/
  `ElementRemoved`, non-bubbling), `FocusEvent` (`Focus`/`Blur`, bubbling), `KeyboardEvent` (`Down`/`Up`,
  bubbling), `MouseEvent` (`Click`→`Down`/`Up`, `Scroll`, `Move` bubbling; `Enter`/`Leave` non-bubbling,
  matching real DOM semantics).
- **`EventListenerGroup<T>`** (in `com.crystalgui.ui`) — per-(element, event-type) bundle of three
  `Signal.Pair` (`capture`/`target`/`bubble`) plus a `defaultEvents` signal for built-in behavior. Nested
  `EventListenerGroup.Map` lazily creates one group per concrete event class.
- **`ui/input/UIInputHandler`** — the actual three-phase walker (`sendInputEvent`: build
  `UITreeTraversal.pathToRoot`, walk root→target for CAPTURE, fire once for TARGET, walk target→root for
  BUBBLE if `bubbles`). This single class merges what older notes called `UiInputManager` + `FocusManager`
  — there is no such split anymore. It implements `SystemInput.Mouse`/`SystemInput.Keyboard` directly
  (registered as the raw-event sink), tracks hover via a `CacheCell<UIElement>` diffed once per frame
  entirely inside `endFrame()`/`fireAccumulatedMouseEvents()` (propagating `:hover`-like state up the
  ancestor chain) — `beginFrame()` unconditionally invalidates that cache (forcing a fresh hit-test
  every frame, so a UI reflow under a stationary cursor doesn't leave hover stale until the next real
  mouse move — fixed 2026-07-22), but must NOT also read/snapshot it (that was the original stuck-hover
  bug: mouse-move events already invalidate the same cache before `beginFrame()` runs, so reading it
  there was really an eager recompute against the *new* position mislabeled as the *old* one — also
  fixed 2026-07-22). The "last frame's hover" baseline is a plain field (`lastFrameHover`) snapshotted
  at the end of `fireAccumulatedMouseEvents()`, not read from the cache at the top of the frame. Tracks
  click/press state per button (`ButtonState`, multi-click `detail` counting), and drives Tab-key focus
  traversal via `ui/tree/UITreeTraversal` (`firstFocusableIn`/`lastFocusableIn`/`previousFocusable`/`nextFocusable`).
- **`ui/input/FocusPolicy`** (`NONE`/`FOCUSABLE`/`CLICK`) and **`ui/tree/UITreeTraversal`** (stateless
  ancestor/tab-order queries over the `UIElement` tree) round out the package.

Note the actual accumulate-then-dispatch model: `consumeMouseEvent`/`consumeKeyboardEvent` accumulate raw
input during the frame; click/focus/keyboard events dispatch immediately, but hover/enter/leave/move/scroll
are synthesized once per frame from `UIWindow.paintFrame()`'s `inputHandler.beginFrame()`/`endFrame()` calls
— not synchronously per raw platform event like the old `processMouseMove/Down/Up` pseudocode implied.
`beginFrame()` only invalidates the hover cache (forcing a fresh hit-test against that frame's layout);
all of the hover snapshot/diff/dispatch work happens inside `endFrame()`.

## UI elements — currently empty

`ui/elements/` exists as a package but **contains zero classes**. `UiPanel`, `UiButton`, `UiLabel`,
`UiTextbox` are not implemented yet — don't reference them as existing. `ui/test/` (previously described as
holding a `CguiTestUi` demo factory) does not exist at all. The only thing usable to build a UI today is the
base `UIElement` itself (background/overlay painting, style, events, focus — everything a generic styleable
"div" needs), plus whatever you subclass from it.

## Minecraft adapter package — not yet implemented

There is no `mc/` package anywhere under `core/`, and `mc1710/src` has no CrystalGUI integration code (see
"Module layout" above — it's a bare `@Mod` stub). None of `CgUiInputAdapater` (concrete impl), `CgUiRenderAdapter`,
`CgUiForgeEventHandler`, or `LwjglKeyTranslator` exist. Only the **interface** side of the seam exists:
`core/input/CgUiInputAdapter` (the SPI a platform integration must implement) and
`CrystalGuiCore.getAdapter()/setAdapter()` (where it gets registered). Building the actual MC 1.7.10
adapter is future work, not a relocated/renamed existing feature.

### Documentation
- `docs/CGUI_STYLE_RENDER_PIPELINE.md` — current-state doc: cascade, selectors/stylesheets, transitions, frame lifecycle, drawable system (tint vs. layer-opacity compositing channels), `background:` CSS grammar reference, SDF rounded rects (per-corner radii, morphing vs. cross-fading), known gaps vs. the web, file map
- `docs/DOM_UI_FUNDAMENTALS V2.md` — learning document: theory and mental models for DOM-based UI frameworks
- `docs/CRYSTALGUI_BACKEND_ROADMAP.md` — phased development plan (signal/slot, LDLib2 analysis, Phases 0–8)
- `docs/PHASES_0_3_IMPLEMENTATION_GUIDE.md` — deep walkthrough of what Phases 0–3 actually built, how the pieces connect, and where the implementation diverged from the roadmap
- These roadmap docs predate the style/cascade system and the render rewrite described above; treat them as historical, not current-state. `docs/CGUI_STYLE_RENDER_PIPELINE.md` is the current-state reference for everything they predate.

## CrystalGraphics Ownership Boundary (Critical)

CrystalGraphics **must own the rendering backend**.

- CrystalGUI may define renderer-facing abstractions and scene/UI draw orchestration.
- CrystalGUI must **not** become the owner of low-level OpenGL backend concerns.
- Fonts, shaders, framebuffers/render targets, VAO/VBO concerns, draw submission plumbing, GPU resource ownership, and modern GL pipeline capabilities belong in **CrystalGraphics**.
- CrystalGUI should consume those APIs and stay backend-using, not backend-owning.

Because CrystalGraphics lives in this same repository and is directly writable here:

- if CrystalGUI needs new rendering backend capabilities, we are **allowed and expected** to add them to CrystalGraphics directly;
- CrystalGUI should then integrate against those new CrystalGraphics APIs rather than reimplementing the backend itself.

Rendering direction going forward:

- We are not treating Minecraft 1.7.10 fixed-function rendering as the target architecture.
- We are moving toward **modern core GL 3.0+ style rendering pipelines**.
- CrystalGraphics will gradually backport 1.20.1-like rendering frameworks and capabilities to 1.7.10 where needed.
- CrystalGUI should be architected around those CrystalGraphics APIs from day one.


# For future reference:
Cg -> acronym for CrystalGraphics
Cgui -> CrystalGUI


## Code Style: Lombok

**Rule: Prioritize Lombok annotations to eliminate handwritten getter/setter boilerplate in all new code.**
Lombok generates Java 8-compatible bytecode. All annotations listed above work correctly with Java 8 and LWJGL 2.9.3. No runtime dependency is added — Lombok is `compileOnly`.

### When to Use Each Annotation

| Annotation | Use When |
|---|---|
| `@Data` | Simple POJOs / value objects with all fields participating in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — when you need getters on all fields but setters on only some, or vice versa |
| `@RequiredArgsConstructor` | Immutable classes — generates constructor for all `final` fields (pairs well with `@Getter` only) |
| `@Builder` | Complex object construction with many optional parameters |
| `@Value` | Fully immutable data carriers (makes class final, all fields private final, no setters) |
| `@ToString` / `@EqualsAndHashCode` | When you need only one of these without full `@Data` |
| `@Slf4j` / `@Log` | Logger field generation (prefer `@Slf4j` if SLF4J is available) |

### Guidelines

1. **Prefer `@Data` for simple POJOs** that are pure data holders with no complex logic.
2. **Use `@Getter` + `@RequiredArgsConstructor` for immutable classes** — avoid `@Data` when you don't want setters.
3. **Use `@Builder` for classes with 4+ constructor parameters** or when many parameters are optional.
4. **Apply `@Getter`/`@Setter` at field level** when only specific fields need accessors.
5. **Do NOT use `@Data` on entities or classes with inheritance** — use explicit `@Getter`/`@Setter`/`@ToString`/`@EqualsAndHashCode` instead to control behavior.
6. **Always use `@EqualsAndHashCode(callSuper = true)`** on subclasses to avoid subtle bugs.

---


## 1.7.10 Module
The 1.7.10 module (`mc1710/`) is meant to hold the version-specific implementations (uses JVMDowngrader to
make CrystalGUI & its dependencies run in Java 8) and eventually the MC-side adapter described above. Most
of the logic should be handled in the core. **As of 2026-07-22 it is not wired into `settings.gradle.kts`**
(`includeBuild("mc1710")` is commented out) and contains only a bare `@Mod` stub with no CrystalGUI
integration — treat it as scaffolding, not a working module, until that changes. `mc1201/` (Forge/NeoForge/
Fabric loaders) exists on disk with real subproject structure but is likewise not included in the build yet.
We must ensure all code added to `core/` stays fully cross-platform applicable — that's what the platform
abstraction layer (the not-yet-built `mc/` adapter seam) is for.


LDLib2 Source: `research_repos/LDLib2/`
Taffy source: `research_repos/taffy/`
Minecraft 1.7.10 DECOMPILED AT `build/rfg/minecraft-src/java`
Minecraft 1.20.1 DECOMPILED AT `research_repos/mc1201_sources/`

---

# CrystalGraphics Infrastructure — Use What Exists (MANDATORY)

CrystalGraphics has mature, layered GPU infrastructure. **Before writing any buffer, shader, VAO, mesh, or data-packing code, you are required to check whether an existing class already owns that concern.**

The pattern of defaulting to raw OpenGL calls or raw `float[]`/`byte[]` when project abstractions exist is forbidden. Every class below was built to own its use case permanently.

---

## Reconnaissance Protocol (Run Before Every Implementation)

1. Grep for the concept: `buffer`, `writer`, `staging`, `mesh`, `shader`, `vao`, `stream`
2. Read the 2-3 closest classes in full before writing anything
3. Ask: "Is what I need an extension of an existing class's scope, or genuinely orthogonal?"
4. If the existing class almost fits → **widen it** (add the method, extract an abstract parent)
5. Only create something new when the semantics are genuinely apples-to-oranges

---

## Infrastructure Ownership Map

### GPU Buffer Upload — `CgStreamBuffer`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/CgStreamBuffer.java`
- Owns: ALL dynamic GPU buffer uploads — vertex data, shader buffer data, anything that streams to the GPU per-frame
- Key methods: `uploadFloats(float[], int)`, `map(int)`, `commit(int)`, `bind()`, factory `create(int)` / `createForShaderBuffer(int, int)`
- ❌ NEVER: `GL15.glGenBuffers()` + raw `glBufferData`/`glBufferSubData` in a feature class. That is CgStreamBuffer's job.

### CPU Data Staging — `CgStagingBuffer`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/staging/CgStagingBuffer.java`
- Owns: ALL CPU-side float accumulation before GPU upload — growing float array, write cursor, reset
- Key methods: `putFloat(float)`, `putIntBits(int)`, `ensureRoomForNextVertex()`, `reset()`, `rawData()`, `rawCursor()`
- ❌ NEVER: a raw `float[]` field + manual index tracking inside a writer or buffer class. That is CgStagingBuffer's job.

### Vertex Data Packing — `CgVertexWriter`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/staging/CgVertexWriter.java`
- Owns: Converting semantic vertex attributes (position, UV, color, normal) → interleaved floats in a CgStagingBuffer
- Key methods: `vertex(x,y,z)`, `uv(u,v)`, `color(r,g,b,a)`, `normal(x,y,z)`, `endVertex()`
- ❌ NEVER: Manually calling `stagingBuffer.putFloat(x); stagingBuffer.putFloat(y)` for vertex attributes. Use CgVertexWriter.

### Per-Instance Data Packing — `CgInstanceWriter`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/staging/CgInstanceWriter.java`
- Owns: Packing per-instance data (matrices, colors, custom floats) into a CgStagingBuffer for instanced draw calls
- Key methods: `mat4(Matrix4f)`, `mat3(Matrix3f)`, `vec2/3/4(...)`, `colorARGB(int)`, `beginInstance()`, `endInstance()`
- ❌ NEVER: A raw float[] for instance data, or calling putFloat manually for matrices. Use CgInstanceWriter.

### Shader Buffer Data Packing — `CgBufferWriter`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/staging/CgBufferWriter.java`
- Owns: Writing uniform block data, SSBO data, TBO data — all non-vertex GPU float packing, backed by CgStagingBuffer
- Key methods: `putFloat(float)`, `putInt(int)`, `vec2/3/4(...)`, `mat3/4(...)`, `beginRecord()`, `endRecord(int)`, `reset()`
- Sister classes: `CgVertexWriter`, `CgInstanceWriter` — if you need a new writer, model it on these and back it with CgStagingBuffer

### Shader Buffer Lifecycle — `CgShaderBuffer` + subclasses
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/buffer/shader/CgShaderBuffer.java`
- Owns: SSBO/TBO/UBO lifecycle — create, write session (`beginWrite`/`endWrite`), GPU upload, bind/unbind
- Subclasses: `CgShaderStorageBuffer` (GL 4.3+), `CgTextureBuffer` (GL 3.1 fallback), `CgUniformBuffer` (per-frame uniforms)
- Key methods: `create(int)`, `beginWrite(int)`, `advanceRecord()`, `writer()`, `endWrite()`, `bind(int)`
- ❌ NEVER: A raw `int glBufferId` field created with `GL15.glGenBuffers()` in a shader buffer class. That is CgStreamBuffer's job, already used by CgShaderBuffer.
- ❌ NEVER: A new SSBO/UBO/TBO class that does not extend CgShaderBuffer.

### Static Mesh — `CgMesh`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/mesh/CgMesh.java`
- Owns: Immutable static geometry — VBO + optional IBO + VAO, uploaded once, drawn many times
- Key methods: `upload(CgVertexFormat, CgMeshTopology, ByteBuffer, ByteBuffer, int)`, `drawDirect()`, `delete()`
- ❌ NEVER: Manually creating a VBO + VAO for static geometry. CgMesh handles that.

### VAO Management — `CgVertexArray`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/gl/vertex/CgVertexArray.java`
- Owns: VAO lifecycle and attribute pointer setup across GL 3.0 core / ARB fallback
- Key methods: `create()`, `bind()`, `unbind()`, `configure(CgVertexFormat)`, `reconfigureWithOffset(...)`
- ❌ NEVER: `GL30.glGenVertexArrays()` / `ARBVertexArrayObject.glGenVertexArrays()` outside this class.

### Shader Programs — `CgAbstractShaderProgram` + `CgShaderFactory`
**Files**: `gl/shader/CgAbstractShaderProgram.java`, `gl/shader/CgShaderFactory.java`
- `CgAbstractShaderProgram` owns: shader lifecycle (bind, unbind, delete, ownership tracking)
- `CgShaderFactory` owns: compilation + framebufferPath waterfall selection (core vs ARB)
- ❌ NEVER: `glCreateProgram()` / `glCreateShader()` outside these classes.

### Buffer Interface — `CgObjectBuffer`
**File**: `CrystalGraphics/src/main/java/com/crystalgraphics/api/buffer/CgObjectBuffer.java`
- The common interface for all GPU-resident data blocks. New buffer types must implement it.

---

## Decision Tree: "I need a buffer / writer / shader"

```
Need to upload data to GPU per-frame?
  └─> CgStreamBuffer

Need to accumulate float data CPU-side before upload?
  └─> CgStagingBuffer (directly) or via a Writer class

Need to write vertex attributes (pos, uv, color, normal)?
  └─> CgVertexWriter

Need to write per-instance data (matrices, colors)?
  └─> CgInstanceWriter

Need to write uniform block / SSBO / TBO data?
  └─> CgBufferWriter (CPU side) + CgShaderBuffer subclass (GPU side)

Need a new SSBO/UBO/TBO type?
  └─> Extend CgShaderBuffer. Do NOT create a new raw buffer.

Need static geometry on GPU?
  └─> CgMesh

Need a VAO?
  └─> CgVertexArray

Need a shader program?
  └─> CgShaderFactory.compile() / extend CgAbstractShaderProgram
```


