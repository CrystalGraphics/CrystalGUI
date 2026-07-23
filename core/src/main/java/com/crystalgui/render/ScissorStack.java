package com.crystalgui.render;

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
     * @param x left edge in screen pixels
     * @param y top edge in screen pixels
     * @param w width in screen pixels
     * @param h height in screen pixels
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

    /** Clear all scissor rects. Call at frame start. */
    public void reset() {
        depth = 0;
    }


    public void applyScissorIfNeeded() {
        if (this.hasScissor()) {
            CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(
                    this.currentX(),
                    this.currentY(),
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
