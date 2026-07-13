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

## Core library
The core of CrystalGUI can be written in versions of Java newer than 8 and depends on CrystalGraphics.
- DOM-style component tree 
- It uses Taffy as a layout backend (already included as a dependency)
- The renderer is supposed to be platform agnostic 
- Supports DOM-style three-phase events (capture/target/bubble)
- Mouse/Keyboard/Input events. (Signal/Slot design pattern? I heard EventBus is unrecommended to use. If you know of any better design patterns for a web-browser like layout engine lemme know) 
- Data-Driven Reactivity (Property Binding)
- RPC Events
- XML-based GUI creation (Delegate to V2)
  - need a component registry for that in the future though
- Code based GUI creation
- Stylesheet support

## UI Render Architecture (V3.1 Draw-List)

The primary UI rendering model uses a **painter's-order draw list** instead of typed layers.

### Key concepts
- `CgUiDrawList` — packed `int[]` command pool recording draw commands in DOM traversal order
- `CgUiPaintContext` — paint surface passed through UI traversal (recording side)
- `CgUiDrawListExecutor` — stateless sequential replay
- `CgUiDrawState` — cached command-local draw state (reference-identity merge)
- `CgUiBatchSlots` — `Map<CgVertexFormat, CgBatchRenderer>` with stable slot indices
- `ScissorStack` — allocation-free nested clips (dual-mode: logical + GL apply)
- `CgScissorRect` — lives in CrystalGraphics `api/state/`, pooled by ScissorStack

### Source package guide
- `src/main/java/com/crystalgui/core/render/AGENTS.md` — authoritative package guide

### UI element and test packages
- `ui/elements/` — reusable `UIElement` subclasses (`UiPanel`: filled rectangle via draw-list)
- `ui/test/` — reusable demo/test UI factories (`CguiTestUi`: static factory building a test `UIContainer`)

### Interaction & reactivity packages (Phases 0–3)
- `core/signal/` — unified signal/slot primitives (`Signal.Action`, `Signal.Value<T>`, `Signal.Pair<A,B>`, `SignalBase`, `Connection`, `ConnectionGroup`); see `core/signal/AGENTS.md`
- `core/property/` — observable `Property<T>` with equality-suppressing change notification, one-way and bidirectional binding; see `core/property/AGENTS.md`
- `core/input/` — container-scoped interaction layer (`UiInputManager`, `FocusManager`, `FocusPolicy`); see `core/input/AGENTS.md`
- `core/event/` — DOM-style three-phase event dispatch, typed event hierarchy (`UiMouseEvent`, `UiKeyEvent`, `CgUiKeyCodes`, `Modifiers`), verbose debug logging (`CgUiDebug`); see `core/event/AGENTS.md`
- `ui/elements/UiButton` — first interactive widget: click signal, hover signal, `FocusPolicy.CLICK`; see `ui/elements/AGENTS.md`
- `ui/elements/UiLabel` — property-backed text label with Taffy `MeasureFunc` for intrinsic sizing
- `ui/elements/UiTextbox` — single-line text input widget: `textChanged` signal, `submitted` signal, caret navigation, backspace/delete, `FocusPolicy.CLICK`

### Minecraft adapter package
- `mc/` — decoupled LWJGL 2 adapter utilities: `CgUiInputAdapater` (stateless input forwarding with caller-supplied coordinate transform/key filter), `CgUiRenderAdapter` (layout + render invocation), `CgUiForgeEventHandler` (ready-to-use `@SubscribeEvent` handler for `InputEvent.MouseInputEvent`/`KeyInputEvent`/`RenderGameOverlayEvent.Post`), `LwjglKeyTranslator` (LWJGL 2 → CgUiKeyCodes translation); see `mc/AGENTS.md`

### Documentation
- `docs/DOM_UI_FUNDAMENTALS V2.md` — learning document: theory and mental models for DOM-based UI frameworks
- `docs/CRYSTALGUI_BACKEND_ROADMAP.md` — phased development plan (signal/slot, LDLib2 analysis, Phases 0–8)
- `docs/PHASES_0_3_IMPLEMENTATION_GUIDE.md` — deep walkthrough of what Phases 0–3 actually built, how the pieces connect, and where the implementation diverged from the roadmap

### Frame lifecycle
```
paintContext.beginRecord()
  root.drawSubtree(paintContext)   // DOM traversal, painter's order
paintContext.endRecord()
executor.execute(drawList, slots, projection)  // replay
```

### Interaction lifecycle
```
UiInputManager.processMouseMove(x, y, mods)  // one hit-test → hover enter/leave → move dispatch
UiInputManager.processMouseDown(x, y, btn, mods)  // hit-test → MOUSE_DOWN → click-to-focus
UiInputManager.processMouseUp(x, y, btn, mods)  // MOUSE_UP → click/double-click synthesis
FocusManager.dispatchKeyEvent(keyEvent)  // Tab traversal or routed key dispatch
UIContainer.computeLayout(w, h)  // → validateFocus() after layout
```

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
The main module for now. other modules will come in the future, but we must ensure all code added is fully cross-platform applicable, 
and thats also where the future abstraction layer comes in.
The 1.7.10 module of CrystalGUI contains the version-specific implementations, uses JVMDowngrader to make CrystalGUI & its dependencies run in Java 8.
Most of the logic should be handled in the core. 


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


