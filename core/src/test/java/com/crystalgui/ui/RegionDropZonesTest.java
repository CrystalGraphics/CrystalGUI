package com.crystalgui.ui;

import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;
import com.crystalgui.ui.elements.workbench.RegionDropZones;

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
     * <b>A band cannot eat the centre.</b>
     *
     * <p>A sidebar dragged out to most of the window would otherwise leave nowhere that means "not a tool
     * window", and the editor — the one place a tool window must not land — would stop being reachable as
     * an answer.</p>
     */
    @Test
    public void anEnormousRegionIsCappedSoTheCentreSurvives() {
        assertNull("a huge sidebar swallowed the editor area",
                RegionDropZones.forPoint(W / 2f, H / 2f, W, H, 900f, 0f, 0f));
        assertTrue("the cap is not being applied at all",
                RegionDropZones.MAX_BAND_FRACTION < 0.5f);
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
