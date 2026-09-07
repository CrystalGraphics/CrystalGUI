package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.CanvasRects;

/**
 * <b>An outline hugs its subject from outside, never over it.</b>
 *
 * <p>All four strokes used to be drawn inside the rectangle, so the outline covered the outermost pixels
 * of the very thing it points at. Invisible on a box with padding; obvious on one whose content reaches
 * its edge — a slider's thumb is flush against the control's left edge at minimum value, so the stroke
 * ran through it and read as the thumb spilling out of its own box. Nothing was spilling.</p>
 */
public class OutlineDoesNotCoverItsSubjectTest {

    @Test
    public void noStrokeEntersTheRectangle() {
        float[] rect = {100f, 50f, 40f, 20f};
        for (float[] side : CanvasRects.outlineSides(rect, 2f)) {
            assertTrue("a stroke covers what it outlines: " + describe(side), outside(rect, side));
        }
    }

    /**
     * <b>A thin box keeps a full-thickness outline.</b>
     *
     * <p>Drawing inside meant two opposite strokes met on a box thinner than twice the thickness, so the
     * thickness was halved to stop the outline painting the box solid — a 2px-tall element got a 1px
     * one. A ring outside never overlaps itself, so the clamp is gone.</p>
     */
    @Test
    public void aThinBoxKeepsItsThickness() {
        float[] rect = {0f, 0f, 3f, 2f};
        for (float[] side : CanvasRects.outlineSides(rect, 2f)) {
            assertTrue("a stroke was thinned on a small box: " + describe(side),
                    side[2] >= 2f - 0.01f && side[3] >= 2f - 0.01f);
        }
    }

    /** Nothing to outline is nothing drawn, rather than a zero-sized artefact. */
    @Test
    public void anEmptyRectangleDrawsNothing() {
        assertEquals(0, CanvasRects.outlineSides(null, 2f).length);
        assertEquals(0, CanvasRects.outlineSides(new float[] {0f, 0f, 0f, 10f}, 2f).length);
    }

    /** No overlap at all between the stroke and the rectangle's own area. */
    private static boolean outside(float[] rect, float[] side) {
        return side[0] + side[2] <= rect[0] + 0.01f
                || side[0] >= rect[0] + rect[2] - 0.01f
                || side[1] + side[3] <= rect[1] + 0.01f
                || side[1] >= rect[1] + rect[3] - 0.01f;
    }

    private static String describe(float[] side) {
        return side[0] + "," + side[1] + " " + side[2] + "x" + side[3];
    }
}
