package com.crystalgui.render;

import java.util.Arrays;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Allocation-free nested clip region stack.
 *
 * <p>Uses an inline {@code int[]} array (capacity 64 = 16 depth levels × 4 ints
 * per rect: x, y, w, h) to track nested scissor rectangles. Zero heap allocation
 * on push/pop — pure primitive int stack with zero GC pressure.</p>
 *
 * <p>This is a <strong>logical-only</strong> data structure. GL scissor application
 * is done by {@code CgUiPaintContext} at draw time, not by this class.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ScissorStack stack = new ScissorStack();
 * stack.pushScissor(0, 0, 400, 300);   // full screen
 * stack.pushScissor(50, 50, 200, 200); // nested clip
 * // ... draw calls here, clipped to (50,50,200,200) ...
 * stack.popScissor();                   // back to (0,0,400,300)
 * stack.reset();                        // clear all
 * }</pre>
 */
public final class ScissorStack {

    /** 16 depth levels × 4 ints per rect (x, y, w, h). */
    private final int[] stack = new int[64];
    private int depth;

    /**
     * Push a new scissor rect. If a parent scissor is active, the new rect
     * is intersected with the parent. The result is stored on the stack.
     *
     * @param x left edge in the target's physical pixels, top-left origin
     * @param y top edge in the target's physical pixels, top-left origin
     * @param w width in physical pixels
     * @param h height in physical pixels
     */
    public ScissorStack pushScissor(int x, int y, int w, int h) {
        int ix = x, iy = y, iw = w, ih = h;

        if (depth > 0) {
            int base = (depth - 1) * 4;
            int px = stack[base];
            int py = stack[base + 1];
            int pw = stack[base + 2];
            int ph = stack[base + 3];

            ix = Math.max(x, px);
            iy = Math.max(y, py);
            int right = Math.min(x + w, px + pw);
            int bottom = Math.min(y + h, py + ph);
            iw = Math.max(0, right - ix);
            ih = Math.max(0, bottom - iy);
        }

        int base = depth * 4;
        stack[base] = ix;
        stack[base + 1] = iy;
        stack[base + 2] = iw;
        stack[base + 3] = ih;
        depth++;
        return this;
    }

    /** Remove the topmost scissor rect. No-op if stack is empty. */
    public ScissorStack popScissor() {
        if (depth > 0) {
            depth--;
        }
        return this;
    }

    /** @return current scissor left edge, or 0 if no scissor is active */
    public int currentX() {
        return depth > 0 ? stack[(depth - 1) * 4] : 0;
    }

    /** @return current scissor top edge, or 0 if no scissor is active */
    public int currentY() {
        return depth > 0 ? stack[(depth - 1) * 4 + 1] : 0;
    }

    /** @return current scissor width, or 0 if no scissor is active */
    public int currentW() {
        return depth > 0 ? stack[(depth - 1) * 4 + 2] : 0;
    }

    /** @return current scissor height, or 0 if no scissor is active */
    public int currentH() {
        return depth > 0 ? stack[(depth - 1) * 4 + 3] : 0;
    }

    /** @return true if at least one scissor rect is active */
    public boolean hasScissor() {
        return depth > 0;
    }

    /** Number of nested scissor rects currently pushed. Zero between balanced frames — which is what
     * {@code UIWindow.paintTopLayer} asserts before starting its own pass, so an unbalanced
     * push/pop in the main tree is reported at its cause rather than as a mystery clip later. */
    public int depth() {
        return depth;
    }

    /** Clear all scissor rects. Call at frame start. */
    public void reset() {
        depth = 0;
    }

    /**
     * Sets the whole stack aside, so a render into a target with its OWN coordinate space starts from
     * no clip at all; {@link #resume} puts it back exactly as it was.
     *
     * <p>{@link #clearScissorIfNeeded} is not this: it only disables the GL test, and only when the
     * stack is already empty. A rect inherited from an ancestor stays on the stack, and every push made
     * during the nested render is INTERSECTED with it — in the ancestor's screen pixels, against a
     * target that is not the screen. A window photographed under any enclosing clip came out cut along
     * whatever line that clip happened to be. The rects are COPIED out rather than merely hidden behind
     * {@code depth = 0}, because the nested render's own pushes overwrite the same slots.</p>
     *
     * @return the token {@link #resume} takes; opaque to the caller
     */
    public int[] suspend() {
        int[] saved = Arrays.copyOf(stack, depth * 4);
        depth = 0;
        return saved;
    }

    /** Restores what {@link #suspend} set aside. Does not touch GL state — apply or clear afterwards. */
    public void resume(int[] saved) {
        System.arraycopy(saved, 0, stack, 0, saved.length);
        depth = saved.length / 4;
    }


    /**
     * Applies the current rect to GL, flipped against {@code targetHeight} — the height of the buffer
     * being drawn into <em>right now</em>.
     *
     * <p><b>The stack holds TOP-LEFT rects and the flip happens here, per target</b>, because a GL
     * scissor rect is bottom-left-origin pixels of a particular buffer and means nothing in a buffer of
     * another height. It used to hold GL rects, flipped once at push time against the screen, which is
     * the same thing for as long as every target is the screen's size — every pooled layer is. The first
     * target that was not, a window's snapshot, showed what that assumption costs: a clip pushed against
     * the snapshot (a few hundred pixels tall) was inherited by the screen-sized pool layers begun inside
     * it, where the same numbers describe a band at the BOTTOM of the layer, so every masked or faded
     * element in the photograph was clipped to nothing and one scrolled one resurfaced displaced. Kept
     * in the target-independent orientation, a rect can be re-applied to whichever buffer is bound.</p>
     */
    public void applyScissorIfNeeded(int targetHeight) {
        if (this.hasScissor()) {
            CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(
                    this.currentX(),
                    targetHeight - (this.currentY() + this.currentH()),
                    this.currentW(),
                    this.currentH());
        }
    }

    /** Disables {@code GL_SCISSOR_TEST} once no scissor rect remains active (stack fully popped). */
    public void clearScissorIfNeeded() {
        if (!this.hasScissor()) {
            CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
        }
    }
}
