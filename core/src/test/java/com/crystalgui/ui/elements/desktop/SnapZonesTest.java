package com.crystalgui.ui.elements.desktop;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The drag-to-edge arithmetic — CrystalOS <b>W13b</b>.
 *
 * <p>Pure functions over a rect, so every awkward case is reachable here rather than only by dragging:
 * a band against a work area that has not been laid out, a corner that two zones both claim, an odd
 * width that leaves a gap down the middle.</p>
 */
public class SnapZonesTest {

    private static final float W = 800f;
    private static final float H = 600f;

    @Test
    public void theMiddleOfTheScreenSnapsToNothing() {
        assertNull(SnapZones.forPoint(400f, 300f, W, H));
    }

    @Test
    public void theSideBandsTakeTheHalves() {
        assertEquals(SnapZones.Zone.LEFT, SnapZones.forPoint(2f, 300f, W, H));
        assertEquals(SnapZones.Zone.RIGHT, SnapZones.forPoint(W - 2f, 300f, W, H));
    }

    @Test
    public void theTopBandMaximises() {
        assertEquals(SnapZones.Zone.MAXIMIZE, SnapZones.forPoint(400f, 1f, W, H));
    }

    /**
     * <b>A corner belongs to the top band, not to a half.</b>
     *
     * <p>Dragging into the top-left corner is how somebody maximises a window they are already moving
     * leftwards. Resolving it as {@code LEFT} would make the top band unreachable from either end, since
     * the two side bands meet it at both corners.</p>
     */
    @Test
    public void aCornerBelongsToTheTopBand() {
        assertEquals(SnapZones.Zone.MAXIMIZE, SnapZones.forPoint(1f, 1f, W, H));
        assertEquals(SnapZones.Zone.MAXIMIZE, SnapZones.forPoint(W - 1f, 1f, W, H));
    }

    /**
     * <b>The top band is thinner than the sides, and that is deliberate.</b>
     *
     * <p>A window is dragged by its caption, so the pointer sits near the top of the work area for the
     * whole of an ordinary move — a generous top band would maximise windows people were only
     * rearranging.</p>
     */
    @Test
    public void theTopBandIsThinnerThanTheSides() {
        assertNull("the top band is as deep as the side bands",
                SnapZones.forPoint(400f, SnapZones.SIDE_BAND, W, H));
        assertEquals(SnapZones.Zone.MAXIMIZE,
                SnapZones.forPoint(400f, SnapZones.TOP_BAND, W, H));
    }

    /**
     * <b>An unmeasured work area answers nothing rather than guessing.</b>
     *
     * <p>The window layer measures 0x0 before its first layout, and every rule that reads the work area
     * is guarded that way — a zone chosen against nothing would snap a window to a rect of nothing.</p>
     */
    @Test
    public void anUnmeasuredWorkAreaHasNoZones() {
        assertNull(SnapZones.forPoint(0f, 0f, 0f, 0f));
        assertNull(SnapZones.forPoint(2f, 2f, W, 0f));
    }

    /** A pointer dragged off the bottom of the work area is in no zone. */
    @Test
    public void aPointerOutsideTheAreaHasNoZone() {
        assertNull(SnapZones.forPoint(2f, H + 40f, W, H));
        assertNull(SnapZones.forPoint(2f, -4f, W, H));
    }

    /**
     * <b>The halves tile exactly — no column of desktop down the middle.</b>
     *
     * <p>Both taking {@code width / 2} leaves an odd work area one pixel short, which looks like a
     * rendering bug rather than arithmetic. The right half takes the remainder.</p>
     */
    @Test
    public void theHalvesTileAnOddWidthExactly() {
        float odd = 801f;
        float[] left = SnapZones.rectFor(SnapZones.Zone.LEFT, odd, H);
        float[] right = SnapZones.rectFor(SnapZones.Zone.RIGHT, odd, H);

        assertEquals("the halves leave a gap", odd, left[2] + right[2], 0.001f);
        assertEquals("the right half does not start where the left ends", left[0] + left[2], right[0], 0.001f);
    }

    @Test
    public void maximiseIsTheWholeArea() {
        assertArrayEquals(new float[] {0f, 0f, W, H},
                SnapZones.rectFor(SnapZones.Zone.MAXIMIZE, W, H), 0.001f);
    }
}
