package com.crystalgui.workbench.toolwindow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.region.WorkbenchRegions;

/**
 * <b>A placement change is announced by the STORE, so every writer is covered — including the one that
 * replaces the store wholesale.</b>
 *
 * <p>The rails build their buttons from the placement record and re-ask whenever it changes. The
 * announcement used to be raised by {@link ToolWindowManager}'s own three mutators, which covers every
 * gesture and misses a session restore: that clears the record and puts every decoded state straight in.
 * The panels then moved — {@code applyVisibility} re-shows them — and the buttons did not.</p>
 *
 * <p>On screen: Notifications opened bottom-left with its bell on the top right, and Problems
 * bottom-right with its icon on the bottom left. Two arrangements at once, one from the restored record
 * and one from before it.</p>
 */
public class PlacementChangesAreAnnouncedTest {

    private static final String RUN = "run";

    private final ToolWindowManager manager =
            new ToolWindowManager(new WorkbenchRegions(new UIElement()), new DockPanelRegistry<>());
    private final List<String> heard = new ArrayList<>();

    private ToolWindowLayout listening() {
        manager.onDidChangePlacement.connect(heard::add);
        return manager.toolWindows();
    }

    /** What a restore does: forget everything, then install what was read. */
    @Test
    public void replacingTheRecordTellsTheRails() {
        ToolWindowLayout store = listening();
        store.put(ToolWindowState.initial(RUN, DockRegion.PANEL, 0));
        heard.clear();

        store.clear();
        assertTrue("clearing the record said nothing, so the rails keep the arrangement they had "
                + "before the restore", heard.contains(RUN));

        heard.clear();
        store.put(ToolWindowState.initial(RUN, DockRegion.AUXILIARY, 0).withSide(RegionSide.SECONDARY));
        assertTrue("installing a restored placement said nothing", heard.contains(RUN));
    }

    /**
     * The counter-control: writing back what is already there says nothing.
     *
     * <p>{@code put} is how every hide, show and session capture records itself, and most of those write
     * back an unchanged state. Announcing those too would have each rail re-sync for nothing — and would
     * make a test that merely counts events pass against any implementation at all.</p>
     */
    @Test
    public void writingBackAnUnchangedPlacementSaysNothing() {
        ToolWindowLayout store = listening();
        ToolWindowState state = ToolWindowState.initial(RUN, DockRegion.PANEL, 0);
        store.put(state);
        heard.clear();

        store.put(state);
        assertEquals("an unchanged write woke the rails", List.of(), heard);
    }

    /** ...and a gesture still announces, through the same one route. */
    @Test
    public void aMoveStillAnnounces() {
        ToolWindowLayout store = listening();
        store.put(ToolWindowState.initial(RUN, DockRegion.PANEL, 0));
        heard.clear();

        manager.moveTo(RUN, DockRegion.SIDEBAR, RegionSide.PRIMARY);
        assertTrue("moving a tool window no longer tells the rails", heard.contains(RUN));
    }
}
