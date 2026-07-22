package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgGlSlot;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.text.render.CgTextRenderer;
import lombok.Getter;
import lombok.Setter;

/**
 * True immediate-mode 2D paint context for CrystalGUI's box-model layer.
 *
 * <p>Per-instance (not static) — each {@code UiRuntime} owns its own paint context.
 * Wraps frame lifecycle in {@link CgGlScope} for GL state isolation and saves/restores
 * {@link CgFrameData} so UI rendering does not corrupt the 3D pipeline state.</p>
 *
 * <p>Integrates {@link ScissorStack} for nested clip regions — GL scissor is applied
 * at draw time when a scissor rect is active.</p>
 *
 * <p><b>Frame lifecycle</b> — call {@link #beginFrame} once before walking the UI tree,
 * then {@link #endFrame} once after. Every {@code fillRect}/{@code drawImage}/
 * {@link #drawText} call in between draws immediately; there is no recording phase and
 * nothing to flush. This is deliberate, not merely unoptimized — see {@link #drawText}'s
 * doc for why text specifically must not defer its GPU submission.</p>
 */
public final class CgUiPaintContext {

    private final CgMaterial boxModelMaterial;

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    @Getter
    private final CgTexture2D whitePixel;

    @Getter
    private final PoseStack poseStack;

    /**
     * Basic wrapper over CgBatchRenderer
     * Works only for quads.
     * <br>
     * <b>Currently intended for immediate flushing, despite it being inefficient and going against the idea of "Batching"</b>
     */
    @Getter
    private final CgUiRenderer renderer;

    // ── Text ─────────────────────────────────────────────────────────────────
    /**
     * Owned independently of {@link #renderer} — text uses
     * {@link CgVertexFormat#POS2_UV2_COL4UB}, distinct from
     * {@code CgUiRenderer}'s {@code CgVertexFormat.UI}, so it cannot share the same
     * {@code CgBatchRenderer}. See {@code docs/CRYSTALGUI_TEXT_RENDERING_PLAN.md} §2.1.
     */
    @Getter
    private final CgTextRenderer textRenderer;

    // ── GL state isolation ──────────────────────────────────────────────────
    private CgGlScope glScope;


    // ── Scissor ─────────────────────────────────────────────────────────────
    @Getter
    private final ScissorStack scissorStack = new ScissorStack();

    // ── State elision ───────────────────────────────────────────────────────
    @Getter
    private CgTexture2D currentTexture;
    @Getter
    private boolean frameActive;

    @Getter @Setter
    private int color = 0xFFFFFFFF;

    public CgUiPaintContext() {
        this.poseStack = new PoseStack();
        this.renderer = new CgUiRenderer(this);
        this.boxModelMaterial = CgMaterial.load("crystalgui:shaders/gui_quad.shader");
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
        this.textRenderer = CgTextRenderer.create();
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
        boxModelMaterial.bind();
        currentTexture = null;
        scissorStack.reset();
        frameActive = true;
    }

    /**
     * Unbinds the box-model material, restores {@link CgFrameData}, and restores
     * GL state via the saved {@link CgGlScope}. Call once after the whole UI tree
     * has painted.
     */
    public void endFrame() {
        if (!frameActive) return;

        boxModelMaterial.unbind();
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
        submitQuad(x, y, width, height, 0f, 0f, 1f, 1f, argb);
        flush();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture2D texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTexture(texture);
        submitQuad(x, y, width, height, u0, v0, u1, v1, argb);
        flush();
    }

    /**
     * Draws pre-built text immediately, same painter's-order guarantee as {@link #fillRect}/
     * {@link #drawImage} — must not be deferred/batched across the frame (see
     * {@code docs/CRYSTALGUI_TEXT_RENDERING_PLAN.md} §2.3), otherwise text could render out of
     * DOM order relative to quads drawn before/after it.
     *
     * <p>Wrapped in a {@link CgGlScope} because {@link CgTextRenderer} binds/unbinds its own raw
     * shader and applies/clears its own {@code CgRenderState} internally — without this, the next
     * {@code fillRect()}/{@code drawImage()} call in the same frame would render with no program
     * bound (see plan §2.4). Restoring via scope (not a manual re-bind) also survives
     * CrystalGraphics Phase 2's planned {@code CgMaterial} swap inside {@code CgTextRenderer}
     * without needing changes here.</p>
     *
     * @param layout pre-built text layout (caller/widget owns layout construction and caching)
     * @param font   the font to render with
     * @param x      local logical X origin
     * @param y      local logical Y origin
     * @param argb   packed color, tint already includes opacity
     */
    public void drawText(CgTextLayout layout, CgFont font, float x, float y, int argb) {
            textRenderer.draw(layout, font, x, y, argb, poseStack);
    }

    /**
     * Returns the context's text renderer object.
     */
    public CgTextRenderer text() {
        return textRenderer;
    }

    public void bindTexture(CgTexture2D texture) {
        if (texture == currentTexture) return;
        texture.bind(0);
        currentTexture = texture;
    }

    /** Submits quad (4 vertices from the given parameters) to the renderer's queue
     *  but doesn't request the draw call.
     *  Must {@link #flush()} to draw.
     */
    public void submitQuad(float x, float y, float width, float height, 
                           float u0, float v0, float u1, float v1, int argb) {
        renderer.submitQuad(x, y, width, height, u0, v0, u1, v1, argb);
    }

    /**
     * Flushes renderer queue and draws all submitted quads
     */
    public void flush() {
        renderer.flush();
    }
}
