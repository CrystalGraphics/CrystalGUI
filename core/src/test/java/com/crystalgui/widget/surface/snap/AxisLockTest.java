package com.crystalgui.widget.surface.snap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>Shift's constraint is a latch, not a comparison.</b>
 *
 * <p>The version this replaces recomputed {@code abs(dx) >= abs(dy)} every frame, so a drag held near
 * the diagonal changed its mind between frames — and because the solver then moved the pinned axis
 * anyway, which axis appeared to work was a property of the document rather than of the code. Both
 * halves are asserted here; the solver's is in {@code SnapSolverTest}.</p>
 */
public class AxisLockTest {

    @Test
    public void nothingIsLockedUntilShiftIsDown() {
        AxisLock lock = new AxisLock();

        assertNull(lock.update(false, 40f, 2f));
        assertFalse(lock.isPinned(SnapAxis.HORIZONTAL));
        assertFalse(lock.isPinned(SnapAxis.VERTICAL));
    }

    @Test
    public void aHandMovingSidewaysKeepsTheHorizontalAndPinsTheVertical() {
        AxisLock lock = new AxisLock();

        assertEquals(SnapAxis.HORIZONTAL, lock.update(true, 40f, 3f));
        assertTrue(lock.isPinned(SnapAxis.VERTICAL));
        assertFalse(lock.isPinned(SnapAxis.HORIZONTAL));
    }

    /** The half that was reported as unimplemented. It is the same code, mirrored. */
    @Test
    public void aHandMovingDownKeepsTheVerticalAndPinsTheHorizontal() {
        AxisLock lock = new AxisLock();

        assertEquals(SnapAxis.VERTICAL, lock.update(true, 3f, 40f));
        assertTrue(lock.isPinned(SnapAxis.HORIZONTAL));
        assertFalse(lock.isPinned(SnapAxis.VERTICAL));
    }

    /**
     * <b>It does not change its mind.</b>
     *
     * <p>The pointer crosses the diagonal and keeps going the other way; the axis chosen when the hand
     * committed is the axis it keeps. Recomputing per frame is what made a slow diagonal flicker.</p>
     */
    @Test
    public void theAxisLatchesAndSurvivesCrossingTheDiagonal() {
        AxisLock lock = new AxisLock();
        assertEquals(SnapAxis.HORIZONTAL, lock.update(true, 20f, 4f));

        assertEquals(SnapAxis.HORIZONTAL, lock.update(true, 20f, 19f));
        assertEquals("it flipped the moment the other axis edged ahead",
                SnapAxis.HORIZONTAL, lock.update(true, 20f, 60f));
    }

    /** Nothing latches on a hand that has not committed, or the first pixel decides the gesture. */
    @Test
    public void aHandThatHasNotMovedCommitsToNothing() {
        AxisLock lock = new AxisLock();

        assertNull(lock.update(true, 0.5f, 0.5f));
    }

    /** Releasing Shift re-arms, so a wrong guess is recoverable without letting go of the mouse. */
    @Test
    public void lettingShiftGoRearmsIt() {
        AxisLock lock = new AxisLock();
        assertEquals(SnapAxis.HORIZONTAL, lock.update(true, 40f, 4f));

        assertNull(lock.update(false, 40f, 4f));

        assertEquals("it stayed on the axis the first press chose",
                SnapAxis.VERTICAL, lock.update(true, 4f, 40f));
    }
}
