package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
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
     * TODO: feed IDENTITY into u_modelview once shader uniform binding is
     *       restored via CgMaterial.bind() after CgRenderLayer migration.
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
        CgRenderState activeRenderState = null;
        int activeCmdKind = -1;
        int activeTextTextureId = -1;
        float activeTextPxRange = Float.NaN;
        int activeScissorX = -1, activeScissorY = -1, activeScissorW = -1, activeScissorH = -1;

        try {
            for (int i = 0; i < count; i++) {
                CgRenderState cmdRenderState = drawList.renderState(i);
                int cmdKind = drawList.cmdKind(i);
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

                // Draw-state transition — branches on command kind
                boolean stateChanged = cmdRenderState != activeRenderState || cmdKind != activeCmdKind;

                // For text commands, also check texture and pxRange changes
                if (!stateChanged && cmdKind == CgUiDrawList.CMD_KIND_TEXT) {
                    int texId = drawList.textTextureId(i);
                    float pxRange = drawList.textPxRange(i);
                    stateChanged = texId != activeTextTextureId
                            || !floatMatch(pxRange, activeTextPxRange);
                }

                if (stateChanged) {
                    if (activeRenderState != null) {
                        activeRenderState.clear();
                    }

                    if (cmdKind == CgUiDrawList.CMD_KIND_TEXT) {
                        int texId = drawList.textTextureId(i);
                        float pxRange = drawList.textPxRange(i);

                        // TODO: shader uniform binding (u_modelview=IDENTITY, u_pxRange) was
                        //       previously set here via cmdRenderState.getShader().applyBindings().
                        //       getShader() was removed from CgRenderState in T8. Must be
                        //       re-wired via CgMaterial.bind() once CgRenderLayer is migrated
                        //       to the CrystalShader material framework.

                        // TODO: projection and texture (texId) were previously supplied to
                        //       apply(projection, texId). Those parameters are gone from the
                        //       no-arg apply(). Projection + texture binding must be restored
                        //       via CgMaterial once the migration is complete.
                        cmdRenderState.apply();
                        activeTextTextureId = texId;
                        activeTextPxRange = pxRange;
                    } else {
                        // TODO: projection was previously supplied to apply(projection).
                        //       Projection binding must be restored via CgMaterial once the
                        //       CgRenderLayer migration is complete.
                        cmdRenderState.apply();
                    }

                    activeRenderState = cmdRenderState;
                    activeCmdKind = cmdKind;
                }

                // Draw the vertex range
                CgBatchRenderer renderer = slots.renderer(batchSlot);
                renderer.drawUploadedRange(vtxStart, vtxCount);
            }
        } finally {
            // Cleanup: clear last draw state, disable scissor
            if (activeRenderState != null) {
                activeRenderState.clear();
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

    /** Matches two float values, treating NaN == NaN as true. */
    private static boolean floatMatch(float a, float b) {
        if (Float.isNaN(a) && Float.isNaN(b)) return true;
        return a == b;
    }
}
