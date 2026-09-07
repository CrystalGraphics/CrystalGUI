package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>Holding Shift must not make the box jump.</b>
 *
 * <p>The aspect lock compared the raw pixel deltas — {@code |dw|} against {@code |dh|} — and then applied
 * the aspect RATIO to whichever won. Those are different units, so the two arms disagreed at the very
 * point they swapped, and a hand moving near a diagonal flips the dominant axis constantly. Reported as
 * the box resizing "in bursts".</p>
 *
 * <p>What is asserted is CONTINUITY, which is the property that was actually violated — not a particular
 * size, which would only restate the arithmetic.</p>
 */
public class AspectLockIsContinuousTest {

    private static final float W = 214f;
    private static final float H = 99f;

    /** Sweeping the other axis through the crossover must never move the box by a jump. */
    @Test
    public void thereIsNoJumpWhereTheDominantAxisSwaps() {
        float previousWidth = Float.NaN;
        float previousHeight = Float.NaN;
        for (float dy = 0f; dy <= 60f; dy += 1f) {
            float[] locked = ResizeHandles.lockAspect(W, H, W + 10f, H + dy);
            if (!Float.isNaN(previousWidth)) {
                // A one-pixel step of input may not move a side by more than a few pixels. The old
                // version moved the width from 224 to 238 in one such step.
                assertTrue("the box jumped at dy=" + dy + ": width " + previousWidth
                                + " -> " + locked[0], Math.abs(locked[0] - previousWidth) < 4f);
                assertTrue("the box jumped at dy=" + dy + ": height " + previousHeight
                                + " -> " + locked[1], Math.abs(locked[1] - previousHeight) < 4f);
            }
            previousWidth = locked[0];
            previousHeight = locked[1];
        }
    }

    /** And it is still an aspect lock: the shape never changes, whichever axis is leading. */
    @Test
    public void theShapeIsKept() {
        float ratio = H / W;
        for (float dy = -40f; dy <= 60f; dy += 7f) {
            float[] locked = ResizeHandles.lockAspect(W, H, W + 10f, H + dy);
            assertEquals("the aspect ratio drifted at dy=" + dy,
                    ratio, locked[1] / locked[0], 0.001f);
        }
    }

    /**
     * <b>The straddling case, which is the one that shipped broken twice.</b>
     *
     * <p>One axis growing while the other shrinks puts the two proposed scales on opposite sides of 1.
     * A branch picking the larger departure from 1 is discontinuous exactly there — it jumped from x1.05
     * to x0.95, measured as 208x97 collapsing to 86x40 on the smallest movement.</p>
     */
    @Test
    public void thereIsNoJumpWhenOneAxisGrowsAndTheOtherShrinks() {
        float previous = Float.NaN;
        // Width shrinking steadily while the height grows: the scales cross 1 in opposite directions.
        for (float step = -30f; step <= 30f; step += 1f) {
            float[] locked = ResizeHandles.lockAspect(W, H, W - step, H + step);
            if (!Float.isNaN(previous)) {
                assertTrue("the box jumped at step=" + step + ": width " + previous
                        + " -> " + locked[0], Math.abs(locked[0] - previous) < 4f);
            }
            previous = locked[0];
        }
    }

    /** Dragging outward grows and inward shrinks — the projection must not invert the response. */
    @Test
    public void theResponseFollowsTheHand() {
        float[] out = ResizeHandles.lockAspect(W, H, W + 20f, H + 10f);
        float[] in = ResizeHandles.lockAspect(W, H, W - 20f, H - 10f);
        assertTrue("dragging outward should grow the box", out[0] > W);
        assertTrue("dragging inward should shrink it", in[0] < W);
    }

    /** Neither side may collapse, and the shape survives the floor. */
    @Test
    public void aCollapsedDragKeepsItsShape() {
        float[] locked = ResizeHandles.lockAspect(W, H, -500f, -500f);
        assertTrue("a side collapsed to nothing", locked[0] >= 1f && locked[1] >= 1f);
        assertEquals("clamping a side lost the shape", H / W, locked[1] / locked[0], 0.001f);
    }
}
