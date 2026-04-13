package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;
import io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer;

import org.joml.Matrix4f;

/**
 * Sequentially replays a {@link CgUiDrawList} in painter's order.
 *
 * <p>Stateless executor — takes the draw list, batch slots, and projection
 * as input. Handles per-slot upload preparation, scissor transitions,
 * draw-state apply/clear, and {@code drawUploadedRange()} calls.</p>
 *
 * @see CgUiDrawList
 * @see CgUiBatchSlots
 */
public final class CgUiDrawListExecutor {

    /**
     * Cached identity matrix used as {@code u_modelview} for text shaders
     * during draw-list replay. UI text renders in screen space with an
     * orthographic projection; no model-view transform is needed.
     */
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final ScissorStack scissorStack = new ScissorStack();

    public void execute(CgUiDrawList drawList, CgUiBatchSlots slots, Matrix4f projection) {
        if (drawList.isRecording()) throw new IllegalStateException("Cannot replay while still recording");
        int count = drawList.commandCount();
        if (count == 0) return;

        // Phase 1: begin + upload all batch slots
        for (int s = 0; s < slots.size(); s++) {
            CgBatchRenderer renderer = slots.renderer(s);
            if (renderer.isDirty()) {
                renderer.uploadPendingVertices();
            }
        }

        // Phase 2: sequential replay in painter's order
        CgUiDrawState activeDrawState = null;
        int activeScissorX = -1, activeScissorY = -1, activeScissorW = -1, activeScissorH = -1;

        try {
            for (int i = 0; i < count; i++) {
                CgUiDrawState cmdState = drawList.drawState(i);
                int batchSlot = drawList.batchSlot(i);
                int sx = drawList.scissorX(i);
                int sy = drawList.scissorY(i);
                int sw = drawList.scissorW(i);
                int sh = drawList.scissorH(i);
                int vtxStart = drawList.vtxStart(i);
                int vtxCount = drawList.vtxCount(i);

                // Scissor transition
                boolean scissorChanged = sx != activeScissorX || sy != activeScissorY
                        || sw != activeScissorW || sh != activeScissorH;
                if (scissorChanged) {
                    if (sw > 0 && sh > 0) {
                        scissorStack.reset();
                        scissorStack.push(sx, sy, sw, sh);
                        scissorStack.applyCurrentGl();
                    } else {
                        scissorStack.disableGl();
                        scissorStack.reset();
                    }
                    activeScissorX = sx;
                    activeScissorY = sy;
                    activeScissorW = sw;
                    activeScissorH = sh;
                }

                // Draw-state transition
                if (cmdState != activeDrawState) {
                    if (activeDrawState != null) {
                        activeDrawState.getRenderState().clear();
                    }
                    int overrideTexId = -1;
                    if (cmdState.hasTextureOverride()) {
                        CgTextureBinding tb = cmdState.getTextureOverride();
                        overrideTexId = tb != null ? tb.getTextureId() : -1;
                    }

                    // Text shaders require u_modelview (identity for UI) and
                    // u_pxRange.  Set them as ephemeral bindings BEFORE apply()
                    // so they are consumed in the same bind() call that apply()
                    // triggers internally.
                    CgShader shader = cmdState.getRenderState().getShader();
                    if (cmdState.hasTextPxRange()) {
                        float pxRange = cmdState.getTextPxRange();
                        shader.applyBindings(b -> {
                            b.mat4("u_modelview", IDENTITY);
                            b.set1f("u_pxRange", pxRange);
                        });
                    }

                    cmdState.getRenderState().apply(projection, overrideTexId);
                    activeDrawState = cmdState;
                }

                // Draw the vertex range
                CgBatchRenderer renderer = slots.renderer(batchSlot);
                renderer.drawUploadedRange(vtxStart, vtxCount);
            }
        } finally {
            // Cleanup: clear last draw state, disable scissor
            if (activeDrawState != null) {
                activeDrawState.getRenderState().clear();
            }
            scissorStack.disable();

            // Phase 3: finish all batch slots
            for (int s = 0; s < slots.size(); s++) {
                CgBatchRenderer renderer = slots.renderer(s);
                if (renderer.isUploadedForReplay()) {
                    renderer.finishUploadedDraws();
                }
            }
        }
    }
}
