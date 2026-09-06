package com.crystalgui.workbench.region;

import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.region.RegionDropZones;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The six drop slots, as arithmetic — the opposite number to {@code DockDropZonesTest}.
 *
 * <p>Headless and exhaustive rather than by waving a mouse at a scene, which is the whole reason the
 * geometry is a pure class. Every rule below is invisible from any single call site and each was decided
 * from IntelliJ's actual behaviour rather than derived.</p>
 */
public class RegionDropZonesTest {

    private static final float W = 1000f;
    private static final float H = 800f;

    /** Bands sized like a real workbench: 200px sidebar, 220px auxiliary, 240px panel. */
    private static RegionDropZones.Target at(float x, float y) {
        return RegionDropZones.forPoint(x, y, W, H, 200f, 220f, 240f);
    }

    private static void assertSlot(String what, DockRegion region, RegionSide side,
                                   RegionDropZones.Target actual) {
        assertNotNull(what + ": no slot at all", actual);
        assertEquals(what, region, actual.region());
        assertEquals(what, side, actual.side());
    }

    /**
     * <b>The centre is a real answer, and it is "no".</b>
     *
     * <p>The editor holds documents; a tool window has no meaning there. A resolver that returned the
     * nearest band instead would make every drop land somewhere, which is how a drag ends up putting a
     * panel where nobody asked.</p>
     */
    @Test
    public void theEditorAreaOffersNothing() {
        assertNull("the middle of the window offered a destination", at(W / 2f, H / 2f));
        assertNull(at(300f, 100f));
        assertNull(at(700f, 400f));
    }

    /** The side bands split top/bottom; the bottom band splits left/right. */
    @Test
    public void eachBandSplitsAlongItsOwnCrossAxis() {
        assertSlot("left top", DockRegion.SIDEBAR, RegionSide.PRIMARY, at(50f, 100f));
        assertSlot("left bottom", DockRegion.SIDEBAR, RegionSide.SECONDARY, at(50f, 500f));
        assertSlot("right top", DockRegion.AUXILIARY, RegionSide.PRIMARY, at(950f, 100f));
        assertSlot("right bottom", DockRegion.AUXILIARY, RegionSide.SECONDARY, at(950f, 500f));
        assertSlot("bottom left", DockRegion.PANEL, RegionSide.PRIMARY, at(200f, 750f));
        assertSlot("bottom right", DockRegion.PANEL, RegionSide.SECONDARY, at(800f, 750f));
    }

    /**
     * <b>The lower corners belong to the bottom band.</b>
     *
     * <p>Not a tie-break for tidiness. The bottom band is the only one whose halves are split by
     * <em>x</em>, so at the lower-left corner "bottom" and "left" disagree about which axis the halves
     * even run along — there is no answer that satisfies both, and IntelliJ picks the bottom.</p>
     */
    @Test
    public void theLowerCornersGoToTheBottomBand() {
        assertSlot("lower left corner", DockRegion.PANEL, RegionSide.PRIMARY, at(20f, 780f));
        assertSlot("lower right corner", DockRegion.PANEL, RegionSide.SECONDARY, at(980f, 780f));
    }

    /**
     * <b>A hidden region keeps its band.</b>
     *
     * <p>Being unable to put a tool window back into a region you closed is the kind of dead end that
     * makes people restart the application — so a zero measurement falls back to a fraction of the axis
     * rather than to no band at all.</p>
     */
    @Test
    public void aClosedRegionIsStillDroppable() {
        RegionDropZones.Target slot = RegionDropZones.forPoint(10f, 100f, W, H, 0f, 0f, 0f);
        assertSlot("a closed sidebar", DockRegion.SIDEBAR, RegionSide.PRIMARY, slot);
        assertSlot("a closed panel", DockRegion.PANEL, RegionSide.PRIMARY,
                RegionDropZones.forPoint(100f, H - 10f, W, H, 0f, 0f, 0f));
    }

    /**
     * <b>A region you are over is the region you mean, however big it is.</b>
     *
     * <p>A measured band used to be capped at a third of the axis, so that a region dragged out large
     * could not leave the editor unreachable. The concern was right and the instrument was wrong: the cap
     * made part of a REGION report the editor, so hovering the upper half of a tall Problems panel offered
     * to float the window while pointing straight at the panel — which is how it was reported.</p>
     */
    @Test
    public void aPointInsideALargeRegionTargetsThatRegion() {
        // A sidebar occupying most of the width. The pointer is inside it, so it is the answer.
        assertSlot("the middle of a very wide sidebar", DockRegion.SIDEBAR, RegionSide.PRIMARY,
                RegionDropZones.forPoint(W / 2f, 100f, W, H, 900f, 0f, 0f));
        // ...and a tall panel, which is the shape that was reported.
        assertSlot("the upper half of a tall panel", DockRegion.PANEL, RegionSide.PRIMARY,
                RegionDropZones.forPoint(100f, H - 350f, W, H, 0f, 0f, 400f));
    }

    /**
     * The counter-control: the editor is still reachable beside it.
     *
     * <p>What the cap was protecting, and it holds by geometry rather than by arithmetic — a region
     * occupies its own box and the editor is whatever is left. Losing the centre entirely would take a
     * region covering the whole workbench, which cannot happen while the editor is laid out beside it.</p>
     */
    @Test
    public void theEditorIsStillReachableBesideALargeRegion() {
        // A sidebar taking half the width. x=700 is past it and short of the hidden auxiliary's band.
        assertNull("a sidebar half the window wide left nowhere meaning 'not a tool window'",
                RegionDropZones.forPoint(700f, 100f, W, H, 500f, 0f, 0f));
    }

    /**
     * ...and a hidden region's assumed band stops where a visible one begins.
     *
     * <p>The stand-in is a fraction of the whole axis, so beside a large open region it would otherwise
     * claim points plainly inside its neighbour — and the neighbour is the truthful answer. There is no
     * centre left in this configuration, and that is honest rather than a gap: a hundred-pixel editor is
     * not somewhere a tool window can go either.</p>
     */
    @Test
    public void anAssumedBandDoesNotReachIntoAVisibleRegion() {
        assertSlot("a point inside a 900-wide sidebar", DockRegion.SIDEBAR, RegionSide.PRIMARY,
                RegionDropZones.forPoint(880f, 100f, W, H, 900f, 0f, 0f));
    }

    /**
     * <b>The preview halves the region's own box, and does not touch the band.</b>
     *
     * <p>Two rectangles with two jobs, and conflating them lit the rail up as if a tool window could land
     * on it. A band is measured from the workbench edge so hovering the rail targets the region behind it;
     * the highlight is the region's actual box, which starts after the rail.</p>
     */
    @Test
    public void thePreviewHalvesTheRegionsOwnBox() {
        // A sidebar that starts at x=20 (after the rail) and is 180 wide.
        float[] sidebar = {20f, 0f, 180f, H};
        float[] lower = RegionDropZones.previewRect(
                new RegionDropZones.Target(DockRegion.SIDEBAR, RegionSide.SECONDARY), sidebar);
        assertEquals("the highlight started at the workbench edge, covering the rail", 20f, lower[0], 1e-4f);
        assertEquals("did not start at the halfway line", H / 2f, lower[1], 1e-4f);
        assertEquals(180f, lower[2], 1e-4f);
        assertEquals(H / 2f, lower[3], 1e-4f);

        // The bottom strip halves the OTHER way -- its two halves sit side by side.
        float[] panel = {20f, 600f, 960f, 200f};
        float[] right = RegionDropZones.previewRect(
                new RegionDropZones.Target(DockRegion.PANEL, RegionSide.SECONDARY), panel);
        assertEquals(20f + 480f, right[0], 1e-4f);
        assertEquals(600f, right[1], 1e-4f);
        assertEquals(480f, right[2], 1e-4f);
        assertEquals("the bottom strip was halved vertically", 200f, right[3], 1e-4f);
    }

    /**
     * <b>A hidden region's fallback rectangle is inset by the rail.</b>
     *
     * <p>The band it comes from is not, deliberately — so this is the one place the two have to be told
     * apart, and getting it wrong shows a drop landing on the stripe itself.</p>
     */
    @Test
    public void aHiddenRegionsPreviewStillClearsTheRail() {
        float[] rect = RegionDropZones.fallbackRect(DockRegion.SIDEBAR, W, H, 0f, 0f, 0f, 20f);
        assertEquals("the fallback covered the rail", 20f, rect[0], 1e-4f);
        assertTrue("the fallback has no width left", rect[2] > 0f);
        assertEquals("the fallback is not as wide as the band minus the rail",
                W * RegionDropZones.DEFAULT_BAND_FRACTION - 20f, rect[2], 1e-4f);
    }

    /**
     * <b>"Bottom Left" and "Left Bottom" are two different places.</b>
     *
     * <p>Region first, half second — IntelliJ's convention, and the reason the word order flips with the
     * axis. Losing it makes the bottom strip's left half and the sidebar's lower half read as the same
     * destination, which is exactly the pair a drag has to distinguish.</p>
     */
    @Test
    public void theLabelNamesTheSlotRegionFirst() {
        assertEquals("Move to Bottom Left", RegionDropZones.labelFor(
                new RegionDropZones.Target(DockRegion.PANEL, RegionSide.PRIMARY)));
        assertEquals("Move to Left Bottom", RegionDropZones.labelFor(
                new RegionDropZones.Target(DockRegion.SIDEBAR, RegionSide.SECONDARY)));
        assertEquals("Move to Right Top", RegionDropZones.labelFor(
                new RegionDropZones.Target(DockRegion.AUXILIARY, RegionSide.PRIMARY)));
    }

    /** Degenerate boxes answer "nothing" rather than dividing by zero. */
    @Test
    public void anUnmeasuredWorkbenchOffersNothing() {
        assertNull(RegionDropZones.forPoint(10f, 10f, 0f, 0f, 0f, 0f, 0f));
        assertNull(RegionDropZones.forPoint(10f, 10f, -5f, 100f, 0f, 0f, 0f));
    }
}
