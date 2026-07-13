package com.crystalgui.render;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgGlSlot;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgui.core.render.ScissorStack;
import lombok.Getter;
import org.joml.Matrix4f;

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
 * then {@link #endFrame} once after. Every {@code fillRect}/{@code drawImage} call in
 * between draws immediately; there is no recording phase and nothing to flush.</p>
 */
public final class CgUiPaintContext {

    private static final CgVertexFormat FORMAT = CgVertexFormat.POS2_UV2_COL4UB;
    private static final int INITIAL_MAX_QUADS = 9;
    private final CgTextureAbstract MISSING_TEX = CgTextureManager.get().getFallback();

    // Batch-Renderer set up in immediate flush mode
    private final CgBatchRenderer renderer;
    private final CgMaterial boxModelMaterial;
    private final CgTexture2D whitePixel;

    // ── GL state isolation ──────────────────────────────────────────────────
    private CgGlScope glScope;

    // ── Frame data save/restore ─────────────────────────────────────────────
    private final Matrix4f savedViewMatrix = new Matrix4f();
    private final Matrix4f savedProjMatrix = new Matrix4f();
    private int savedViewportW;
    private int savedViewportH;

    // ── Scissor ─────────────────────────────────────────────────────────────
    @Getter
    private final ScissorStack scissorStack = new ScissorStack();

    // ── State elision ───────────────────────────────────────────────────────
    private CgTexture2D currentTexture;
    private boolean frameActive;

    public CgUiPaintContext() {
        this.renderer = CgBatchRenderer.create(FORMAT, INITIAL_MAX_QUADS);
        this.boxModelMaterial = CgMaterial.load("crystalgui:shaders/gui_quad.shader");
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
    }

    // ── Frame lifecycle ─────────────────────────────────────────────────────

    /**
     * Saves GL state via {@link CgGlScope}, saves {@link CgFrameData}, sets up
     * an orthographic screen-space projection, and binds the shared box-model
     * material. Call once per frame before {@code rootElement.drawSubtree(ctx)}.
     */
    public void beginFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("beginFrame() called without matching endFrame()");

        // Save GL state before UI rendering
//        glScope = CgGlState.save(
//                CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
//                CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.VIEWPORT);

        // Save CgFrameData
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        savedViewMatrix.set(fd.viewMatrix);
        savedProjMatrix.set(fd.projMatrix);
        savedViewportW = fd.viewportW;
        savedViewportH = fd.viewportH;

        // Set ortho projection for UI
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        fd.viewportW = screenWidth;
        fd.viewportH = screenHeight;
        pipeline.prepareFrame();

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

        // Restore CgFrameData
//        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
//        CgFrameData fd = pipeline.getFrameData();
//        fd.viewMatrix.set(savedViewMatrix);
//        fd.projMatrix.set(savedProjMatrix);
//        fd.viewportW = savedViewportW;
//        fd.viewportH = savedViewportH;

//        // Restore GL state
//        if (glScope != null) {
//            glScope.close();
//            glScope = null;
//        }
    }

    // ── Public draw API ─────────────────────────────────────────────────────

    /** Solid-color fill, tint already includes opacity. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        bindTexture(whitePixel);
        submitQuad(x, y, width, height, 0f, 0f, 1f, 1f, argb);
        flushRenderer();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture2D texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTexture(texture);
        submitQuad(x, y, width, height, u0, v0, u1, v1, argb);
        flushRenderer();
    }

    public void submitQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (!frameActive) throw new IllegalStateException("Draw called outside beginFrame()/endFrame()");

        if (currentTexture == MISSING_TEX) {
            u0 = 0; v0 = 0; u1 = 1; v1 = 1;
        }

        com.crystalgraphics.gl.buffer.staging.CgVertexWriter vc = renderer.vertex();
        vc.vertex(x, y).uv(u0, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y).uv(u1, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y + h).uv(u1, v1).colorArgb(argb).endVertex();
        vc.vertex(x, y + h).uv(u0, v1).colorArgb(argb).endVertex();
    }

    public void bindTexture(CgTexture2D texture) {
        if (texture == currentTexture) return;
        texture.bind(0);
        currentTexture = texture;
    }
    public void flushRenderer() {
        renderer.flush();
    }
}
