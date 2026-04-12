package com.crystalgui.core.render;

import org.lwjgl.opengl.GL11;

/**
 * Allocation-free scissor rectangle stack for nested clip regions.
 *
 * <p>Wraps a flat {@code int[]} for hot-path storage while exposing a clean
 * push/pop API.  Each {@link #push(int, int, int, int)} call intersects the
 * new rectangle with the current top-of-stack so that child clips never
 * exceed their parent's visible area.</p>
 *
 * <p><strong>Flush-before-scissor invariant</strong>: callers (i.e.
 * {@link CgUIRenderContext}) must flush all dirty layers before calling
 * {@link #push(int, int, int, int)} or {@link #pop()} to avoid scissor
 * state bleeding into already-queued geometry.</p>
 *
 * <p>Maximum nesting depth is {@value #MAX_DEPTH}.  Exceeding it throws
 * {@link IllegalStateException}.</p>
 */
public final class ScissorStack {

    /** Maximum nesting depth for scissor rectangles. */
    static final int MAX_DEPTH = 16;

    /**
     * Flat storage: 4 ints per entry (x, y, w, h).
     * Index into this array = depth * 4.
     */
    private final int[] stack = new int[MAX_DEPTH * 4];

    /** Current stack depth (0 = empty). */
    private int depth;

    /** Whether GL_SCISSOR_TEST is currently enabled by this stack. */
    private boolean active;

    /**
     * Pushes a new scissor rectangle, intersecting it with the current
     * top-of-stack if one exists.
     *
     * <p>Enables {@code GL_SCISSOR_TEST} on the first push and applies
     * the intersected rectangle via {@code glScissor}.</p>
     *
     * @param x x-origin of the scissor rect (screen-space pixels)
     * @param y y-origin of the scissor rect (screen-space pixels)
     * @param w width  of the scissor rect (pixels, must be ≥ 0)
     * @param h height of the scissor rect (pixels, must be ≥ 0)
     * @throws IllegalStateException if maximum nesting depth is exceeded
     */
    public void push(int x, int y, int w, int h) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                "ScissorStack overflow: maximum depth " + MAX_DEPTH + " exceeded");
        }

        // Intersect with current top-of-stack if one exists
        if (depth > 0) {
            int base = (depth - 1) * 4;
            int px = stack[base];
            int py = stack[base + 1];
            int pw = stack[base + 2];
            int ph = stack[base + 3];

            // Compute intersection
            int ix = Math.max(x, px);
            int iy = Math.max(y, py);
            int ix2 = Math.min(x + w, px + pw);
            int iy2 = Math.min(y + h, py + ph);

            x = ix;
            y = iy;
            w = Math.max(0, ix2 - ix);
            h = Math.max(0, iy2 - iy);
        }

        // Store the (possibly intersected) rectangle
        int offset = depth * 4;
        stack[offset] = x;
        stack[offset + 1] = y;
        stack[offset + 2] = w;
        stack[offset + 3] = h;
        depth++;

        // Enable scissor test on first push
        if (!active) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            active = true;
        }

        GL11.glScissor(x, y, w, h);
    }

    /**
     * Pops the current scissor rectangle and restores the previous one.
     *
     * <p>If the stack becomes empty, disables {@code GL_SCISSOR_TEST}.</p>
     *
     * @throws IllegalStateException if the stack is already empty
     */
    public void pop() {
        if (depth <= 0) {
            throw new IllegalStateException("ScissorStack underflow: pop() on empty stack");
        }

        depth--;

        if (depth == 0) {
            // Stack is now empty — disable scissor test
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            active = false;
        } else {
            // Restore previous rectangle
            int offset = (depth - 1) * 4;
            GL11.glScissor(
                stack[offset],
                stack[offset + 1],
                stack[offset + 2],
                stack[offset + 3]
            );
        }
    }

    /**
     * Disables scissor testing and resets the stack, regardless of current depth.
     *
     * <p>Called at frame/pass end to ensure clean GL state.</p>
     */
    public void disable() {
        if (active) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            active = false;
        }
        depth = 0;
    }

    /**
     * Resets the stack to empty state without issuing GL calls.
     *
     * <p>Called at the start of a new frame/pass when GL state is already known-clean.</p>
     */
    public void reset() {
        depth = 0;
        active = false;
    }

    /** Returns the current nesting depth (0 = no active scissor). */
    public int getDepth() {
        return depth;
    }

    /** Returns whether the scissor test is currently active. */
    public boolean isActive() {
        return active;
    }
}
