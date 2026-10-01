package com.crystalgui.render;

import java.util.Arrays;

import com.crystalgraphics.render.graph.CgPassRecorder;

/**
 * Allocation-free nested clip region stack.
 *
 * <p>Each level is a rect in the target's physical pixels, top-left origin, kept both as pushed and intersected with
 * every level above it — what culling asks. A level pushed under a spatial node also keeps its clip in that node's
 * space, which is what is recorded, so it moves with the node.</p>
 *
 * <p>An applied stack becomes the scissor chain of the chunks its recorder records after it; nothing here touches
 * GL.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ScissorStack stack = new ScissorStack(recorder);
 * stack.pushScissor(0, 0, 400, 300);   // full screen
 * stack.pushScissor(50, 50, 200, 200); // nested clip
 * stack.applyScissorIfNeeded(targetHeight);   // chunks recorded from here are clipped to (50,50,200,200)
 * stack.popScissor();                   // back to (0,0,400,300)
 * stack.reset();                        // clear all
 * }</pre>
 */
public final class ScissorStack {

    /** How deep clips nest; the recorder's chain is as deep. */
    public static final int MAX_DEPTH = CgPassRecorder.MAX_SCISSORS;

    /** Per level, x, y, w, h: cut by every level above it, and as pushed. */
    private final int[] stack = new int[MAX_DEPTH * 4];
    private final int[] own = new int[MAX_DEPTH * 4];
    /** Per level, the spatial node it was pushed under and its clip there, x0, y0, x1, y1; unused for node 0. */
    private final int[] nodes = new int[MAX_DEPTH];
    private final float[] boxes = new float[MAX_DEPTH * 4];
    private int depth;
    /** Where an applied stack goes: the scissor chain of the chunks recorded after it. */
    private final CgPassRecorder recorder;

    public ScissorStack(CgPassRecorder recorder) {
        this.recorder = recorder;
    }

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
        return pushScissor(x, y, w, h, 0, 0f, 0f, 0f, 0f);
    }

    /**
     * As {@link #pushScissor(int, int, int, int)}, for a clip under spatial node {@code node}: {@code (x0, y0)-(x1,
     * y1)} is the same clip in the node's space, top-down, and is what the recorder is given.
     */
    public ScissorStack pushScissor(int x, int y, int w, int h, int node, float x0, float y0, float x1, float y1) {
        if (depth == MAX_DEPTH) throw new IllegalStateException("clips nest " + MAX_DEPTH + " deep at most");
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
        own[base] = x;
        own[base + 1] = y;
        own[base + 2] = w;
        own[base + 3] = h;
        nodes[depth] = node;
        boxes[base] = x0;
        boxes[base + 1] = y0;
        boxes[base + 2] = x1;
        boxes[base + 3] = y1;
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

    /** A stack set aside by {@link #suspend}: opaque but for {@link #shifted}. */
    public static final class Saved {
        private final int[] stack, own, nodes;
        private final float[] boxes;

        private Saved(int[] stack, int[] own, int[] nodes, float[] boxes) {
            this.stack = stack;
            this.own = own;
            this.nodes = nodes;
            this.boxes = boxes;
        }

        /**
         * The same clips in a target whose pixel (0,0) is at {@code (-dx, -dy)} of this one's: every rect in target
         * pixels moves, and a clip in a node's space does not, since the target's place is the pass's to say.
         */
        public Saved shifted(int dx, int dy) {
            if (dx == 0 && dy == 0 || stack.length == 0) return this;
            int[] movedStack = stack.clone(), movedOwn = own.clone();
            for (int i = 0; i < movedStack.length; i += 4) {
                movedStack[i] += dx;
                movedStack[i + 1] += dy;
                movedOwn[i] += dx;
                movedOwn[i + 1] += dy;
            }
            return new Saved(movedStack, movedOwn, nodes, boxes);
        }
    }

    /**
     * Sets the whole stack aside, so a render into a target with its OWN coordinate space starts from
     * no clip at all; {@link #resume} puts it back exactly as it was.
     *
     * <p>{@link #clearScissorIfNeeded} is not this: it only lifts the scissor, and only when the
     * stack is already empty. A rect inherited from an ancestor stays on the stack, and every push made
     * during the nested render is INTERSECTED with it — in the ancestor's screen pixels, against a
     * target that is not the screen. A window photographed under any enclosing clip came out cut along
     * whatever line that clip happened to be. The rects are COPIED out rather than merely hidden behind
     * {@code depth = 0}, because the nested render's own pushes overwrite the same slots.</p>
     *
     * @return the token {@link #resume} takes
     */
    public Saved suspend() {
        Saved saved = new Saved(Arrays.copyOf(stack, depth * 4), Arrays.copyOf(own, depth * 4),
                Arrays.copyOf(nodes, depth), Arrays.copyOf(boxes, depth * 4));
        depth = 0;
        return saved;
    }

    /** Restores what {@link #suspend} set aside. Does not set the recorder's scissor — apply or clear afterwards. */
    public void resume(Saved saved) {
        System.arraycopy(saved.stack, 0, stack, 0, saved.stack.length);
        System.arraycopy(saved.own, 0, own, 0, saved.own.length);
        System.arraycopy(saved.nodes, 0, nodes, 0, saved.nodes.length);
        System.arraycopy(saved.boxes, 0, boxes, 0, saved.boxes.length);
        depth = saved.nodes.length;
    }


    /**
     * Sets the stack as the recorder's scissor chain, each rect flipped against {@code targetHeight} — the height of
     * the target being drawn into <em>right now</em>. Each level goes as pushed, and the executor cuts them
     * together: under a spatial node a level is the node's clip, which moves with it.
     *
     * <p><b>The stack holds TOP-LEFT rects and the flip happens here, per target</b>, because a scissor
     * rect is bottom-left-origin pixels of a particular buffer and means nothing in a buffer of
     * another height. It used to hold GL rects, flipped once at push time against the screen, which is
     * the same thing for as long as every target is the screen's size — every pooled layer is. The first
     * target that was not, a window's snapshot, showed what that assumption costs: a clip pushed against
     * the snapshot (a few hundred pixels tall) was inherited by the screen-sized pool layers begun inside
     * it, where the same numbers describe a band at the BOTTOM of the layer, so every masked or faded
     * element in the photograph was clipped to nothing and one scrolled one resurfaced displaced. Kept
     * in the target-independent orientation, a rect can be re-applied to whichever buffer is bound.</p>
     */
    public void applyScissorIfNeeded(int targetHeight) {
        if (!hasScissor()) return;
        recorder.noScissor();
        for (int i = 0; i < depth; i++) {
            int o = i * 4;
            if (nodes[i] == 0) {
                recorder.pushScissor(own[o], targetHeight - (own[o + 1] + own[o + 3]), own[o + 2], own[o + 3]);
            } else {
                recorder.pushScissor(nodes[i], boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3]);
            }
        }
    }

    /** Lifts the recorder's scissor once no rect remains active (stack fully popped). */
    public void clearScissorIfNeeded() {
        if (!this.hasScissor()) {
            recorder.noScissor();
        }
    }
}
