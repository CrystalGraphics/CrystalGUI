package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.vertex.CgVertexConsumer;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexTransformUtil;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class CgUiRenderer {
    private static final CgVertexFormat FORMAT = CgVertexFormat.UI;
    private static final int INITIAL_MAX_QUADS = 64;

    private final CgBatchRenderer renderer;
    private final CgUiPaintContext ctx;
    private final CgTexture2D MISSING_TEX = CgTextureManager.get().getFallback();

    private final VertexWriter poseWriter;

    CgUiRenderer(CgUiPaintContext ctx) {
        this.renderer = CgBatchRenderer.create(FORMAT, INITIAL_MAX_QUADS);
        this.ctx = ctx;
        this.poseWriter = new VertexWriter(ctx.getPoseStack());
    }

    void begin() {
        renderer.begin();
        poseWriter.bind(renderer.vertex());
    }

    void end() {
        poseWriter.unbind();
        renderer.end();
    }

    /**
     * Flushes renderer queue and draws all submitted quads.
     */
    public void flush() {
        renderer.flush();
    }

    public CgVertexConsumer vertex() {
        if (!ctx.isFrameActive()) {
            throw new IllegalStateException("Cannot access vertex writer outside beginFrame()/endFrame()");
        }
        return poseWriter;
    }

    /** Submits quad (4 vertices from the given parameters) to the renderer's queue
     *  but doesn't request the draw call.
     *  Must {@link #flush()} to draw.
     */
    public void submitQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (ctx.getCurrentTexture() == MISSING_TEX) {
            u0 = 0; v0 = 0; u1 = 1; v1 = 1;
        }

        CgVertexConsumer vc = this.vertex();

        vc.vertex(x, y).uv(u0, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y).uv(u1, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y + h).uv(u1, v1).colorArgb(argb).endVertex();
        vc.vertex(x, y + h).uv(u0, v1).colorArgb(argb).endVertex();
    }

    /**
     * A PoseStack-aware Decorator for CgVertexWriter.
     */
    public static final class VertexWriter implements CgVertexConsumer {
        private final PoseStack poseStack;
        private CgVertexWriter delegate;

        private VertexWriter(PoseStack poseStack) {
            this.poseStack = poseStack;
        }

        void bind(CgVertexWriter activeWriter) {
            this.delegate = activeWriter;
        }

        void unbind() {
            this.delegate = null;
        }

        public CgVertexWriter getDelegate() {
            if (delegate == null) {
                throw new IllegalStateException("Vertex writes attempted outside of renderer begin()/end() block");
            }
            return delegate;
        }

        @Override
        public CgVertexFormat format() {
            return getDelegate().format();
        }

    /**
     * Releases what this renderer owns.
     *
     * <p>Called by {@link CgUiPaintContext#destroy()}. {@link CgQuadRenderer#delete()} only unbinds
     * the material it left bound — the shared unit-quad mesh and instance buffer are class-wide and
     * registry-owned, so they deliberately outlive any one renderer.</p>
     */
    void delete() {
        renderer.delete();
    }
}

        @Override
        public void endVertex() {
            getDelegate().endVertex();
        }
    }
}