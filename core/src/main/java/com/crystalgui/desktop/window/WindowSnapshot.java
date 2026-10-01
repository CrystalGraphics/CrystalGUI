package com.crystalgui.desktop.window;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgui.desktop.motion.WindowAnimation;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.ScissorStack;
import com.crystalgui.ui.box.BoxPainter;
import com.crystalgui.ui.box.Box;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * A window's last frame, kept so a minimised window still has a picture.
 *
 * <h3>Why this is allowed to exist, when the plan said it was not</h3>
 *
 * <p>{@code plan/shell-windowing.md} deferred hover thumbnails on the grounds that <em>"a preview of a frozen
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
 * <h3>A requested texture</h3>
 *
 * <p>Paint may not make a framebuffer, so the picture is a texture the paint context requests: made when the frame
 * executes, kept across frames, and released through the same context when the window is disposed or resized.</p>
 */
public final class WindowSnapshot {

    @Nullable
    private CgGraphTexture picture;
    /** The paint context that requested {@link #picture}, and releases it. */
    @Nullable
    private CgUiPaintContext owner;

    /** The logical size of what was captured — the thumbnail fits against this, not against pixels. */
    private float capturedWidth;
    private float capturedHeight;

    /** Whether there is a picture to draw. */
    public boolean isValid() {
        return picture != null && capturedWidth > 0f && capturedHeight > 0f;
    }

    public float capturedWidth() {
        return capturedWidth;
    }

    public float capturedHeight() {
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
        Box box = frame.box();
        if (box == null || box.width() <= 0f || box.height() <= 0f || scale <= 0f) return;

        int physicalWidth = Math.max(1, Math.round(box.width() * scale));
        int physicalHeight = Math.max(1, Math.round(box.height() * scale));
        boolean fresh = picture == null || owner != ctx
                || picture.getWidth() != physicalWidth || picture.getHeight() != physicalHeight;
        if (fresh) {
            release();
            picture = ctx.requestLayer("cgui_window_snapshot", physicalWidth, physicalHeight);
            owner = ctx;
        }
        capturedWidth = box.width();
        capturedHeight = box.height();

        // A FRESH TARGET LOSES THE FIRST DRAW MADE INTO IT on at least one driver -- the first photograph of a
        // window came out without the editor's text. warmUpLayer warms one program and a window draws many, so the
        // content itself is drawn twice and the first is overwritten: one extra subtree draw per allocation.
        if (fresh) {
            ctx.warmUpLayer(picture);
            renderInto(ctx, box, scale);
        }
        renderInto(ctx, box, scale);
    }

    /** One pass of the window into {@link #picture}. @see #capture */
    private void renderInto(CgUiPaintContext ctx, Box box, float scale) {
        // THE SCISSOR IS SCREEN-SPACE and this target is not the screen. An enclosing clip -- the
        // desktop's, a scroller's -- would be applied in coordinates that mean nothing here, and would
        // cut the photograph along whatever line happened to be active.
        //
        // SET ASIDE, not merely disabled. clearScissorIfNeeded only turns the GL test off, and only
        // when the stack is empty; an inherited rect stays on the stack and every clip the WINDOW pushes
        // while it is drawn -- its content box, every overflow: hidden inside it -- is intersected with
        // that rect, in screen pixels, against a buffer that is the window's size. The photograph then
        // comes out cut along the ancestor's edge. Resumed below so the caller's clip is exactly what it
        // was. (The push itself also has to flip against THIS buffer's height rather than the screen's,
        // which CgUiPaintContext.pushScissor now does; the two halves were found from the same picture,
        // a minimised editor's preview showing its top half over flat panel colour.)
        // Flushed first: what is queued was drawn under the caller's clip, and is recorded with the clip at its flush.
        ctx.flush();
        ScissorStack.Saved outerClip = ctx.getScissorStack().suspend();
        ctx.getScissorStack().clearScissorIfNeeded();
        ctx.beginLayerFbo(picture, true);
        ctx.getPoseStack().pushPose();
        // A PHOTOGRAPH IS TAKEN IN THE WINDOW'S RESTING FRAME OF REFERENCE, never in the animation's
        // current one -- so the ambient pose is DISCARDED rather than built on.
        //
        // This runs from paintOverlay, which is inside the frame's own drawSubtree, and drawSubtree has
        // already pushed the animation's transform. Building on that bakes the animation into the
        // picture, which is then drawn under the same transform AGAIN. It is invisible for a minimise
        // and a close, whose start transform is neutral, and it wrecks the two that start scaled: an
        // open photographed a sliver, and a restore-from-minimise photographed a shrunken corner.
        //
        // setIdentity, then the ROOT scale alone -- which is uiScale, the one thing that genuinely
        // belongs in the picture, since the FBO is sized in physical pixels. Same reasoning as
        // blitLayer, which sets identity for the same reason.
        //
        // THEN THE WINDOW'S OWN MATRIX, INVERTED, rather than a translation by its origin. The painter
        // poses every box at `base x localToWorld`, so a base carrying that inverse cancels the frame's
        // place in the document exactly -- position, every ancestor's transform, and any scroll above
        // it. Translating by an origin got the common case right and would drift the moment anything
        // between the root and the window was transformed, which is what an animating desktop is.
        ctx.getPoseStack().setIdentity();
        ctx.getPoseStack().last().pose().scale(scale, scale, 1f);
        ctx.getPoseStack().last().pose().mul(new Matrix4f(box.localToWorld()).invert());
        try {
            // NO MIRRORING GUARD, and none is needed. The old engine reconciled ONE cached
            // localToWorld per element against whatever pose it was last drawn with, so a second draw
            // told every element of the window it lived in the photograph and hit-testing followed it
            // there; `ctx.mirrored` was a counter that suppressed the write. `BoxPainter.paintSubtree`
            // composes `base x localToWorld` per box and never writes it back, so a subtree can be
            // drawn anywhere as many times as anybody likes.
            BoxPainter.paintSubtree(box, ctx);
        } finally {
            ctx.getPoseStack().popPose();
            ctx.endLayerFbo();
            ctx.getScissorStack().resume(outerClip);
            ctx.reapplyScissor();
        }
    }

    /** Draws the photograph into a rect. Does nothing when there is none. */
    public void draw(CgUiPaintContext ctx, float x, float y, float width, float height) {
        if (picture == null) return;
        ctx.drawLayer(picture, x, y, width, height);
    }

    /** Frees the picture. Idempotent, and safe on a window that never minimised. */
    void dispose() {
        release();
        capturedWidth = 0f;
        capturedHeight = 0f;
    }

    private void release() {
        if (picture != null && owner != null) owner.releaseTexture(picture);
        picture = null;
        owner = null;
    }
}
