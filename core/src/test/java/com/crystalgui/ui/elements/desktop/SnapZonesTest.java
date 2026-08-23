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

    /** A window's caption, which is what the top band is measured in. @see SnapZones#forPoint */
    private static final float CAPTION = 20f;

    private static SnapZones.Zone at(float x, float y) {
        return SnapZones.forPoint(x, y, W, H, CAPTION);
    }

    @Test
    public void theMiddleOfTheScreenSnapsToNothing() {
        assertNull(at(400f, 300f));
    }

    @Test
    public void contactingASideEdgeTakesTheHalf() {
        assertEquals(SnapZones.Zone.LEFT, at(0f, 300f));
        assertEquals(SnapZones.Zone.RIGHT, at(W, 300f));
    }

    /**
     * <b>CONTACT, not proximity — the regression this class was rewritten for.</b>
     *
     * <p>Windows fires when the cursor reaches the last row of pixels at the monitor's edge, and that is
     * what makes a tiny trigger feel deliberate: the edge is a wall, so the gesture is to shove the
     * pointer into it. A generous band fires while merely crossing the area, which is what the first
     * version did with twelve pixels.</p>
     *
     * <p>Twelve is named here rather than "some larger number" because it is the constant that shipped.
     * </p>
     */
    @Test
    public void merelyNearingAnEdgeSnapsToNothing() {
        assertNull("a pointer twelve pixels from the edge armed a snap", at(12f, 300f));
        assertNull(at(W - 12f, 300f));
    }

    /**
     * <b>...and past the edge is still at it.</b>
     *
     * <p>The counter-assertion the one above needs: an implementation that required the pointer to be
     * inside the area would satisfy it and make the gesture nearly impossible, since the window clamp
     * stops the window travelling well before the hand does.</p>
     */
    @Test
    public void pastAnEdgeIsStillAtIt() {
        assertEquals(SnapZones.Zone.LEFT, at(-60f, 300f));
        assertEquals(SnapZones.Zone.RIGHT, at(W + 60f, 300f));
    }

    /** The top edge, away from either end, maximises. */
    @Test
    public void theTopEdgeMaximises() {
        assertEquals(SnapZones.Zone.MAXIMIZE, at(400f, 1f));
    }

    /**
     * <b>A corner belongs to the QUARTER, where it used to belong to maximise.</b>
     *
     * <p>The old rule was right for the world it was written in: with no quarters, a corner that
     * resolved to {@code LEFT} would have made the top band unreachable from either end. It is wrong the
     * moment a quarter exists, because dragging into a corner obviously means the corner.</p>
     */
    @Test
    public void everyCornerTakesItsQuarter() {
        assertEquals(SnapZones.Zone.TOP_LEFT, at(0f, 0f));
        assertEquals(SnapZones.Zone.TOP_RIGHT, at(W, 0f));
        assertEquals(SnapZones.Zone.BOTTOM_LEFT, at(0f, H));
        assertEquals(SnapZones.Zone.BOTTOM_RIGHT, at(W, H));
    }

    /**
     * <b>The corner is the outer quarter of the edge — KWin's {@code ElectricBorderCornerRatio}.</b>
     *
     * <p>Asserted at the boundary in both directions, because a ratio is the one thing here that a
     * plausible-looking implementation can get half right: reading it as a fraction of the WIDTH, or
     * measuring the bottom corner from the top, both leave one of these four passing.</p>
     */
    @Test
    public void theCornerIsTheOuterQuarterOfTheEdge() {
        float corner = H * SnapZones.CORNER_RATIO;

        assertEquals(SnapZones.Zone.TOP_LEFT, at(0f, corner));
        assertEquals(SnapZones.Zone.LEFT, at(0f, corner + 1f));
        assertEquals(SnapZones.Zone.LEFT, at(0f, H - corner - 1f));
        assertEquals(SnapZones.Zone.BOTTOM_LEFT, at(0f, H - corner));
    }

    /**
     * <b>The top band is handed in, and it is the caption's height.</b>
     *
     * <p>Not a constant of this class, because the pointer rides inside the caption for the whole of a
     * drag and the window is clamped so the caption's top never leaves the work area — a band shallower
     * than the caption is unreachable for every grab but the shallowest. Since the pointer is inside the
     * caption by construction, "within one caption of the top" and "the window has reached the top" are
     * the same statement.</p>
     */
    @Test
    public void theTopBandIsWhateverTheCallerMeasured() {
        assertEquals(SnapZones.Zone.MAXIMIZE, SnapZones.forPoint(400f, 20f, W, H, 20f));
        assertNull(SnapZones.forPoint(400f, 21f, W, H, 20f));
        assertEquals("a deeper caption did not deepen the band",
                SnapZones.Zone.MAXIMIZE, SnapZones.forPoint(400f, 21f, W, H, 30f));
    }

    /**
     * <b>An unmeasured work area answers nothing rather than guessing.</b>
     *
     * <p>The window layer measures 0x0 before its first layout, and every rule that reads the work area
     * is guarded that way — a zone chosen against nothing would snap a window to a rect of nothing.</p>
     */
    @Test
    public void anUnmeasuredWorkAreaHasNoZones() {
        assertNull(SnapZones.forPoint(0f, 0f, 0f, 0f, CAPTION));
        assertNull(SnapZones.forPoint(2f, 2f, W, 0f, CAPTION));
    }

    /** A pointer dragged off the bottom of the work area — over the taskbar — is in no zone. */
    @Test
    public void aPointerOutsideTheAreaHasNoZone() {
        assertNull(at(2f, H + 40f));
        assertNull(at(2f, -4f));
    }

    /**
     * <b>The halves tile exactly — no line of desktop down the middle.</b>
     *
     * <p>Both taking {@code size / 2} leaves an odd work area one pixel short, which looks like a
     * rendering bug rather than arithmetic. The far side takes the remainder.</p>
     */
    @Test
    public void theHalvesTileAnOddWidthExactly() {
        float odd = 801f;
        float[] left = SnapZones.rectFor(SnapZones.Zone.LEFT, odd, H);
        float[] right = SnapZones.rectFor(SnapZones.Zone.RIGHT, odd, H);

        assertEquals("the halves leave a gap", odd, left[2] + right[2], 0.001f);
        assertEquals("the right half does not start where the left ends",
                left[0] + left[2], right[0], 0.001f);
    }

    /** <b>...and so do the quarters, on BOTH axes.</b> An odd height is the half nothing else covers. */
    @Test
    public void theQuartersTileAnOddAreaExactly() {
        float oddW = 801f;
        float oddH = 601f;
        float[] tl = SnapZones.rectFor(SnapZones.Zone.TOP_LEFT, oddW, oddH);
        float[] tr = SnapZones.rectFor(SnapZones.Zone.TOP_RIGHT, oddW, oddH);
        float[] bl = SnapZones.rectFor(SnapZones.Zone.BOTTOM_LEFT, oddW, oddH);
        float[] br = SnapZones.rectFor(SnapZones.Zone.BOTTOM_RIGHT, oddW, oddH);

        assertEquals("the top row leaves a gap", oddW, tl[2] + tr[2], 0.001f);
        assertEquals("the bottom row leaves a gap", oddW, bl[2] + br[2], 0.001f);
        assertEquals("the left column leaves a gap", oddH, tl[3] + bl[3], 0.001f);
        assertEquals("the right column leaves a gap", oddH, tr[3] + br[3], 0.001f);

        assertEquals("the bottom row does not start where the top ends", tl[1] + tl[3], bl[1], 0.001f);
        assertEquals("the right column does not start where the left ends", tl[0] + tl[2], tr[0], 0.001f);
        assertArrayEquals("the top-left quarter is not at the origin",
                new float[] {0f, 0f}, new float[] {tl[0], tl[1]}, 0.001f);
    }

    @Test
    public void maximiseIsTheWholeArea() {
        assertArrayEquals(new float[] {0f, 0f, W, H},
                SnapZones.rectFor(SnapZones.Zone.MAXIMIZE, W, H), 0.001f);
    }
}
