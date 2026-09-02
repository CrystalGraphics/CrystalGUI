package com.crystalgui.workbench.region;

import com.crystalgui.workbench.toolwindow.ToolWindowManager;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.region.WorkbenchRegions;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A region renders at the share it was given, whatever else is open.
 *
 * <h3>What went wrong</h3>
 *
 * <p>{@code sync()} gave the editor whatever the named regions left over — {@code 1 - sidebar - aux} —
 * and subtracted <b>both</b> of them whether or not they were on screen. With the auxiliary bar closed
 * its 0.22 came off anyway, so the row's weights summed to 0.78; {@code SplitFill} then normalised,
 * which it must, because a flex row totalling below one leaves the remainder blank instead of dividing
 * it. Every visible pane was therefore scaled by 1/0.78.</p>
 *
 * <p>The consequence is the one that was reported: <b>the sidebar's width depended on whether an
 * unrelated region was open</b> — 256px of a 1000px axis with the auxiliary bar shut, 200px with it
 * open. Pressing a stripe button resized the file tree.</p>
 *
 * <h3>Why this asserts the LAID-OUT width</h3>
 *
 * <p>The model was never wrong. {@code weightOf} kept reading 0.20 throughout, because
 * {@code onPercentageChanged} fires from {@code setPercentageAt} alone and {@code sync()} writes through
 * {@code setWeights} — so nothing ever fed the normalised number back. A first draft of this test
 * asserted on the weights and passed against the broken build. What moved was the picture, so the
 * picture is what has to be measured.</p>
 */
public class WorkbenchRegionWeightTest extends UiDocumentTestBase {

    private static final int AXIS = 1000;

    private WorkbenchRegions regions;

    @Before
    public void setUpRegions() {
        regions = new WorkbenchRegions(new UINode());
        regions.host(DockRegion.SIDEBAR).show(RegionSide.PRIMARY, "explorer", new UINode());
        regions.sync();

        UINode root = new UINode().layout(l -> l.width(AXIS).height(600));
        root.append(regions.root());
        document.append(root);
        settle();
    }

    /** No stylesheet is installed, so the dividers take no width and the axis divides exactly. */
    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    /** What the sidebar actually IS wide, which is what the report was about. */
    private float sidebarWidth() {
        settle();
        return regions.host(DockRegion.SIDEBAR).box().width();
    }

    /** What a stripe button does, via ToolWindowManager: fill the host, then re-sync the frame. */
    private void show(DockRegion region, String typeId) {
        regions.host(region).show(RegionSide.PRIMARY, typeId, new UINode());
        regions.sync();
    }

    private void hide(DockRegion region) {
        regions.host(region).clear();
        regions.sync();
    }

    /**
     * <b>The share it was given, and not a scaled version of it.</b> The stability tests below would all
     * pass against a build that was consistently wrong by the same factor — which is exactly what this
     * was, before anybody opened the auxiliary bar and made the two answers disagree.
     */
    @Test
    public void aRegionRendersAtTheShareItWasGiven() {
        assertEquals("the sidebar's 20% of a 1000px axis", 200f, sidebarWidth(), 0.5f);
    }

    /** The reported bug: the right-hand rail moving the left-hand one. */
    @Test
    public void openingTheAuxiliaryBarLeavesTheSidebarWhereItWas() {
        float before = sidebarWidth();
        show(DockRegion.AUXILIARY, "inspector");
        assertEquals("opening the auxiliary bar resized the sidebar", before, sidebarWidth(), 0.5f);
    }

    @Test
    public void openingTheBottomPanelLeavesTheSidebarWhereItWas() {
        float before = sidebarWidth();
        show(DockRegion.PANEL, "run");
        assertEquals("opening the panel resized the sidebar", before, sidebarWidth(), 0.5f);
    }

    /**
     * <b>And it must not creep.</b> One step was a few dozen pixels and arguable on its own; what made
     * this unmistakable is that opening and closing kept re-applying it.
     */
    @Test
    public void togglingPanelsRepeatedlyDoesNotMoveTheSidebar() {
        float before = sidebarWidth();
        for (int i = 0; i < 8; i++) {
            show(DockRegion.PANEL, "run");
            show(DockRegion.AUXILIARY, "inspector");
            hide(DockRegion.PANEL);
            hide(DockRegion.AUXILIARY);
        }
        assertEquals("eight open/close cycles moved the sidebar", before, sidebarWidth(), 0.5f);
    }

    /** A width somebody chose survives it too — the default happening to be stable proves less. */
    @Test
    public void aChosenWidthSurvivesARegionOpening() {
        regions.setWeight(DockRegion.SIDEBAR, 0.35f);
        assertEquals("the chosen share is what renders", 350f, sidebarWidth(), 0.5f);
        show(DockRegion.AUXILIARY, "inspector");
        assertEquals("a chosen sidebar width did not survive", 350f, sidebarWidth(), 0.5f);
    }
}
