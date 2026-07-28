# CrystalGUI V4 — Architecture Overhaul  ⟨HISTORICAL⟩

> ## ⚠️ This is a decision record, not a description of the code.
>
> **Current-state references live elsewhere:**
> `CGUI_STYLE_RENDER_PIPELINE.md` (cascade, stylesheets, painting) ·
> `CGUI_WIDGETS.md` (the twelve widgets) ·
> `CGUI_SERVER_AND_SERIALIZATION.md` (codecs, packets, sessions) ·
> `../AGENTS.md` (package map).
>
> **What is still true here** is the *premise*: V3.x had CrystalGUI owning GL infrastructure, that
> was wrong, and CrystalGraphics owns the rendering backend. That boundary is still the governing
> constraint of the project, and this document is the record of why.
>
> **What is not true** is the design that follows from it. V4 specified a `CgRenderCommand` / `CgUiPass`
> render-queue architecture; the codebase went the other way and rendering is **fully synchronous
> immediate-mode** through `CgUiPaintContext`. Per-part status:
>
> | Part | Status |
> |---|---|
> | Core Problem · V4 Principle | ✅ still the governing rationale |
> | Part 1.1 Tear Down | ⚠️ mostly landed — but `ScissorStack` was **not** deleted and is still in use |
> | Part 1.2 – Part 4 (`CgUiPass`, scissor-on-command, `CgUiPaintContext` V4, `UiMaterials`, `UiMeshCache`, `UiCommandTextSink`, the `ui_*.shader` set) | ❌ **abandoned** — none of it exists; the shipped materials are `gui_quad`, `gui_rounded_rect`, `gui_layer_blit` |
> | Part 5.1 Module structure (`core/` + `mc1710/` + `mc1201/`) | ✅ landed |
> | Part 6 "What is Preserved" | ❌ **struck** — see the note in place of that section |
> | Part 7 Harness scene | ⚠️ a scene exists (`cgui-test`), built from different classes than described; there are now fifteen |
>
> Read this document for the *why*. Never for the *what*.
>
> (The two files its original banner claimed to supersede — `CRYSTALGUI_BACKEND_ROADMAP.md` and
> `PHASES_0_3_IMPLEMENTATION_GUIDE.md` — have never existed in this repository's history.)

---

## The Core Problem with V3.x

V3.x built a parallel rendering sub-system inside CrystalGUI — draw list, batch slots, batch renderer
orchestration, render state objects, a shader runtime — all duplicating infrastructure CrystalGraphics
already owns and does better. The result is a broken, half-migrated mess:

- `CgUiDrawListExecutor` cannot bind shaders, projection, or textures (all broken TODOs since T8)
- `CgUiRuntime` loads shaders via `CgShaderFactory` in a static initializer — no GL lifecycle, will crash on cold start
- `CgUiBatchSlots` reinvents what `CgRenderPipeline`'s command queue already provides
- `CgUiRenderAdapter` has no GL state save/restore
- The MC adapter is 1.7.10-only with no path to 1.20.1
- No use of `CgMaterial` / `.shader` format anywhere

V3.x is not salvageable. We tear it down.

---

## The V4 Principle

> **CrystalGUI does not own rendering infrastructure. CrystalGraphics does.**

CrystalGUI's render layer is a thin paint surface that translates UI draw calls into `CgRenderCommand`
submissions to a new UI render pass inside `CgRenderPipeline`. The command queue, the render pass,
the shaders (as `CgMaterial`), the vertex format, the GL state management, the resource registries,
and the platform event hooks — **all of it lives in CrystalGraphics**. CrystalGUI owns only:

- The DOM tree (`UIElement`, `UIContainer`, `UIDocument`)
- The paint context (`CgUiPaintContext`) — submits commands, nothing more
- The UI `.shader` material files (assets consumed by `CgMaterialRegistry`)
- The logic layer (signals, properties, events, layout, input)

---

## Part 1: Tear Down

### 1.1 Completely Deleted

These classes are gone. Do not reference them in new code.

| Class | Replacement |
|---|---|
| `CgUiDrawList` | `CgRenderCommandQueue` (in CrystalGraphics) |
| `CgUiDrawListExecutor` | CrystalGraphics' new `CgUiPass` inside `CgRenderPipeline` |
| `CgUiBatchSlots` | Gone — no batch slots; every draw is a `CgRenderCommand` |
| `ScissorStack` | Scissor fields on `CgRenderCommand` (§2.5); intersection done inline in `CgUiPaintContext` |
| `CgUiRuntime` | Gone — `CgMaterialRegistry` + `CgGraphicsLifecycle` own all resource lifecycles |
| `CgUiRenderAdapter` | Gone — render hook is wired via the `CgPlatform` SPI (§3) |
| `CgUiForgeEventHandler` | Gone — replaced by the CrystalGraphics SPI loader adapters (§3) |

### 1.2 `CgUiPaintContext` — Gutted and Rebuilt

The class name and public API surface (`fillRect`, `drawText`, `drawImage`, `pushScissor`,
`popScissor`, `strokeRect`) are kept for call-site compatibility. Everything inside is replaced.

**Removed internals:**
- `CgUiDrawList` ownership → gone
- `CgUiBatchSlots` ownership → gone
- `ScissorStack` ownership → replaced by inline int stack (§4.1)
- `currentRenderState` / `currentBatchSlot` tracking → gone
- `beginRecord()` / `endRecord()` / `finishFrame()` lifecycle → gone
- `getRuntime()` → gone
- `DrawListTextSink` inner class → replaced by `UiCommandTextSink` (§4.3)
- `reserveQuads()`, `vertex()`, `recordCommand()`, `recordTextCommand()` → gone

**Kept:**
- All high-level draw method signatures (`fillRect`, `drawText`, `drawImage`, etc.)
- Scissor push/pop API
- Text service references (`CgTextRenderer`, `CgFontFamily`, `CgTextRenderContext`)

---

## Part 2: CrystalGraphics Expansions Required

We own CrystalGraphics. We add exactly what is needed to support UI rendering — no workarounds,
no hacks inside CrystalGUI.

### ~~2.1 `CgVertexFormat.UI` — New Vertex Format~~ ADDED

`CgVertexFormat.SPATIAL` (vec3 pos + vec2 uv + vec3 normal, 32 bytes) is for 3D meshes. UI quads
are 2D — normal is meaningless, 32 bytes per vertex is wasteful. Add a dedicated UI format.

**Add to CrystalGraphics `api/vertex/`:**

```
CgVertexFormat.UI
  cg_Position  vec2    location 0   (XY screen coords)
  cg_TexCoord0 vec2    location 1   (UV)
  cg_Color     ubyte4  location 2   (normalized → vec4 0–1 in shader)
  stride: 16 bytes
```

`cg_Color` uses `GL_UNSIGNED_BYTE` + `normalized = true` so shaders receive a `vec4` in 0–1
range without any conversion. Color packing remains `0xRRGGBBAA` integers on the Java side.

`CgMeshBuilder` gets a `unitQuadUI()` factory producing `CgMeshData` in `CgVertexFormat.UI`:
the canonical `[(0,0),(1,0),(1,1),(0,1)]` unit quad with UVs and white vertex color.

### ~~2.2 `#type UI` — Dynamic Vertex Format Attachment~~  VERY EASY TO ADD, JUST ADD CONSTANT TO CgVertexFormat THATS ALL

The `.shader` `#type` tag is now **dynamically attached to a `CgVertexFormat`**. Writing
`#type UI` in a `.shader` file causes the compiler to look up the registered format named
`"UI"` (`CgVertexFormat.UI`) and infer everything from its descriptor — no hardcoded
branching needed in the compiler.

**What the `CgVertexFormat.UI` descriptor declares:**
- Attribute layout: `cg_Position` (loc 0), `cg_TexCoord0` (loc 1), `cg_Color` (loc 2)
- **No instancing** — `CgObjectDataBuffer` SSBO/TBO wiring skipped entirely
- **No shadow/depth auto-gen** — `ShadowCaster` and `Depth` passes never generated
- **No `cg_InstanceId` flat varying** — not injected for non-instanced format types
- Default queue: `"UI"` (index 5000, above `"Overlay"` at 4500)

The `.shader` files in CrystalGUI use `#type UI` (capital, matching the format enum name).

### ~~2.3 `ui_env.glsl`~~ — Eliminated

`ui_env.glsl` is no longer needed. The dynamic vertex format attachment system (§2.2) infers
all compiler behavior from `CgVertexFormat.UI`'s descriptor automatically.

UI shaders that need `u_projection` or other frame-level uniforms declare them directly, or
include a voluntary convenience file:

```glsl
// Optional voluntary include — NOT auto-injected
#include "crystalgui:shaders/lib/ui_common.glsl"
// Declares: uniform mat4 u_projection;
//           #define CG_MATRIX_UI u_projection
//           #define CG_TIME / CG_RESOLUTION convenience aliases
```

CrystalGUI ships `ui_common.glsl` in its resources and all shipped `.shader` files use it.
Third-party `#type UI` shaders may include it or declare their own projection.

### 2.4 `CgUiPass` — UI Render Pass in `CgRenderPipeline`

A new `CgUiPass` is added to `CgRenderPipeline`'s execute sequence.

**Position in the execute sequence (updated full sequence):**
```
sort (opaque front-to-back, transparent back-to-front)
→ frame UBO upload
→ depth prepass
→ opaque forward pass
→ transparent pass
→ [NEW] UI pass   ← CgUiPass executes here
```

**`CgUiPass` behavior:**
1. Saves GL state: `CgGlState.save(PROGRAM, BLEND, DEPTH, CULL, SCISSOR, TEXTURES, VIEWPORT)`
2. Sets `cg_ViewMatrix = identity`, `cg_ProjMatrix = ortho(0, screenW, screenH, 0, -1, 1)` and
   re-uploads the frame UBO
3. Disables depth test globally for the pass (all UI renders over everything)
4. Iterates the UI command queue **in submission order** (no sort — painter's order is preserved)
5. Per-command:
   - If `cmd.scissorEnabled`: `glEnable(GL_SCISSOR_TEST)` + `glScissor(x, y, w, h)`; else disable
   - `cmd.material.bind()` — binds shader + render state from the `.shader` file
   - Upload `u_modelMatrix` via ephemeral binding: `cmd.material.applyProperties(b -> b.mat4("u_modelMatrix", cmd.modelMatrix))`
   - `cmd.mesh.drawDirect()` — non-instanced draw
   - `cmd.material.unbind()`
6. Restores GL state via the `CgGlScope`
7. Re-uploads frame UBO with the original 3D view/proj matrices (restores state for any subsequent passes)

The UI command queue is a separate `CgRenderCommandQueue` — distinct from the 3D geometry queue.
`CgRenderPipeline` exposes `acquireUiCommand()` which pulls from this queue and pre-sets
`renderQueue = RenderQueue.UI`.

### 2.5 Scissor Fields on `CgRenderCommand`

```java
// Added to CgRenderCommand:
public boolean scissorEnabled = false;
public int     scissorX, scissorY, scissorW, scissorH;   // screen pixels, Y-up GL convention

public void setScissor(int x, int y, int w, int h) {
    this.scissorEnabled = true;
    this.scissorX = x; this.scissorY = y;
    this.scissorW = w; this.scissorH = h;
}
public void clearScissor() { this.scissorEnabled = false; }
```

### 2.6 `CgUiService` — UI Hooks in the `CgPlatform` SPI

CrystalGUI's render and input hooks go directly into CrystalGraphics' `platform/` SPI.
**No separate `CguiPlatform` singleton.** `CgPlatform` gains a `ui()` accessor.

**Add to `platform/` SPI:**

```java
// New interface in platform/src/main/java/com/crystalgraphics/platform/
public interface CgUiService {
    /**
     * Called by the loader's render hook each frame, before CgRenderPipeline.execute().
     * Implementations call UIContainer.render() to submit UI CgRenderCommands.
     * partialTick, screenW, screenH are in physical pixels.
     */
    void onUiFrame(float partialTick, int screenW, int screenH);

    /**
     * Called for each mouse move event from the loader.
     * x/y are in logical (GUI-scaled) coordinates.
     */
    void onMouseMove(double x, double y, int modifiers);

    void onMouseButton(double x, double y, int button, boolean pressed, int modifiers);
    void onMouseScroll(double x, double y, double deltaX, double deltaY);

    /**
     * Called for each key event. keyCode is a CgUiKeyCodes constant.
     */
    void onKey(int cgKeyCode, int modifiers, boolean pressed, char typedChar);
}
```

**Add to `CgPlatformService`:**

```java
// In CgPlatformService (the interface all loaders implement):
CgUiService ui();    // may return a no-op default if CrystalGUI is not present
```

**Add to `CgPlatform` dispatch:**

```java
// In CgPlatform:
public static CgUiService ui() { return instance.ui(); }
```

### 2.7 Loader Implementations

Each loader's platform service gets a `ui()` implementation. The default (when CrystalGUI JAR is
absent) returns a `NoopCgUiService`. When CrystalGUI is present, its bootstrap registers a real
implementation via:

```java
// Called from CrystalGUI's @Mod constructor / entrypoint:
CgPlatform.ui().setDelegate(new CrystalGuiUiServiceImpl());
```

Or, more cleanly, CrystalGUI registers itself via a service-loader discovery mechanism already
present in `CgPlatform`. The exact wiring is loader-dependent (Forge `@Mod`, Fabric entrypoint),
but the SPI contract is in `platform/` and is loader-blind.

**mc1710 — `PlatformRegistry1710` wires the render event:**

```java
// In RenderingService1710 (existing class, add UI hook):
@Override
public void onFrameBegin(float partialTick) {
    // existing:
    CgRenderPipeline.getInstance().execute(partialTick);  // runs 3D + UI pass
    // The UI commands are submitted by CgPlatform.ui().onUiFrame() BEFORE execute().
    // execute() drains both the 3D queue and the UI queue.
}
```

The render event hook in `CgRenderHook` (the Mixin on `EntityRenderer.renderWorld`) calls
`CgPlatform.ui().onUiFrame(partialTick, w, h)` **before** `CgPlatform.rendering().onFrameBegin()`,
so UI commands are in the queue when the pipeline executes. Alternatively `CgUiPass` can call
the UI service itself at the top of its execute — the ordering is an implementation detail resolved
during implementation.

**mc1710 input wiring** (`PlatformRegistry1710.onInit()`):

```java
// Existing Forge input event subscription extended:
@SubscribeEvent
public static void onMouseInput(InputEvent.MouseInputEvent e) {
    // translate LWJGL2 → CgUiKeyCodes using LwjglKeyTranslator
    // forward to CgPlatform.ui().onMouse*(...)
    CgUiInputTranslator1710.forwardMouseEvent(CgPlatform.ui());
}

@SubscribeEvent
public static void onKeyInput(InputEvent.KeyInputEvent e) {
    CgUiInputTranslator1710.forwardKeyEvent(CgPlatform.ui());
}
```

**mc1201** — same pattern. Input events via Forge/NeoForge bus or GLFW callback chain (Fabric).
The `CgUiService` interface is loader-blind — the translators live in each loader's adapter.

This means CrystalGUI's entire cross-version integration is zero lines of version-specific code
in CrystalGUI itself. It contributes one `CgUiService` implementation and wires up via
`CgPlatform`. The loaders handle the rest.

---

## Part 3: CrystalGUI — What Remains

### 3.1 `CgUiPaintContext` V4

Thin command submission surface. All GL infrastructure is gone.

```java
public final class CgUiPaintContext {

    private final CgRenderPipeline pipeline;

    // Scissor — inline int stack, no heap objects, no CgScissorRect
    private final int[] scissorStack = new int[64];  // x,y,w,h × 16 depth
    private int scissorDepth = 0;

    // Text services
    private CgTextRenderer textRenderer;
    private CgFontFamily defaultFontFamily;
    private CgTextRenderContext textRenderContext;
    private long textFrame;

    // ── Lifecycle (trivial) ────────────────────────────────────────────────
    public void beginFrame() { scissorDepth = 0; }
    public void endFrame()   { /* no-op */ }

    // ── Scissor ───────────────────────────────────────────────────────────
    public void pushScissor(int x, int y, int w, int h) {
        if (scissorDepth > 0) {
            // intersect with parent
            int base = (scissorDepth - 1) * 4;
            int px = scissorStack[base], py = scissorStack[base+1];
            int pw = scissorStack[base+2], ph = scissorStack[base+3];
            int ix = Math.max(x, px), iy = Math.max(y, py);
            int iw = Math.min(x+w, px+pw) - ix;
            int ih = Math.min(y+h, py+ph) - iy;
            x = ix; y = iy; w = Math.max(0, iw); h = Math.max(0, ih);
        }
        int base = scissorDepth * 4;
        scissorStack[base]=x; scissorStack[base+1]=y;
        scissorStack[base+2]=w; scissorStack[base+3]=h;
        scissorDepth++;
    }

    public void popScissor() {
        if (scissorDepth > 0) scissorDepth--;
    }

    // ── Core primitives ───────────────────────────────────────────────────
    public void fillRect(float x, float y, float w, float h, int rgba) {
        CgRenderCommand cmd = pipeline.acquireUiCommand();
        cmd.mesh = UiMeshCache.unitQuad();
        cmd.material = UiMaterials.solid();
        cmd.modelMatrix.translation(x, y, 0).scale(w, h, 1);
        cmd.custom0.set(unpackRgbaToVec4(rgba));  // tint color → custom0
        applyScissor(cmd);
        pipeline.submit(cmd);
    }

    public void drawImage(CgMaterial material, float x, float y, float w, float h,
                          float u0, float v0, float u1, float v1, int rgba) {
        CgRenderCommand cmd = pipeline.acquireUiCommand();
        cmd.mesh = UiMeshCache.unitQuad();
        cmd.material = material;
        cmd.modelMatrix.translation(x, y, 0).scale(w, h, 1);
        cmd.custom0.set(unpackRgbaToVec4(rgba));
        cmd.custom1.set(u0, v0, u1 - u0, v1 - v0);  // uv offset+scale
        applyScissor(cmd);
        pipeline.submit(cmd);
    }

    public void drawWithMaterial(CgMaterial material, float x, float y, float w, float h, int rgba) {
        CgRenderCommand cmd = pipeline.acquireUiCommand();
        cmd.mesh = UiMeshCache.unitQuad();
        cmd.material = material;
        cmd.modelMatrix.translation(x, y, 0).scale(w, h, 1);
        cmd.custom0.set(unpackRgbaToVec4(rgba));
        applyScissor(cmd);
        pipeline.submit(cmd);
    }

    public void strokeRect(float x, float y, float w, float h, float t, int rgba) {
        fillRect(x,       y,       w, t,           rgba);
        fillRect(x,       y+h-t,   w, t,           rgba);
        fillRect(x,       y+t,     t, h-2*t,       rgba);
        fillRect(x+w-t,   y+t,     t, h-2*t,       rgba);
    }

    public void drawText(String text, float x, float y, int rgba) {
        if (text == null || text.isEmpty()) return;
        UiCommandTextSink sink = new UiCommandTextSink(pipeline, this);
        textRenderer.drawInternalTarget(sink, text, defaultFontFamily,
                                        x, y, rgba, textFrame, textRenderContext, new PoseStack());
        sink.flush();
    }

    // ... overloads (explicit renderer/family/layout)

    // ── Package-private scissor helper (used by UiCommandTextSink) ───────
    void applyScissor(CgRenderCommand cmd) {
        if (scissorDepth > 0) {
            int base = (scissorDepth - 1) * 4;
            cmd.setScissor(scissorStack[base], scissorStack[base+1],
                           scissorStack[base+2], scissorStack[base+3]);
        }
    }
}
```

**No lifecycle manager. No GL calls. No resource ownership.**
Materials are loaded lazily via `UiMaterials` (§3.3) and owned by `CgMaterialRegistry`.
Meshes are loaded lazily via `UiMeshCache` (§3.4) and owned by `CgMeshRegistry`.

### 3.2 `UIContainer` Frame Lifecycle

```java
// Old:
public void render(Matrix4f projection) { ... }

// New:
public void render(int screenW, int screenH) {
    computeLayout(screenW, screenH);
    paintContext.beginFrame();
    drawSubtree(paintContext);   // DOM traversal — submits CgRenderCommands
    paintContext.endFrame();
    // Commands are now in the pipeline's UI queue.
    // CgRenderPipeline.execute() will drain them in CgUiPass.
}
```

No projection matrix parameter. No GL state management. The pipeline owns both.

### 3.3 `UiMaterials` — Lazy Material Cache

Lives in `core/render/` (CrystalGUI). Does not own lifecycle — `CgMaterialRegistry` does.
Hot-reload is handled automatically by `CgMaterialRegistry.get().reloadAll()` on F3+T.

```java
public final class UiMaterials {
    private static CgMaterial solid;
    private static CgMaterial textured;
    private static CgMaterial msdf;

    public static CgMaterial solid() {
        if (solid == null) solid = CgMaterial.load("crystalgui:shaders/ui_solid.shader");
        return solid;
    }
    public static CgMaterial textured() {
        if (textured == null) textured = CgMaterial.load("crystalgui:shaders/ui_textured.shader");
        return textured;
    }
    public static CgMaterial msdf() {
        if (msdf == null) msdf = CgMaterial.load("crystalgui:shaders/ui_msdf.shader");
        return msdf;
    }
    /** Per-draw MSDF instance with atlas texture + pxRange properties. Cheap — GL program is shared. */
    public static CgMaterial msdfInstance(int atlasTextureId, float pxRange) {
        CgMaterial m = CgMaterial.newInstance("crystalgui:shaders/ui_msdf.shader");
        m.applyProperties(b -> b.sampler2D("_MainTex", 0, atlasTextureId).set1f("_PxRange", pxRange));
        return m;
    }
    /** Called by CrystalGUI's reload listener — clears stale cached references. */
    public static void invalidate() { solid = null; textured = null; msdf = null; }
}
```

### 3.4 `UiMeshCache` — Static Quad Mesh

```java
public final class UiMeshCache {
    private static CgMesh unitQuad;

    /** Returns the cached unit quad in CgVertexFormat.UI. Loaded once, owned by CgMeshRegistry. */
    public static CgMesh unitQuad() {
        if (unitQuad == null)
            unitQuad = CgMesh.upload(CgMeshBuilder.unitQuadUI());  // new factory — see §2.1
        return unitQuad;
    }
}
```

### 3.5 `UiCommandTextSink` — Text Via `CgRenderCommand`

Replaces `DrawListTextSink`. Bridges `CgTextQuadSink` (CrystalGraphics text pipeline) into the
`CgRenderCommand` submission path.

The MSDF text renderer emits quads per atlas page. Each atlas page flush = one `CgRenderCommand`.
Quads are accumulated into a dynamic `CgMeshData` (using `CgVertexWriter`) and uploaded as a
transient `CgMesh` per batch.

```java
final class UiCommandTextSink implements CgTextQuadSink {

    private final CgRenderPipeline pipeline;
    private final CgUiPaintContext ctx;
    private CgMaterial currentMaterial;
    private final CgStagingBuffer staging = CgStagingBuffer.create(256);
    private final CgVertexWriter writer   = CgVertexWriter.forBuffer(staging, CgVertexFormat.UI);

    @Override
    public void beginBatch(CgRenderState state, int atlasTextureId, float pxRange) {
        flush();  // emit previous batch if any
        currentMaterial = UiMaterials.msdfInstance(atlasTextureId, pxRange);
    }

    @Override
    public void emitQuad(float x0, float y0, float x1, float y1,
                         float u0, float v0, float u1, float v1,
                         int r, int g, int b, int a) {
        staging.ensureRoomForQuads(1);
        writer.vertex(x0, y0).uv(u0, v0).color(r,g,b,a).endVertex();
        writer.vertex(x1, y0).uv(u1, v0).color(r,g,b,a).endVertex();
        writer.vertex(x1, y1).uv(u1, v1).color(r,g,b,a).endVertex();
        writer.vertex(x0, y1).uv(u0, v1).color(r,g,b,a).endVertex();
    }

    @Override
    public void endText() { flush(); }

    public void flush() {
        if (staging.vertexCount() == 0) return;
        CgMesh batchMesh = CgMesh.upload(
            CgMeshData.fromStagingQuads(staging, CgVertexFormat.UI));
        CgRenderCommand cmd = pipeline.acquireUiCommand();
        cmd.mesh     = batchMesh;
        cmd.material = currentMaterial;
        cmd.modelMatrix.identity();   // text is pre-transformed by the renderer
        ctx.applyScissor(cmd);
        pipeline.submit(cmd);
        staging.reset();
    }
}
```

`CgMeshData.fromStagingQuads()` is a new factory on the CrystalGraphics side that builds an indexed
quad mesh from a `CgStagingBuffer` without going through raw GL. It belongs in `gl/mesh/` in
CrystalGraphics.

---

## Part 4: CrystalGUI `.shader` Materials

All UI shader authoring uses `CgMaterial` + `#type UI`. No `CgRenderState` construction in Java.
Render state (blend, depth, cull) lives exclusively inside the `.shader` file.

### 4.1 Shipped Materials

Location: `src/main/resources/assets/crystalgui/shaders/`

| File | Purpose |
|---|---|
| `ui_solid.shader` | Solid-color filled rectangle (`cg_Color` vertex color + `custom0` tint) |
| `ui_textured.shader` | Textured quad (`_MainTex`, `custom1` UV offset/scale, `custom0` tint) |
| `ui_msdf.shader` | MSDF text atlas (`_MainTex`, `_PxRange`) |
| `ui_image.shader` | Sprite/icon draw — textured with explicit UV rect |

### 4.2 Material Skeletons

```glsl
// ui_solid.shader
#type UI
Queue = "UI"

Pass {
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest OFF
        DepthWrite OFF
        Cull OFF
    }
    struct v2f { vec4 color; };
    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_UI * vec4(cg_Position, 0.0, 1.0);
        o.color = cg_Color;    // vertex color carries per-instance tint via custom0 mapping in env
    }
    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = i.color;
    }
}
```

```glsl
// ui_msdf.shader
#type UI
Queue = "UI"

Properties {
    _MainTex ("Atlas",   sampler2D) = "white"
    _PxRange ("PxRange", float)    = 4.0
}

Pass {
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA    // premultiplied alpha — correct for MSDF
        DepthTest OFF
        DepthWrite OFF
        Cull OFF
    }
    struct v2f { vec2 uv; vec4 color; };
    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_UI * vec4(cg_Position, 0.0, 1.0);
        o.uv    = cg_TexCoord0;
        o.color = cg_Color;
    }
    void fragment(in v2f i, out vec4 fragColor) {
        #include "crystalgraphics:shaders/lib/msdf.glsl"
        float dist  = msdfSample(_MainTex, i.uv);
        float alpha = smoothstep(0.5 - _PxRange/2.0, 0.5 + _PxRange/2.0, dist);
        fragColor   = vec4(i.color.rgb * alpha, i.color.a * alpha);
    }
}
```

### 4.3 Advanced UI Effects — First-Class `.shader` Citizens

Any widget that needs shader-level visual expressiveness authors a `#type UI` material. No Java
GL plumbing. The `drawWithMaterial()` API on `CgUiPaintContext` accepts any `CgMaterial`.

```glsl
// ui_panel_rounded.shader — example widget material
#type UI
#pragma cg_feature SHADOW_ON

Queue = "UI"

Properties {
    _Radius     ("Corner Radius px", float)  = 8.0
    _ShadowBlur ("Shadow Blur px",   float)  = 12.0
    _ShadowColor("Shadow Color",     color)  = (0, 0, 0, 0.5)
    _SizeXY     ("Element WH",       vector) = (100, 100, 0, 0)
}

Pass {
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest OFF
        DepthWrite OFF
        Cull OFF
    }
    struct v2f { vec2 uv; vec4 color; };
    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_UI * vec4(cg_Position, 0.0, 1.0);
        o.uv    = cg_TexCoord0;
        o.color = cg_Color;
    }
    void fragment(in v2f i, out vec4 fragColor) {
        vec2  pixelPos = i.uv * _SizeXY.xy;
        float sdf      = sdRoundBox(pixelPos - _SizeXY.xy * 0.5,
                                    _SizeXY.xy * 0.5 - _Radius, _Radius);
        #ifdef SHADOW_ON
            float s = smoothstep(_ShadowBlur, 0.0, sdf + _ShadowBlur * 0.5);
            fragColor = mix(vec4(0.0), _ShadowColor, s);
        #else
            float a = smoothstep(0.5, -0.5, sdf);
            fragColor = i.color * vec4(1.0, 1.0, 1.0, a);
        #endif
    }
}
```

Usage from a `UIElement.draw()`:

```java
@Override
public void draw(CgUiPaintContext ctx) {
    roundedMaterial.applyProperties(b ->
        b.set1f("_Radius", cornerRadius)
         .vec4("_SizeXY", bounds.width, bounds.height, 0, 0));
    if (shadow) roundedMaterial.enableKeyword("SHADOW_ON");
    ctx.drawWithMaterial(roundedMaterial, bounds.x, bounds.y, bounds.width, bounds.height, tintColor);
}
```

Keyword variants (`SHADOW_ON`) are compiled and cached automatically by `CgMaterial`.
`roundedMaterial` is a `CgMaterial.load(...)` instance held by the element — lifecycle owned
by `CgMaterialRegistry`, hot-reloaded on F3+T automatically.

---

## Part 5: Module Structure

### 5.1 `core/` + `mc1710/` + `mc1201/` — Mirrors CrystalGraphics

CrystalGUI restructures from a single flat module into the same three-subproject layout used by
CrystalGraphics. This is a **prerequisite** for all rendering and SPI work — it enforces the
platform boundary at the build level.

```
CrystalGUI/
├── core/          ← Platform-agnostic UI engine (Java 9+, zero MC/LWJGL imports)
├── mc1710/        ← Forge 1.7.10 adapter (Java 8, LWJGL2, input wiring, bootstrap)
└── mc1201/        ← Forge/Fabric 1.20.1 adapter
```

**Dependency graph:**

| Subproject | Depends On |
|---|---|
| `core/` | CrystalGraphics `core/`, CrystalGraphics `platform/` |
| `mc1710/` | CrystalGUI `core/`, CrystalGraphics `mc1710/` |
| `mc1201/` | CrystalGUI `core/`, CrystalGraphics `mc1201/` |

`core/` has **zero** Minecraft, Forge, or LWJGL imports. The boundary is compiler-enforced.

### 5.2 Platform SPI — `CgUiService` in CrystalGraphics `platform/`

CrystalGUI's engine contract (`CgUiService`) lives in **CrystalGraphics `platform/`**, not in
CrystalGUI itself. This follows the same pattern as every other CG service (`CgRenderingService`,
`CgLifecycleService`, etc.).

`CgPlatformService` gains `CgUiService ui()` as a default method returning
`NoopCgUiService.INSTANCE`, so all existing loader implementations compile unchanged.

`CgPlatform` gains:
- `CgPlatform.ui()` — returns the registered `CgUiService`
- `CgPlatform.registerUiService(CgUiService)` — called once by CrystalGUI's bootstrap

### 5.3 Bootstrap Flow

```
[CG mc1710 preinit]
  CgPlatform.register(new PlatformService1710())
  → CgPlatform.ui() returns NoopCgUiService

[CGUI mc1710 init — CrystalGUI's @Mod]
  new CguiPlatformService1710().bootstrap()
    a. Creates CrystalGuiUiServiceImpl (lives in core/)
    b. CgPlatform.registerUiService(impl)
    c. Subscribes Forge @SubscribeEvent input events
    d. Translates LWJGL2 events via LwjglKeyTranslator → CgPlatform.ui().*

[Each frame — CgUiPass.execute()]
  CgPlatform.ui().onUiFrame(partialTick, w, h)
    → CrystalGuiUiServiceImpl iterates UIContainers → container.render(w, h)
    → UIContainer submits CgRenderCommands into pipeline's UI queue
  → CgUiPass iterates UI queue in painter's order → drawDirect()
```

`CguiPlatformService1710` is intentionally thin — it only bootstraps and wires input.
All rendering flows through CrystalGraphics' `CgUiPass`. No rendering code in `mc1710/`.

### 5.4 Source Distribution

| Concern | Subproject |
|---|---|
| DOM tree (`UIElement`, `UIContainer`, `UIDocument`) | `core/` |
| Paint context (`CgUiPaintContext`) | `core/` |
| Material cache (`UiMaterials`, `UiMeshCache`) | `core/` |
| Text sink (`UiCommandTextSink`) | `core/` |
| `CrystalGuiUiServiceImpl` | `core/` |
| Signal/slot, property, event, input, layout | `core/` |
| `LwjglKeyTranslator` | `mc1710/` |
| `CgUiInputTranslator1710` (Forge events → `CgPlatform.ui()`) | `mc1710/` |
| `CguiPlatformService1710` (bootstrap) | `mc1710/` |
| GLFW input translation | `mc1201/` |
| `CguiPlatformService1201` | `mc1201/` |

---

## Part 6: What is Preserved (Unchanged)  ⟨STRUCK⟩

> **This section has been removed rather than corrected.** It listed roughly fifteen classes and
> packages as "architecturally sound, keep as-is" — `core/event/UiEventDispatcher`,
> `core/input/UiInputManager` + `FocusManager`, `core/layout/LayoutContext`, `core/geometry/UiRect`,
> `core/tree/TreeTraversal`, `ui/UIContainer`, `ui/UIDocument`, `ui/elements/UiPanel`/`UiButton`/
> `UiLabel`/`UiTextbox`, `mc/LwjglKeyTranslator` — **none of which have ever existed in this
> repository.** It reads as a factual inventory and is not one; leaving it in place with a caveat
> would have kept it quotable.
>
> The real equivalents, all documented in `../AGENTS.md`: `ui/event/` (the three-phase `UIEvent`
> hierarchy), `ui/input/UIInputHandler` (which merges what this section called `UiInputManager` +
> `FocusManager` — there is no such split), `ui/tree/UITreeTraversal`, and no `UIContainer` /
> `UIDocument` at all: `UIElement` is both leaf and container by design. The widgets are `Button`,
> `UIText`, `TextField` and nine others — see `CGUI_WIDGETS.md`.
>
> What this section was *right* about, and what has genuinely survived the whole overhaul untouched:
> **`core/signal/` and `core/property/`**.

---

## Part 7: GL Debug Harness Scene

A `cgui-test` scene in `gl-debug-harness/` is **required** before V4 is considered complete.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-test"
# Output: harness-output/cgui-test/frame.png
```

The scene:
1. Constructs a `UIContainer` with `UiPanel`, `UiButton`, `UiLabel`, `UiTextbox`
2. Calls `container.render(800, 600)` — submits UI `CgRenderCommand`s into the pipeline
3. Calls `CgRenderPipeline.getInstance().execute(0f)` — runs `CgUiPass`, draws to framebuffer
4. Captures PNG via `ScreenshotUtil`
5. Assertions: no GL errors, no broken `CgMaterial` compiles, frame is not blank

This decouples CrystalGUI renderer correctness from Minecraft entirely, consistent with
CrystalGraphics' own harness model.

---

## Part 8: Summary Execution Order

### Phase 0: Module Restructuring (prerequisite)

0a. Create `core/`, `mc1710/`, `mc1201/` Gradle subprojects
0b. Migrate existing source into `core/`; move MC adapters to `mc1710/src/`
0c. Wire Gradle deps: `core/` → CG `core/` + CG `platform/`; `mc1710/` → CGUI `core/` + CG `mc1710/`

### Phase 1: CrystalGraphics Additions (can begin after Phase 0)

1. `CgVertexFormat.UI` + dynamic `#type` attachment in `api/vertex/`
2. `CgMeshBuilder.unitQuadUI()` in `gl/mesh/`
3. Scissor fields on `CgRenderCommand` + `reset()` fix in `api/render/`
4. `CgUiService` interface + `NoopCgUiService` + `CgPlatform.registerUiService()` + `CgPlatform.ui()` in `platform/`
5. `CgAssetReloader` calls `CgPlatform.ui().onReload()`
6. `CgMaterial.applyEphemeral()` API
7. Unsorted UI command list in `CgRenderPipeline` + `acquireUiCommand()`
8. `CgUiPass` implementation
9. Loader wiring: mc1710 + mc1201 input events → `CgPlatform.ui()`

### Phase 2: CrystalGUI `core/` Rebuild (depends on Phase 1)

10. Delete: `CgUiDrawList`, `CgUiDrawListExecutor`, `CgUiBatchSlots`, `ScissorStack`, `CgUiRuntime`, `CgUiRenderAdapter`, `CgUiForgeEventHandler`
11. Write `.shader` files: `ui_solid`, `ui_textured`, `ui_msdf`, `ui_bitmap`, `ui_image` + `lib/ui_common.glsl`
12. Implement `UiMaterials`, `UiMeshCache`
13. Implement `UiCommandTextSink`
14. Rebuild `CgUiPaintContext` as command submission surface
15. Update `UIContainer.render()` to `render(int screenW, int screenH)`
16. Implement `CrystalGuiUiServiceImpl`

### Phase 3: MC Adapter Wiring (depends on Phase 1 + 2)

17. `CguiPlatformService1710` — bootstrap + `CgPlatform.registerUiService()` call
18. `CgUiInputTranslator1710` — Forge events → `CgPlatform.ui().*`
19. `CguiPlatformService1201` — same for 1.20.1

### Phase 4: Validation

20. Add `cgui-test` harness scene — end-to-end validation without Minecraft

---

*Document version: V4.1 — `#type CgVertexFormat` dynamic attachment, `ui_env.glsl` eliminated, module restructure added.*

*Status: **HISTORICAL**. The ownership boundary this document argues for still governs the project; the
render-queue design it specifies was abandoned for immediate-mode. See the banner at the top for the
per-part breakdown and the current-state documents.*
