package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgGlSlot;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.lifecycle.CgUiLifecycle;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * True immediate-mode 2D paint context for CrystalGUI's box-model layer.
 *
 * <p><b>A true process-wide singleton</b> — one paint context for the whole client, accessed via
 * {@link #getInstance()}, lazily constructed on first use. This reflects reality, not an
 * aspiration: nothing in this codebase ever constructs more than one {@link com.crystalgui.ui.UIWindow}
 * at a time (no split-screen/multi-window support exists anywhere), and {@link #beginFrame} is not
 * reentrant (throws if called without a matching {@link #endFrame}) — a second concurrent
 * {@code UIWindow} painting at the "same time" could not actually be served by a shared instance.
 * Lazy construction matters beyond avoiding needless work: it means simply constructing/using a
 * {@code UIWindow} for pure layout/tree logic (no {@link #beginFrame}/paint call) never eagerly
 * triggers GL material/font loads — a real, if small, step toward running CrystalGUI's tree/layout
 * logic headlessly (e.g. server-side) without a GL context.</p>
 *
 * <p>Being static, the instance does <em>not</em> die with the GL context that built it, so whoever
 * owns that lifecycle must call {@link #destroy()} on context destruction — see that method for what
 * is and isn't freed, and why the distinction matters.</p>
 *
 * <p>Wraps frame lifecycle in {@link CgGlScope} for GL state isolation and saves/restores
 * {@link CgFrameData} so UI rendering does not corrupt the 3D pipeline state.</p>
 *
 * <p>Integrates {@link ScissorStack} for nested clip regions — GL scissor is applied
 * at draw time when a scissor rect is active.</p>
 *
 * <p><b>Frame lifecycle</b> — call {@link #beginFrame} once before walking the UI tree,
 * then {@link #endFrame} once after. Every {@code fillRect}/{@code drawImage} call in between draws immediately;
 * there is no recording phase and nothing to flush. This is intentional for now, not merely unoptimized. </p>
 */
public final class CgUiPaintContext {
    /** {@code namespace:path} resolved through {@link CgIO}'s waterfall (filesystem override →
     * MC resource manager → classpath) — works identically in-game and in the harness/tests,
     * unlike the hardcoded absolute Windows path this replaced ({@code C:\WINDOWS\Fonts\arial.ttf},
     * which only ever worked on the original dev's machine). Reuses a font CrystalGraphics already
     * bundles rather than shipping a duplicate. */
    private static final String DEFAULT_FONT_ASSET = "crystalgraphics:IBMPlexSans-Regular.ttf";

    static {
        // Self-wire CrystalGUI's lifecycle the moment this class comes into play.
        //
        // Touching CgUiPaintContext at all is the event "CrystalGUI is about to own things that must
        // be released on context loss" — so binding teardown to class initialization makes the two
        // impossible to get out of step. The alternative, an explicit CgUiLifecycle.register() call
        // in every loader and harness scene, is a step that can be forgotten, and forgetting it fails
        // silently: the next GL context draws from freed materials and atlases rather than throwing.
        //
        // Costs nothing where it must cost nothing. A dedicated server never touches this class, so
        // it never registers and never loads CrystalGraphics — and could not have anyway, since
        // LAYER_FORMAT below already makes this class unloadable without CrystalGraphics present.
        CgUiLifecycle.register();
    }

    private static CgUiPaintContext instance;

    /** Lazily constructs the singleton on first use. See the class doc for why this must stay lazy. */
    public static CgUiPaintContext getInstance() {
        if (instance == null) instance = new CgUiPaintContext();
        return instance;
    }

    private final CgMaterial boxModelMaterial;

    /**
     * Dedicated material for {@link #blitLayer}, distinct from {@link #boxModelMaterial}.
     * A visual-layer FBO is always cleared fully transparent before anything paints into it, so
     * at every partially-covered pixel its stored color ends up premultiplied by its own alpha —
     * compositing that back onto the screen needs premultiplied blend (@{@code srcRGB=ONE}), not
     * {@link #boxModelMaterial}'s straight-alpha blend (which is correct for its other, much more
     * common use: painting straight-alpha colors directly onto an already-opaque destination).
     */
    private final CgMaterial layerBlitMaterial;

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    @Getter
    private final CgTexture2D whitePixel;

    @Getter
    private final PoseStack poseStack;

    /**
     * Basic wrapper over {@link com.crystalgraphics.gl.render.CgQuadRenderer}.
     * Works only for quads.
     * <br>
     * <b>Currently intended for immediate flushing, despite it being inefficient and going against the idea of "Batching"</b>
     */
    @Getter
    private final CgUiRenderer renderer;

    // ── Text ─────────────────────────────────────────────────────────────────
    /**
     * Owned independently of {@link #renderer}, though both now reach the GPU the same way:
     * CrystalGraphics' {@code CgTextRenderer} batches glyphs through its own
     * {@code CgQuadRenderer}, exactly as {@link CgUiRenderer} does for box-model quads.
     *
     * <p>They stay separate instances rather than sharing one because each batches across its own
     * {@code begin()}/{@code end()} window against its own material — only the CPU-side accumulation
     * buffer is per-instance state, while the unit-quad mesh and the instance SSBO/TBO behind them
     * are class-wide and shared regardless.</p>
     */
    @Getter
    private final CgTextRenderer textRenderer;

    // ── GL state isolation ──────────────────────────────────────────────────
    private CgGlScope glScope;

    // ── Visual layers (offscreen FBO compositing) ───────────────────────────
    // Screen-sized, not element-sized: draws inside a layer use the same absolute screen
    // coordinates (runtimeCache.getX()/getY()) as the normal path, so nothing needs translating —
    // matches LDLib2's own "off-target spans the full window" approach for the same reason.
    private int screenWidth, screenHeight;
    private final List<CgFrameBuffer> layerFboPool = new ArrayList<>();
    /** One saved frame per nested {@link #beginLayerFbo}/{@link #endLayerFbo} pair. */
    private final Deque<LayerFrame> layerStack = new ArrayDeque<>();
    private static final CgFrameBufferFormat LAYER_FORMAT =
            CgFrameBufferFormat.builder("cgui_layer").color(0, CgTextureType.RGBA8).build();

    private record LayerFrame(CgFrameBuffer fbo, CgGlScope glScope, Matrix4f savedProjMatrix,
                               int savedViewportW, int savedViewportH) {
    }


    // ── Scissor ─────────────────────────────────────────────────────────────
    @Getter
    private final ScissorStack scissorStack = new ScissorStack();

    // ── State elision ───────────────────────────────────────────────────────
    @Getter
    private CgTexture2D currentTexture;
    @Getter
    private boolean frameActive;

    // ── Material switching ──────────────────────────────────────────────────
    @Getter
    private CgMaterial currentMaterial;

    /**
     * Current layer-compositing opacity (distinct from {@link #color}'s tint — see
     * {@code gui_quad.shader}'s doc comment). Every UI-facing material declares a
     * {@code _LayerOpacity} property; {@link #withMaterial} keeps whichever material is
     * currently bound in sync with this value on every switch.
     */
    @Getter
    private float layerOpacity = 1f;

    @Getter
    private final CgFont font = loadDefaultFont();

    @Getter @Setter
    private int color = 0xFFFFFFFF;

    private CgUiPaintContext() {
        this.poseStack = new PoseStack();
        this.renderer = new CgUiRenderer(this);
        this.boxModelMaterial = CgMaterial.load("crystalgui:shaders/gui_quad.shader");
        this.layerBlitMaterial = CgMaterial.load("crystalgui:shaders/gui_layer_blit.shader");
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
        this.textRenderer = CgTextRenderer.createManualSized().poseStack(this.poseStack)
                                          .restoreStateWith(() -> {
                renderer.useMaterial(boxModelMaterial);
                currentTexture = null;
            });
    }

    private static CgFont loadDefaultFont() {
        InputStream in = CgIO.openStream(DEFAULT_FONT_ASSET);
        if (in == null) {
            throw new IllegalStateException("CgUiPaintContext: default font asset not found: " + DEFAULT_FONT_ASSET);
        }
        try {
            byte[] data = readAllBytes(in);
            return CgFont.load(data, DEFAULT_FONT_ASSET, CgFontStyle.REGULAR, 16);
        } catch (IOException e) {
            throw new IllegalStateException("CgUiPaintContext: failed to read default font asset: " + DEFAULT_FONT_ASSET, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing meaningful to do — the font either loaded successfully above or we're
                // already throwing; a close failure on a read-only stream isn't actionable.
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public int mouseX, mouseY;

    // ── Frame lifecycle ─────────────────────────────────────────────────────

    /**
     * Saves GL state via {@link CgGlScope}, saves {@link CgFrameData}, sets up
     * an orthographic screen-space projection, and binds the shared box-model
     * material. Call once per frame before {@code rootElement.drawSubtree(ctx)}.
     */
    public void beginFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("beginFrame() called without matching endFrame()");
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        // Save GL state before UI rendering
        glScope = CgGlState.save(
                CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
                CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.VIEWPORT);

        // Save CgFrameData
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        // Set ortho projection for UI
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        fd.viewportW = screenWidth;
        fd.viewportH = screenHeight;
        pipeline.prepareFrame();

        // Text: projection + atlas LRU frame tick. No beginBatch() here — drawText()
        // deliberately stays standalone-per-call, see docs/CRYSTALGUI_TEXT_RENDERING_PLAN.md §2.3.
        textRenderer.context().updateOrtho(screenWidth, screenHeight);

        poseStack.pushPose();
        renderer.begin();
        renderer.useMaterial(boxModelMaterial);
        currentMaterial = boxModelMaterial;
        currentTexture = null;
        scissorStack.reset();
        frameActive = true; // must be set before warmUp() — quad() requires an active frame

        if (!warmedUp) {
            warmUp();
            warmedUp = true;
        }
    }

    private boolean warmedUp = false;

    /**
     * Eagerly creates (and cold-draws into) the layer-FBO pool slots a masked element with children
     * commonly needs, once, on the first frame — see {@link #warmUpLayerFbo} for why. Depth 0 covers
     * the element's own background layer; depth 1 covers its children's nested layer; depth 2
     * covers the transient mask-shape FBO. Deeper nesting (an element whose child is *also* masked)
     * isn't pre-warmed here — it's covered automatically by {@link #acquireLayerFbo}'s own per-slot
     * warm-up whenever that depth is first reached, so this is a head start for the common case, not
     * the load-bearing part of the fix.
     */
    private void warmUp() {
        acquireLayerFbo(0);
        acquireLayerFbo(1);
        acquireLayerFbo(2);
    }

    /**
     * Unbinds the box-model material, restores {@link CgFrameData}, and restores
     * GL state via the saved {@link CgGlScope}. Call once after the whole UI tree
     * has painted.
     */
    public void endFrame() {
        if (!frameActive) return;

        // No explicit unbind: CgQuadRenderer owns bind/unbind (see CgUiRenderer#useMaterial), and the
        // PROGRAM slot saved by beginFrame's CgGlScope restores whatever program was bound before the
        // UI painted anyway — which is the restoration that actually matters to the 3D pipeline.
        currentMaterial = null;
        currentTexture = null;
        frameActive = false;
        renderer.end();

        poseStack.popPose();

        if (!poseStack.clear()) throw new IllegalStateException("Unpopped stack(s) in UI frame");

        // Restore CgFrameData
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        // Restore GL state
        if (glScope != null) {
            glScope.close();
            glScope = null;
        }
    }

    // ── Public draw API ─────────────────────────────────────────────────────

    /** Solid-color fill, tint already includes opacity. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        bindTexture(whitePixel);
        quad().at(x, y).size(width, height).color(argb).submit();
        flush();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture2D texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTexture(texture);
        // A failed load resolves to the fallback checkerboard, which has no meaningful sub-rect — a
        // caller's UV crop would sample an arbitrary corner of it. Full-range UVs keep a missing
        // texture looking like the recognisable "missing texture" it is, at whatever size it was
        // asked to draw. Was previously enforced centrally in submitQuad; it now lives at the two
        // sites that can actually be handed a fallback (here and CgUiSprite).
        boolean missing = texture == CgTextureManager.get().getFallback();
        CgQuadRenderer.Quad q = quad().at(x, y).size(width, height).color(argb);
        (missing ? q : q.uv(u0, v0, u1, v1)).submit();
        flush();
    }
    
    /**
     * Returns the context's text renderer object.
     *
     * <p>Its owned pose stack was wired to this context's own {@link #getPoseStack()} in
     * the constructor, so {@link CgTextRenderer.Draw#poseStack(PoseStack)}
     * may be omitted entirely — a draw with no explicit pose falls back to it.</p>
     *
     * <pre>{@code
     * // One-shot: build and submit in the same expression. No .pose(...) call needed —
     * // falls back to this context's own poseStack automatically.
     * ctx.text().draw()
     *         .text("Hello world")
     *         .font(myFont)
     *         .at(20.0f, 40.0f)
     *         .color(0xFFFFFFFF)
     *         .submit();
     *
     * // Retained: held across frames (e.g. a widget's cached label draw), only the
     * // text changes each tick. Independent of draw()'s shared immediate-mode scratch instance.
     * CgTextRenderer.Draw labelDraw = ctx.text().retainedDraw()
     *         .font(myFont).at(20.0f, 40.0f).color(0xFFFFFFFF);
     * // ... later, once per frame:
     * labelDraw.text(currentLabel).submit();
     *
     * // Manually-batched: several draws sharing one upload+draw. submit() returns the
     * // owning CgTextRenderer, so the last call in the batch can chain into endBatch().
     * ctx.text().beginBatch();
     * ctx.text().draw().text(line1).font(myFont).at(20.0f, 20.0f).color(0xFFFFFFFF).submit();
     * ctx.text().draw().text(line2).font(myFont).at(20.0f, 40.0f).color(0xFFFFFFFF)
     *         .submit().endBatch();
     * }</pre>
     */
    public CgTextRenderer text() {
        return textRenderer;
    }

    public void bindTexture(CgTexture2D texture) {
        if (texture == currentTexture) return;
        texture.bind(0);
        currentTexture = texture;
    }

    /**
     * Starts a fluent quad, already carrying this context's {@code PoseStack} transform.
     *
     * <p>Build and {@code submit()} in one expression — the returned instance is
     * {@code CgQuadRenderer}'s shared per-renderer scratch object, so holding it past the
     * {@code submit()} is not safe (the next {@code quad()} resets and reuses it).</p>
     *
     * <pre>{@code
     * ctx.bindTexture(tex);
     * ctx.quad().at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();
     * ctx.flush();   // submit() only queues — this is what draws
     * }</pre>
     *
     * <p><b>Never call {@code .pose(...)} on the result.</b> {@link CgUiRenderer#quad()} has already
     * applied the active pose, and overwriting it drops the {@code uiScale}/element transform that
     * every logical-space coordinate in this API assumes. Defaults are the full UV rect and opaque
     * white, so a solid fill needs neither.</p>
     */
    public CgQuadRenderer.Quad quad() {
        return renderer.quad();
    }

    /**
     * Flushes renderer queue and draws all submitted quads
     */
    public void flush() {
        renderer.flush();
    }

    /**
     * Pushes a new clip rect, intersected with whatever scissor is already active, and enables
     * {@code GL_SCISSOR_TEST} against it. Pair with {@link #popScissor()}.
     *
     * <p>{@code x}/{@code y}/{@code w}/{@code h} are in the same logical, top-left-origin,
     * pre-{@code uiScale} layout space as everything else this context draws (e.g.
     * {@link #fillRect}) — <b>not</b> physical screen pixels. This method converts internally
     * via the current {@link #poseStack} transform before touching {@link ScissorStack}, since
     * {@code glScissor} needs real physical framebuffer pixels in GL's bottom-left-origin
     * convention, and the inverted-ortho projection {@link #beginFrame} sets up for vertex
     * rendering has no effect on the separate scissor-test raster stage.</p>
     */
    public void pushScissor(float x, float y, float w, float h) {
        flush();
        Matrix4f m = poseStack.last().pose();
        float physX0 = m.m00() * x + m.m10() * y + m.m30();
        float physY0 = m.m01() * x + m.m11() * y + m.m31();
        float physX1 = m.m00() * (x + w) + m.m10() * (y + h) + m.m30();
        float physY1 = m.m01() * (x + w) + m.m11() * (y + h) + m.m31();


        int physX = (int) Math.floor(Math.min(physX0, physX1));
        int physY = (int) Math.floor(Math.min(physY0, physY1));
        int physW = (int) Math.ceil(Math.max(physX0, physX1)) - physX;
        int physH = (int) Math.ceil(Math.max(physY0, physY1)) - physY;
        // Top-left-origin logical space -> GL's bottom-left-origin glScissor space.
        int glY = screenHeight - (physY + physH);
        scissorStack.pushScissor(physX, glY, Math.max(0, physW), Math.max(0, physH));
        scissorStack.applyScissorIfNeeded();
    }

    /**
     * Pops the topmost clip rect, restoring the parent scissor (if any) or disabling
     * {@code GL_SCISSOR_TEST} entirely once the stack is empty.
     */
    public void popScissor() {
        flush();
        scissorStack.popScissor();
        if (scissorStack.hasScissor()) {
            scissorStack.applyScissorIfNeeded();
        } else {
            scissorStack.clearScissorIfNeeded();
        }
    }

    /** Nesting depth of the clip stack; 0 when nothing is clipped. Exposed so the top-layer paint
     * pass can assert the main tree left the stack balanced before it starts painting unclipped. */
    public int getScissorDepth() {
        return scissorStack.depth();
    }

    /**
     * Switches to {@code material} for the duration of {@code drawBody}, then eagerly restores
     * {@link #boxModelMaterial}. Used for drawables (e.g. an SDF rounded rect) that need their own
     * shader/program rather than the shared box-model one.
     *
     * <p>{@code material.unbind()} restores GL state flags but does NOT rebind whatever program was
     * previously active — so the switch back to {@link #boxModelMaterial} is explicit here, not
     * automatic. {@link #currentTexture} is invalidated on both sides of the switch since a different
     * material may wire its sampler differently even for what looks like "the same" texture reference.</p>
     *
     * <p><b>{@code bind()} must run AFTER {@code drawBody}, not before.</b> {@code applyProperties(...)}
     * is CPU-only — it marks a dirty flag but doesn't upload anything; the GPU-side upload only
     * happens inside {@code bind()}'s own dirty-check. {@code drawBody} (e.g. {@code CgUiRoundedRect}'s
     * lambda) is exactly where the caller sets its own per-instance properties (corner radius, border,
     * fill, ...) — binding before that ran would upload whatever was dirty from the *previous* draw
     * call on this material, one draw stale. Invisible for a single static drawable re-drawing the
     * same values every frame; badly broken for two different instances of the same drawable
     * alternating every frame (e.g. a cross-fade), where each draw would render with the other's
     * properties. `bind()` is safe to call unconditionally here (not just on a material switch) —
     * its own `ProgramKey`/`wiredPrograms` caching makes a repeat bind of an already-current variant
     * just a dirty re-check, not a recompile.</p>
     */
    public void withMaterial(CgMaterial material, Runnable drawBody) {
        flush();
        currentMaterial = material;
        currentTexture = null;
        material.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));

        // useMaterial() is called TWICE around drawBody on purpose, and both calls are load-bearing.
        //
        // The first satisfies CgQuadRenderer's precondition — Quad.submit() throws unless a material
        // is active, since that call is the renderer's only confirmation its instance buffer is
        // attached to the bound program — and auto-flushes anything still queued for the previous
        // material, whose shader those instances were computed against.
        //
        // The second preserves the bind-AFTER-drawBody invariant documented above. drawBody sets its
        // per-instance properties (corner radii, border, fill) via applyProperties, which is CPU-only
        // — the GPU upload happens inside bind(). useMaterial() rebinds on every call even for the
        // same instance, so this second call is what actually uploads what drawBody just set. Without
        // it the draw would run with whatever was dirty from the *previous* use of this material:
        // invisible for a static drawable repeating identical values, badly wrong for two instances
        // alternating every frame, which is exactly the cross-fade bug this ordering was written to fix.
        renderer.useMaterial(material);
        drawBody.run();
        renderer.useMaterial(material);
        flush();

        // Properties before the bind here, so the restored box-model material uploads the current
        // layer opacity on this bind rather than trailing a frame behind.
        boxModelMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        renderer.useMaterial(boxModelMaterial);
        currentMaterial = boxModelMaterial;
        currentTexture = null;
    }

    /**
     * Runs {@code drawBody} with the layer-compositing opacity temporarily set to {@code opacity},
     * then restores the previous value. Syncs the new value into {@link #currentMaterial} immediately
     * (covering draws that call {@code fillRect}/{@code drawImage} directly against the already-bound
     * material without going through {@link #withMaterial}) — any nested {@link #withMaterial} call
     * inside {@code drawBody} re-syncs it again on its own switches, so this composes correctly with
     * drawables that own their own material (e.g. an SDF rounded rect).
     *
     * <p>Used by {@code CgUiCrossFade} to draw its "to" drawable at a fractional opacity without
     * touching that drawable's own ambient tint/alpha.</p>
     */
    public void withLayerOpacity(float opacity, Runnable drawBody) {
        flush();
        float previous = layerOpacity;
        // Compose with the enclosing scope rather than overwriting it — a retargeted texture-valued
        // transition can nest a drawable (e.g. a CgUiCrossFade or mixed-fill CgUiRoundedRect) inside
        // another one; an absolute overwrite here would let the innermost call silently discard
        // every enclosing opacity, leaving the outer transition's own progress with zero visual
        // effect on whatever it wraps.
        layerOpacity = previous * opacity;
        currentMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        renderer.useMaterial(currentMaterial);
        drawBody.run();
        flush();
        layerOpacity = previous;
        currentMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        renderer.useMaterial(currentMaterial);
    }

    // ── Visual layers ────────────────────────────────────────────────────────

    /** Acquires (creating on first use) the pooled screen-sized layer FBO for the given nesting depth. */
    private CgFrameBuffer acquireLayerFbo(int depth) {
        while (layerFboPool.size() <= depth) {
            String name = "cgui_layer_" + layerFboPool.size();
            CgFrameBuffer newFbo = CgFrameBuffer.createOwned(name, Math.max(1, screenWidth), Math.max(1, screenHeight), LAYER_FORMAT);
            layerFboPool.add(newFbo);
            warmUpLayerFbo(newFbo);
        }
        CgFrameBuffer fbo = layerFboPool.get(depth);
        if (fbo.getWidth() != screenWidth || fbo.getHeight() != screenHeight) {
            fbo.resize(Math.max(1, screenWidth), Math.max(1, screenHeight));
        }
        return fbo;
    }

    /**
     * Cold-draws a fully transparent, immediately-discarded quad into a freshly-created layer FBO
     * via {@link #layerBlitMaterial}, once, right when that FBO is created.
     *
     * <p>Root cause this works around: the very first masked/opacity element painted anywhere in
     * the process's life is also the first point {@link #layerBlitMaterial} (compiled lazily, on
     * its own first {@code bind()}) ever draws into a brand-new, never-drawn-to FBO — on at least
     * one NVIDIA driver, that specific "cold program's first draw into a cold FBO, same frame"
     * coincidence has been observed to silently produce nothing (verified via frame-by-frame
     * capture: the masked content is simply missing on frame 1, then permanently correct from frame
     * 2 onward). Forcing that same coincidence to happen here — right when the slot is created,
     * against throwaway content nobody reads — means whatever real content later reuses this exact
     * pool slot never hits a truly first-ever draw again, on any frame.</p>
     *
     * <p>Self-scaling by construction: this runs from {@link #acquireLayerFbo} itself, so it covers
     * every nesting depth the UI tree ever actually reaches, not just whatever depth
     * {@link #warmUp()} eagerly primes at startup.</p>
     */
    private void warmUpLayerFbo(CgFrameBuffer fbo) {
        flush();
        CgMaterial previousMaterial = currentMaterial;
        CgTexture2D previousTexture = currentTexture;
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND)) {
            fbo.bind();
            CgGL.glViewport(0, 0, fbo.getWidth(), fbo.getHeight());
            fbo.clearColor(0f, 0f, 0f, 0f);
            withMaterial(layerBlitMaterial, () -> {
                bindTexture(whitePixel);
                quad().at(0, 0).size(fbo.getWidth(), fbo.getHeight()).color(0x0).submit();
                flush();
            });
        }
        // CgGlScope already restored the real GL FBO/program/texture bindings to whatever was active
        // before — resync the CPU-side bookkeeping fields withMaterial() left pointing at
        // boxModelMaterial back to match, rather than leaving them stale relative to the real GL state.
        currentMaterial = previousMaterial;
        currentTexture = previousTexture;
    }

    /**
     * Pushes a new screen-sized offscreen target and redirects subsequent drawing into it —
     * cleared fully transparent, same screen-space ortho convention {@link #beginFrame} sets up
     * for the real screen (just retargeted), so draws made while a layer is active use the exact
     * same absolute coordinates they always do. Nests correctly (a layered element containing
     * another layered element) via a small per-depth FBO pool. Pair with {@link #endLayerFbo}.
     *
     * @return the acquired FBO, for the caller to composite/blit once painting into it is done
     */
    public CgFrameBuffer beginLayerFbo() {
        flush();
        CgFrameBuffer fbo = acquireLayerFbo(layerStack.size());
        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        layerStack.push(new LayerFrame(fbo, CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT),
                new Matrix4f(fd.projMatrix), fd.viewportW, fd.viewportH));

        fbo.bind();
        CgGL.glViewport(0, 0, fbo.getWidth(), fbo.getHeight());
        fbo.clearColor(0f, 0f, 0f, 0f);

        fd.projMatrix.identity().ortho(0, fbo.getWidth(), fbo.getHeight(), 0, -1, 1);
        fd.viewportW = fbo.getWidth();
        fd.viewportH = fbo.getHeight();
        CgRenderPipeline.getInstance().prepareFrame();
        currentTexture = null;
        return fbo;
    }

    /** Pops the innermost {@link #beginLayerFbo}, restoring the saved GL state and projection so
     * subsequent draws land back on whatever was active before it (the parent target, or an
     * enclosing layer). Does not composite/draw anything itself — see {@link #blitLayer} and
     * {@link #compositeMask} for what to do with the finished FBO. */
    public void endLayerFbo() {
        flush();
        LayerFrame frame = layerStack.pop();
        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        fd.projMatrix.set(frame.savedProjMatrix());
        fd.viewportW = frame.savedViewportW();
        fd.viewportH = frame.savedViewportH();
        CgRenderPipeline.getInstance().prepareFrame();
        frame.glScope().close();
        currentTexture = null;
    }

    /** Blits a finished layer FBO (from {@link #beginLayerFbo}/{@link #endLayerFbo}) back into
     * whatever's currently bound, full-screen, tinted by {@code opacity} via {@link #withLayerOpacity}
     * — everywhere the layer's own content didn't draw stayed transparent from the initial clear,
     * so this is safe to blit full-screen regardless of the originating element's own bounds.
     *
     * <p>Uses {@link #layerBlitMaterial}, not {@link #boxModelMaterial} — {@code fbo}'s contents are
     * premultiplied alpha (every partially-covered pixel was painted starting from a transparent
     * clear), so compositing it back needs premultiplied blend, not {@code boxModelMaterial}'s
     * straight-alpha blend. See {@code gui_layer_blit.shader}'s own doc comment for the full
     * derivation — using the wrong one reproduces exactly the "AA edges/translucent content look
     * different once behind a mask or fractional opacity" symptom this material fixes.</p>
     *
     * <p>V is sampled flipped ({@code v0=1, v1=0}): content drawn at screen-space y=0 (our top-left
     * origin convention) lands at NDC y=+1, which is texture row/{@code v=1} under OpenGL's own
     * bottom-left-origin texture convention — the opposite of a normal loaded-from-disk texture
     * (pre-flipped at decode time). Same correction {@code PictureInPictureRenderer.blitTexture}
     * applies in vanilla Minecraft/LDLib2 for the identical reason.</p>
     *
     * <p>{@code fbo}'s dimensions are real physical screen pixels (that's what it was allocated
     * with), but every quad submitted through {@link #quad} is run through the active
     * {@link PoseStack} transform — which, mid-frame, still carries {@code UIWindow}'s own
     * {@code uiScale} scale meant for logical-space element coordinates. Submitting an
     * already-physical-sized quad through that same scale would double-apply it. Temporarily
     * resetting the pose to identity for just this quad avoids that.</p> */
    public void blitLayer(CgFrameBuffer fbo, float opacity) {
        CgTexture2D colorTex = (CgTexture2D) fbo.getColorTexture(0);
        withMaterial(layerBlitMaterial, () -> withLayerOpacity(opacity, () -> {
            bindTexture(colorTex);
            poseStack.pushPose();
            poseStack.setIdentity();
            quad().at(0, 0).size(fbo.getWidth(), fbo.getHeight())
                  .uv(0f, 1f, 1f, 0f)   // V flipped — see the javadoc above
                  .color(getColor()).submit();
            flush();
            poseStack.popPose();
        }));
    }

    /**
     * Composites a mask onto an already-rendered subtree layer: multiplies {@code subtreeFbo}'s
     * existing color+alpha by {@code maskFbo}'s alpha channel via {@link CgBlendState#MASK_ALPHA_MULTIPLY}
     * — wherever the mask's alpha is 0, the subtree's output is zeroed out too. Both FBOs are the
     * pool's screen-sized instances, so they're always the same size. Leaves GL FBO/viewport/blend
     * state restored to whatever it was before this call (caller is responsible for re-binding
     * {@code subtreeFbo} itself if it needs to keep drawing into it afterward).
     */
    public void compositeMask(CgFrameBuffer subtreeFbo, CgFrameBuffer maskFbo) {
        flush();
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.BLEND)) {
            subtreeFbo.bind();
            CgGL.glViewport(0, 0, subtreeFbo.getWidth(), subtreeFbo.getHeight());
            CgTexture2D maskTex = (CgTexture2D) maskFbo.getColorTexture(0);
            bindTexture(maskTex);
            CgBlendState.MASK_ALPHA_MULTIPLY.apply();
            // Same v-flip as blitLayer — maskTex is another FBO color attachment, same OpenGL
            // bottom-left-origin storage vs. our top-left screen-space convention. Same
            // identity-pose bypass as blitLayer too — this quad is already physical-pixel-sized.
            poseStack.pushPose();
            poseStack.setIdentity();
            quad().at(0, 0).size(subtreeFbo.getWidth(), subtreeFbo.getHeight())
                  .uv(0f, 1f, 1f, 0f)   // V flipped, same reason as blitLayer
                  .color(0xFFFFFFFF).submit();
            flush();
            poseStack.popPose();
        }
        currentTexture = null;
    }

    /**
     * Releases everything this context owns outright and drops the singleton, so the next
     * {@link #getInstance()} builds a fresh one.
     *
     * <p>Called on GL-context destruction via {@code CgUiLifecycle}. Note that in this engine that
     * means <b>game shutdown only</b> — there is no destroy-then-init cycle in a running process — so
     * this is not protecting a subsequent context. It is explicit, complete teardown of what this
     * class owns, matching the engine's own convention, and it is the only thing that frees the layer
     * FBO pool: those are built with {@link CgFrameBuffer#createOwned}, which bypasses
     * {@code CgFrameBufferRegistry}, so {@code deleteAll()} never reaches them.</p>
     *
     * <p><b>Only genuinely-owned resources are freed here</b>, and the distinction matters because
     * double-freeing is as bad as leaking:</p>
     * <ul>
     *   <li><b>Freed</b> — the layer FBO pool ({@link CgFrameBuffer#createOwned}, so ours), the
     *       {@link CgUiRenderer}'s batch renderer, and the {@link CgTextRenderer} (CrystalGraphics'
     *       registry treats {@code deleteAll()} as a backstop and expects owners to delete their
     *       own).</li>
     *   <li><b>Not freed</b> — {@code boxModelMaterial}/{@code layerBlitMaterial} come from the
     *       cache in {@code CgMaterialRegistry}, {@code whitePixel} is a
     *       {@code CgFallbackTextures} constant, and the atlases behind {@code font} belong to
     *       {@code CgFontRegistry}. All three are swept by
     *       {@code CgGraphicsLifecycle.destroyContext()} itself; deleting them here would be a
     *       double free of objects this context merely borrows.</li>
     * </ul>
     *
     * <p>Idempotent, and safe to call when the singleton was never constructed.</p>
     */
    public static void destroy() {
        if (instance == null) return;
        instance.releaseOwnedResources();
        instance = null;
    }

    private void releaseOwnedResources() {
        // Any still-open layer frame belongs to a frame that will never finish. Drop the saved GL
        // scopes without close()-ing them: their saved state refers to the dying context, so
        // restoring it is meaningless at best.
        layerStack.clear();

        for (CgFrameBuffer fbo : layerFboPool) {
            fbo.delete();
        }
        layerFboPool.clear();

        renderer.delete();
        textRenderer.delete();

        glScope = null;
        currentTexture = null;
        currentMaterial = null;
        frameActive = false;
    }
}
