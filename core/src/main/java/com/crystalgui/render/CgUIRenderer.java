package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.vertex.CgVertexConsumer;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import org.joml.Matrix4f;

public final class CgUIRenderer {
    private static final CgVertexFormat FORMAT = CgVertexFormat.POS2_UV2_COL4UB;
    private static final int INITIAL_MAX_QUADS = 9;

    private final CgBatchRenderer renderer;
    private final CgUiPaintContext ctx;

    private final CgTexture2D MISSING_TEX = CgTextureManager.get().getFallback();

    private final VertexWriter writer;

    CgUIRenderer(CgUiPaintContext cgUiPaintContext) {
        this.renderer = CgBatchRenderer.create(FORMAT, INITIAL_MAX_QUADS);
        this.ctx = cgUiPaintContext;
        this.writer = new VertexWriter(renderer.vertex(), ctx.getPoseStack());;
    }

    public void flush() {
        renderer.flush();
    }

    void end() {
        renderer.end();
    }

    void begin() {
        renderer.begin();
    }

    public void submitQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (!ctx.isFrameActive()) throw new IllegalStateException("Draw called outside beginFrame()/endFrame()");

        if (ctx.getCurrentTexture() == MISSING_TEX) {
            u0 = 0; v0 = 0; u1 = 1; v1 = 1;
        }

        CgVertexConsumer vc = this.vertex();
        vc.vertex(x, y).uv(u0, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y).uv(u1, v0).colorArgb(argb).endVertex();
        vc.vertex(x + w, y + h).uv(u1, v1).colorArgb(argb).endVertex();
        vc.vertex(x, y + h).uv(u0, v1).colorArgb(argb).endVertex();
    }

    public VertexWriter vertex() {
        return writer;
    }

    // PoseStack-aware VertexWriter, obviously set up in 2D mode.
    public final static class VertexWriter implements CgVertexConsumer {

        private final CgVertexWriter writer;
        private final PoseStack poseStack;

        private VertexWriter(CgVertexWriter writer, PoseStack poseStack) {
            this.writer = writer;
            this.poseStack = poseStack;
        }

        @Override
        public CgVertexFormat format() {
            return writer.format();
        }

        @Override
        public CgVertexConsumer vertex(float x, float y) {
            Matrix4f pose = poseStack.last().pose();

            writer.vertex(pose, x, y);
            return this;
        }

        @Override
        public CgVertexConsumer vertex(float x, float y, float z) {
            throw new IllegalStateException("Format is not 3D-position format");
        }

        @Override
        public CgVertexConsumer uv(float u, float v) {
            writer.uv(u, v);
            return this;
        }

        @Override
        public CgVertexConsumer color(int r, int g, int b, int a) {
            writer.color(r, g, b, a);
            return this;
        }

        @Override
        public void endVertex() {
            writer.endVertex();
        }
    }
}
