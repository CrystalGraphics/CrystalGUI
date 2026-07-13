package com.crystalgui.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.gl.texture.CgTextureManager;

/**
 * True immediate-mode 2D paint context for CgGui's box-model layer. NO BATCHING,
 * NO DEFERRED SUBMISSION — every {@link #fillRect}/{@link #drawImage} call issues its
 * GPU draw the instant it's called, in the exact order called, exactly once. This is
 * "Track A" from the CrystalGraphics immediate-mode design: one
 * {@code map → write → commit → rebindPointersIfNeeded → draw} sequence per quad,
 * riding {@link CgStreamBuffer}'s sync-ring waterfall so per-quad mapping doesn't stall.
 *
 * <p><b>Frame lifecycle</b> — call {@link #beginFrame} once before walking the UI tree,
 * then {@link #endFrame} once after. Every {@code fillRect}/{@code drawImage} call in
 * between draws immediately; there is no recording phase and nothing to flush.</p>
 *
 * <p><b>Scope of this first slice:</b> only the shared {@code gui_quad.shader} box-model
 * material is wired up (solid rects + full/partial-rect sprites, both through the same
 * quad path with a bound texture — solid fills use a shared 1x1 white texture). Per-element
 * custom materials, scissor/clip, and 2D transforms are intentionally deferred to a later
 * phase (see CgGui phase plan) — adding them means widening {@link #currentMaterial} state
 * tracking here, not restructuring this class.</p>
 */
public final class CgUiPaintContext {

    private static final CgVertexFormat FORMAT = CgVertexFormat.POS2_UV2_COL4UB;
    private final CgTextureAbstract MISSING_TEX = CgTextureManager.get().getFallback();

    // Batch-Renderer set up in immediate flush mode
    private final CgBatchRenderer renderer;
    private final CgMaterial boxModelMaterial;
    private final CgTexture2D whitePixel;

    // ── State elision (see conversation: not batching, just skipping redundant GL work) ──
    private CgTextureAbstract currentTexture;
    private boolean frameActive;

    public CgUiPaintContext() {
        this.renderer = CgBatchRenderer.create(FORMAT, 4);
        this.boxModelMaterial = CgMaterial.load("crystalgui:shaders/gui_quad.shader");
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
    }

    // ── Frame lifecycle ─────────────────────────────────────────────────────

    /**
     * Sets up an orthographic screen-space projection ({@code cg_ProjMatrix}) and binds
     * the shared box-model material. Call once per frame before {@code rootElement.drawSubtree(ctx)}.
     */
    public void beginFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("beginFrame() called without matching endFrame()");

        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        fd.viewportW = screenWidth;
        fd.viewportH = screenHeight;
        pipeline.prepareFrame();
        renderer.begin();

        boxModelMaterial.bind();
        currentTexture = null; // force a texture (re)bind on the first quad of this frame
        frameActive = true;
    }

    /** Unbinds the box-model material. Call once after the whole UI tree has painted. */
    public void endFrame() {
        if (!frameActive) return;
        boxModelMaterial.unbind();
        currentTexture = null;
        frameActive = false;
        renderer.end();
    }

    // ── Public draw API (called from UIElement / CguiTexture implementations) ──────

    /** Solid-color fill, tint already includes opacity. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        bindTextureIfChanged(whitePixel);
        submitQuad(x, y, width, height, 0f, 0f, 1f, 1f, argb);
        renderer.flush();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture2D texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTextureIfChanged(texture);
        submitQuad(x, y, width, height, u0, v0, u1, v1, argb);
        renderer.flush();
    }

    public void submitQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (!frameActive) throw new IllegalStateException("Draw called outside beginFrame()/endFrame()");

        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;

        if (currentTexture == MISSING_TEX) {
            // Top left
            u0 = 0;
            v0 = 0;
            // Bottom right
            u1 = 1;
            v1 = 1;
        }

        CgVertexWriter vc = renderer.vertex();
        vc.vertex(x, y).uv(u0, v0).color(r, g, b, a).endVertex();
        vc.vertex(x + w, y).uv(u1, v0).color(r, g, b, a).endVertex();
        vc.vertex(x + w, y + h).uv(u1, v1).color(r, g, b, a).endVertex();
        vc.vertex(x, y + h).uv(u0, v1).color(r, g, b, a).endVertex();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void bindTextureIfChanged(CgTextureAbstract texture) {
        if (texture == currentTexture) return; // elision — not batching, just skipping a redundant glBindTexture
        texture.bind(0);
        currentTexture = texture;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Straight ARGB multiply, both channels normalized to [0,255] per-component. */
    public static int multiplyArgb(int a, int b) {
        int aa = ((a >>> 24) & 0xFF) * ((b >>> 24) & 0xFF) / 255;
        int ar = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int ag = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int ab = (a & 0xFF) * (b & 0xFF) / 255;
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }
}
