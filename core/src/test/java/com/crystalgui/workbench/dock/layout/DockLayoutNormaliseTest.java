package com.crystalgui.workbench.dock.layout;

import com.crystalgui.workbench.dock.drag.DockDropZone;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DockLayout#normalise()} — the repair a decoded tree gets, and the one a live tree never needs.
 *
 * <p>A session is bytes on disk: whatever shape was saved comes back, and no operation runs to tidy it.
 * So every invariant the operations maintain as they go has to be reachable from here too.</p>
 */
public class DockLayoutNormaliseTest {

    private final DockPanelRef alpha = new DockPanelRef("alpha");

    /**
     * <b>A session saved with one half of a split empty does not restore the blank band.</b>
     *
     * <p>An empty central leaf is kept while it is the last one — the main work area always exists — and
     * that exemption applied beside a sibling too, so the empty half came back still holding its weight.
     * Nothing was ever going to close it again, which is what made it survive a restart.</p>
     */
    @Test
    public void anEmptyCentralHalfDoesNotSurviveARestore() {
        DockLeaf centre = new DockLeaf();
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);
        layout.drop(centre, DockDropZone.SPLIT_RIGHT, new DockLeaf(alpha));

        layout.normalise();

        assertEquals("the empty half is gone", 1, layout.leaves().size());
        DockLeaf survivor = layout.leaves().get(0);
        assertTrue("and the survivor took the central role", survivor.isCentral());
        assertTrue("carrying the panel", survivor.indexOf(alpha) >= 0);
        layout.checkInvariants();
    }

    /** The last leaf standing keeps the role even with nothing in it: that is an empty editor area. */
    @Test
    public void theLastEmptyLeafIsTheRestingState() {
        DockLeaf centre = new DockLeaf();
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);

        layout.normalise();

        assertEquals(1, layout.leaves().size());
        assertNotNull(layout.centralLeaf());
        layout.checkInvariants();
    }
}
