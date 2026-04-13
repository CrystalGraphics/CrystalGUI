package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.state.CgScissorRect;
import org.lwjgl.opengl.GL11;

/**
 * Allocation-free scissor rectangle stack for nested clip regions.
 *
 * <p>Uses a preallocated pool of {@link CgScissorRect} objects for hot-path
 * storage while exposing a clean push/pop API. Each {@link #push(int, int, int, int)}
 * call intersects the new rectangle with the current top-of-stack so that child
 * clips never exceed their parent's visible area.</p>
 *
 * <h3>Dual-mode operation (V3.1)</h3>
 * <p>This class serves two purposes:</p>
 * <ol>
 *   <li><strong>Logical-only</strong> — during draw-list recording, the stack tracks
 *       nested clips without issuing GL calls. Use {@link #push(int, int, int, int)}
 *       and {@link #pop()} without calling {@link #applyCurrentGl()}.</li>
 *   <li><strong>GL apply helper</strong> — during replay or legacy typed-layer paths,
 *       {@link #applyCurrentGl()} and {@link #disableGl()} issue the actual GL
 *       scissor state changes.</li>
 * </ol>
 *
 * <p><strong>Flush-before-scissor invariant</strong>: callers that use the GL apply
 * path must flush all dirty layers before calling {@link #push(int, int, int, int)}
 * or {@link #pop()} to avoid scissor state bleeding into already-queued geometry.
 * In the draw-list recording path, scissor is tracked logically and applied during
 * replay, so the flush invariant is handled by the executor.</p>
 *
 * <p>Maximum nesting depth is {@value #MAX_DEPTH}.  Exceeding it throws
 * {@link IllegalStateException}.</p>
 */
public final class ScissorStack {

    /** Maximum nesting depth for scissor rectangles. */
    static final int MAX_DEPTH = 16;

    /** Preallocated pool of scissor rects — never reallocated. */
    private final CgScissorRect[] pool = new CgScissorRect[MAX_DEPTH];

    /** Current stack depth (0 = empty). */
    private int depth;

    /** Whether GL_SCISSOR_TEST is currently enabled by this stack. */
    private boolean glActive;

    public ScissorStack() {
        for (int i = 0; i < MAX_DEPTH; i++) {
            pool[i] = new CgScissorRect();
        }
    }

    /**
     * Pushes a new scissor rectangle, intersecting it with the current
     * top-of-stack if one exists. Does NOT issue GL calls — call
     * {@link #applyCurrentGl()} separately if GL state change is needed.
     *
     * @param x x-origin of the scissor rect (screen-space pixels)
     * @param y y-origin of the scissor rect (screen-space pixels)
     * @param w width  of the scissor rect (pixels, must be ≥ 0)
     * @param h height of the scissor rect (pixels, must be ≥ 0)
     * @return the effective (intersected) scissor rect at the new top of stack
     * @throws IllegalStateException if maximum nesting depth is exceeded
     */
    public CgScissorRect push(int x, int y, int w, int h) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                "ScissorStack overflow: maximum depth " + MAX_DEPTH + " exceeded");
        }

        // Intersect with current top-of-stack if one exists
        if (depth > 0) {
            CgScissorRect parent = pool[depth - 1];
            int px = parent.getX();
            int py = parent.getY();
            int pw = parent.getWidth();
            int ph = parent.getHeight();

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

        CgScissorRect rect = pool[depth];
        rect.set(x, y, w, h);
        depth++;
        return rect;
    }

    /**
     * Pops the current scissor rectangle. Does NOT issue GL calls — call
     * {@link #applyCurrentGl()} or {@link #disableGl()} separately as needed.
     *
     * @throws IllegalStateException if the stack is already empty
     */
    public void pop() {
        if (depth <= 0) {
            throw new IllegalStateException("ScissorStack underflow: pop() on empty stack");
        }
        depth--;
    }

    /**
     * Returns the current top-of-stack scissor rect, or {@code null} if the
     * stack is empty.
     */
    public CgScissorRect current() {
        return depth > 0 ? pool[depth - 1] : null;
    }

    /** Returns the current nesting depth (0 = no active scissor). */
    public int getDepth() {
        return depth;
    }

    /** Returns whether GL_SCISSOR_TEST is currently enabled by this stack. */
    public boolean isGlActive() {
        return glActive;
    }

    // ── GL apply helpers (replay / legacy path) ─────────────────────────

    /**
     * Applies the current top-of-stack scissor rect to GL. Enables
     * {@code GL_SCISSOR_TEST} if not already active. If the stack is
     * empty, disables scissor test.
     */
    public void applyCurrentGl() {
        if (depth == 0) {
            disableGl();
            return;
        }
        if (!glActive) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            glActive = true;
        }
        pool[depth - 1].applyGl();
    }

    /**
     * Disables {@code GL_SCISSOR_TEST} without modifying the logical stack.
     */
    public void disableGl() {
        if (glActive) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            glActive = false;
        }
    }

    /**
     * Disables scissor testing and resets the stack, regardless of current depth.
     *
     * <p>Called at frame/pass end to ensure clean GL state.</p>
     */
    public void disable() {
        disableGl();
        depth = 0;
    }

    /**
     * Resets the stack to empty state without issuing GL calls.
     *
     * <p>Called at the start of a new frame/pass when GL state is already known-clean.</p>
     */
    public void reset() {
        depth = 0;
        glActive = false;
    }
}
