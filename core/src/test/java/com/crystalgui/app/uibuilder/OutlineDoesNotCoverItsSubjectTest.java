package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.CanvasRects;

/**
 * <b>An outline hugs its subject from outside, never over it.</b>
 *
 * <p>All four strokes used to be drawn inside the rectangle, so the outline covered the outermost pixels
 * of the very thing it points at. Invisible on a box with padding; obvious on one whose content reaches
 * its edge — a slider's thumb is flush against the control's left edge at minimum value, so the stroke
 * ran through it and read as the thumb spilling out of its own box. Nothing was spilling.</p>
 *
 * <p>The four fills are one SDF ring now, so what there is to assert is the ring's rectangle: its stroke
 * runs <em>inward</em> from its own edge, which puts the band between the ring and the subject exactly
 * when the ring is inflated by the thickness on every side.</p>
 */
public class OutlineDoesNotCoverItsSubjectTest {

    @Test
    public void theRingSitsWhollyOutsideTheRectangle() {
        float[] rect = {100f, 50f, 40f, 20f};
        float[] ring = CanvasRects.outlineRing(rect, 2f);

        // Inflated by the thickness on each side: the inward stroke then lands in the band between the
        // ring's edge and the subject's, and touches the subject's outermost pixel without covering it.
        assertEquals(98f, ring[0], 0.01f);
        assertEquals(48f, ring[1], 0.01f);
        assertEquals(44f, ring[2], 0.01f);
        assertEquals(24f, ring[3], 0.01f);
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
        float[] ring = CanvasRects.outlineRing(new float[] {0f, 0f, 3f, 2f}, 2f);
        assertEquals(7f, ring[2], 0.01f);
        assertEquals(6f, ring[3], 0.01f);
    }

    /** Nothing to outline is nothing drawn, rather than a zero-sized artefact. */
    @Test
    public void anEmptyRectangleDrawsNothing() {
        assertNull(CanvasRects.outlineRing(null, 2f));
        assertNull(CanvasRects.outlineRing(new float[] {0f, 0f, 0f, 10f}, 2f));
    }
}
