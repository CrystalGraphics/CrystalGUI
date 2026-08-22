package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgui.render.CgUiPaintContext;

import javax.annotation.Nullable;

/**
 * A window's last frame, kept so a minimised window still has a picture.
 *
 * <h3>Why this is allowed to exist, when the plan said it was not</h3>
 *
 * <p>{@code plan_windowing.md} deferred hover thumbnails on the grounds that <em>"a preview of a frozen
 * window means keeping its last frame, which fights the freeze contract"</em>. That reasoning does not
 * survive being written down next to what the freeze contract actually says. Hiding is DETACHING so that
 * a hidden window <b>stops running</b> — no layout, no paint, no selectors, no input references. A
 * texture does not run. It is a picture of a window, not a window, and nothing about keeping one lets a
 * frozen window do anything it was supposed to have stopped doing.</p>
 *
 * <p>DWM keeps exactly this and nobody would call a minimised Windows application live. What the deferral
 * was really protecting against is a preview that is secretly a live window, which is a different design
 * and not this one.</p>
 *
 * <h3>Captured at the START of a minimise, not at the end</h3>
 *
 * <p>The gesture runs for 400ms and ends by detaching, so by the time the window is hidden there is
 * nothing left to photograph. It is taken on the first frame of the animation instead, while the window
 * is still whole and — because {@code WindowAnimation} starts from a neutral transform at full opacity —
 * before any of the flight has been applied to it.</p>
 *
 * <h3>Owned, and therefore this class's problem to free</h3>
 *
 * <p>{@code createOwned} deliberately bypasses {@code CgFrameBufferRegistry}, so nothing sweeps this: the
 * same arrangement {@code CgUiPaintContext}'s layer pool has, and the same obligation. A window disposes
 * its snapshot with itself. The one case not covered is GL context loss, which in this engine happens
 * only at process shutdown — there is no destroy-then-init cycle to survive.</p>
 */
final class WindowSnapshot {

    /** Matches the layer pool's: colour only, straight RGBA8, premultiplied by how it is drawn into. */
    private static final CgFrameBufferFormat FORMAT =
            CgFrameBufferFormat.builder("cgui_window_snapshot").color(0, CgTextureType.RGBA8).build();

    @Nullable
    private CgFrameBuffer fbo;

    /** The logical size of what was captured — the thumbnail fits against this, not against pixels. */
    private float capturedWidth;
    private float capturedHeight;

    /** Whether there is a picture to draw. */
    boolean isValid() {
        return fbo != null && capturedWidth > 0f && capturedHeight > 0f;
    }

    float capturedWidth() {
        return capturedWidth;
    }

    float capturedHeight() {
        return capturedHeight;
    }

    /**
     * Photographs {@code frame} as it is right now.
     *
     * <p>Called from inside that frame's own paint, which is the only place the subtree can be drawn at
     * all — so the caller must have cleared whatever flag brought it here before calling, or the nested
     * draw re-enters this and never stops.</p>
     *
     * @param scale physical pixels per logical pixel, read from the live pose rather than assumed: it is
     *              {@code uiScale} times whatever any ancestor has scaled, and a snapshot allocated
     *              against the wrong one is either blurry or four times too large.
     */
    void capture(CgUiPaintContext ctx, WindowFrame frame, float scale) {
        var box = frame.getRuntimeCache();
        if (box.getWidth() <= 0f || box.getHeight() <= 0f || scale <= 0f) return;

        int physicalWidth = Math.max(1, Math.round(box.getWidth() * scale));
        int physicalHeight = Math.max(1, Math.round(box.getHeight() * scale));
        // WARMED THE MOMENT IT EXISTS, and again after any reallocation.
        //
        // A brand-new framebuffer that has never been drawn into loses the first real draw made into it
        // -- a documented driver behaviour this engine already works around for its layer pool, and one
        // whose symptom is uniquely misleading: the FIRST minimise produced a photograph with the editor
        // missing from it and every one after was perfect, which reads as a race in the window rather
        // than in the target it was being drawn onto.
        //
        // Also after a resize, which the pool does not bother with because its slots settle at the
        // screen size and stay there. A window's snapshot is sized to the WINDOW, so it is reallocated
        // whenever one is resized, and a throwaway transparent quad is nothing against that.
        boolean fresh = false;
        if (fbo == null) {
            fbo = CgFrameBuffer.createOwned("cgui_snapshot", physicalWidth, physicalHeight, FORMAT);
            fresh = true;
        } else if (fbo.getWidth() != physicalWidth || fbo.getHeight() != physicalHeight) {
            fbo.resize(physicalWidth, physicalHeight);
            fresh = true;
        }
        capturedWidth = box.getWidth();
        capturedHeight = box.getHeight();

        // A FRESHLY ALLOCATED TARGET LOSES THE FIRST DRAW MADE INTO IT, so the first one is thrown away.
        //
        // The engine already works around this for its layer pool, with a transparent throwaway quad
        // (warmUpLayer) drawn the moment a slot is created -- and its own note says why it is phrased as
        // a COINCIDENCE rather than a property of the buffer: "a cold program's first draw into a cold
        // FBO, same frame". Warming with that one quad is enough for the pool because the only thing the
        // pool needs to survive is the blit material.
        //
        // It is NOT enough here, and the symptom said so precisely: the first photograph of a window came
        // out complete except for the editor's TEXT. Text does not go through the quad path at all --
        // CgTextRenderer owns its own renderer, its own instance buffer and its own material -- so
        // warming one program vouches for nothing about another, and a snapshot draws a whole window's
        // worth of programs rather than one.
        //
        // So rather than enumerate them, the real content is drawn twice and the first is overwritten.
        // Whatever was cold is warm by the second pass, and this costs one extra subtree draw exactly
        // once per allocation -- a minimise, or a resize of a window that has been minimised before.
        if (fresh) {
            ctx.warmUpLayer(fbo);
            renderInto(ctx, frame, box.getX(), box.getY());
        }
        renderInto(ctx, frame, box.getX(), box.getY());
    }

    /** One pass of the window into {@link #fbo}. @see #capture */
    private void renderInto(CgUiPaintContext ctx, WindowFrame frame, float originX, float originY) {
        // THE SCISSOR IS SCREEN-SPACE and this target is not the screen. An enclosing clip -- the
        // desktop's, a scroller's -- would be applied in coordinates that mean nothing here, and would
        // cut the photograph along whatever line happened to be active.
        ctx.getScissorStack().clearScissorIfNeeded();
        ctx.beginLayerFbo(fbo);
        ctx.getPoseStack().pushPose();
        // The window's own origin to zero, in LOGICAL units -- the pose applies the scale after this, so
        // translating here is in the same space the elements draw in.
        ctx.getPoseStack().last().pose().translate(-originX, -originY, 0f);
        try {
            ctx.mirrored(() -> frame.drawSubtree(ctx));
        } finally {
            ctx.getPoseStack().popPose();
            ctx.endLayerFbo();
            ctx.getScissorStack().applyScissorIfNeeded();
        }
    }

    /** Draws the photograph into a rect. Does nothing when there is none. */
    void draw(CgUiPaintContext ctx, float x, float y, float width, float height) {
        if (fbo == null) return;
        ctx.drawLayer(fbo, x, y, width, height);
    }

    /** Frees the target. Idempotent, and safe on a window that never minimised. */
    void dispose() {
        if (fbo == null) return;
        fbo.delete();
        fbo = null;
        capturedWidth = 0f;
        capturedHeight = 0f;
    }
}
