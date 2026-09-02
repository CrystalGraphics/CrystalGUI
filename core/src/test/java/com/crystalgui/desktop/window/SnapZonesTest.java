package com.crystalgui.desktop.window;

import com.crystalgui.desktop.Desktop;
import org.junit.After;
import org.junit.Before;
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
    /**
     * ANIMATIONS OFF, unless a test turns them on for itself.
     *
     * <p>A window animation defers the thing it animates: `close()` destroys and `hide()`
     * detaches only once the flight has finished, so a test that asserts the state straight
     * after the gesture reads the state BEFORE it. Disabled, the continuation runs
     * synchronously, which is what lets every assertion here be immediate. The tests that are
     * ABOUT the animation enable it themselves and restore this in a finally.</p>
     */
    @Before
    public void quietAnimationsForTheFixture() {
        Desktop.setAnimationsEnabled(false);
    }

    /** AND PUT IT BACK. The flag is STATIC, so leaving it off leaks into every later test in the
     *  run -- a governance test that asks whether every shipped rule still matches something then
     *  finds `taskbar .__entry__.__animating__` matching nothing, because nothing animates. */
    @After
    public void restoreAnimationsAfterTheFixture() {
        Desktop.setAnimationsEnabled(true);
    }


    private static final float W = 800f;
    private static final float H = 600f;

    private static SnapZones.Zone at(float x, float y) {
        return SnapZones.forPoint(x, y, W, H);
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
     * <b>The top edge is read from the CURSOR, exactly like the sides.</b>
     *
     * <p>It used to take the frame's CAPTION HEIGHT as its band, and the argument for that was that a
     * shallower band is unreachable: the pointer rides inside the caption for the whole of a drag, and
     * the window is clamped so the caption's top never leaves the work area, which holds the pointer a
     * whole caption below the border. True, and it made the top the one edge triggered by the WINDOW
     * rather than by the cursor — it fires the moment the window's upper lip reaches the border.</p>
     *
     * <p>The reachability problem is solved where it arises instead: {@code WindowFrame} lets the caption
     * rise one caption-height above the work area <em>while a move is live</em>, so any grab can bring
     * the cursor to the border, and the headroom is withdrawn when the drag ends. Windows does the same —
     * drag a window up and its title bar goes off the top while the cursor reaches the edge.</p>
     */
    @Test
    public void theTopEdgeIsReadFromTheCursor() {
        assertEquals(SnapZones.Zone.MAXIMIZE, at(400f, 0f));
        assertEquals("the very edge is in", SnapZones.Zone.MAXIMIZE, at(400f, 2f));
        assertNull("a caption's depth below the border is NOT the top zone", at(400f, 20f));
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
