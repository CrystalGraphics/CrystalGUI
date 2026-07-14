package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.vertex.CgVertexConsumer;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import org.joml.Matrix4f;

public final class CgRenderWrapper {

    private final CgBatchRenderer renderer;
    private final CgUiPaintContext ctx;

    CgRenderWrapper(CgBatchRenderer renderer, CgUiPaintContext cgUiPaintContext) {
        this.renderer = renderer;
        this.ctx = cgUiPaintContext;
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
    
    public Vertex2DWriter vertex() {
        return new Vertex2DWriter(renderer.vertex(), ctx.getPoseStack());
    }

    public final static class Vertex2DWriter implements CgVertexConsumer {

        private final CgVertexWriter writer;
        private final PoseStack poseStack;

        private Vertex2DWriter(CgVertexWriter writer, PoseStack poseStack) {
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
