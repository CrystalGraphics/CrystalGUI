package com.crystalgui.workbench;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.workbench.diff.DiffView;
import com.crystalgui.workbench.diff.MergeView;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.workbench.dock.banner.DockBannerBar;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.explorer.ProjectFileTree;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionDropOverlay;
import com.crystalgui.workbench.region.RegionHost;
import com.crystalgui.workbench.stripe.StripeRail;
import com.crystalgui.workbench.stripe.StripeView;
import com.crystalgui.workbench.toolwindow.ToolWindowFrame;
import com.crystalgui.workbench.view.ViewContainer;

/**
 * <b>The workbench layer's kinds</b> — the shell's own nodes, and the dock's.
 *
 * <p>Its own service rather than an entry in {@code Widgets} or {@code DesktopKinds}, for the reason
 * {@link NodeKinds} exists: a LAYER speaks for itself. {@code widget} does not know what a dock is,
 * and a registry importing both would be the upward reference {@code LayeringTest} refuses.</p>
 *
 * <p><b>Its sub-packages get no service of their own.</b> {@code workbench.chrome} and
 * {@code workbench.dock} are organisation inside one layer, not layers — the same distinction the
 * layer list itself records, where listing a sub-package made it read as sitting <em>above</em> the
 * layer root.</p>
 */
public final class WorkbenchKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public WorkbenchKinds() {
    }

    @Override
    public void register() {
        // NONE OF THESE FOUR HAS A NO-ARGUMENT CONSTRUCTOR, and all four are registered anyway.
        // What a registration buys here is the TAG, which is a widget's cascade identity: a concrete
        // node declaring no kind inherits `crystalgui:element` and matches every bare `element` rule
        // there is, and one declaring a kind nothing registered matches nothing at all. Both are
        // silent, and the second is the ToolWindowFrame failure — a widget that reads as never having
        // been built. `DesktopKinds` registers `WindowSwitcher` and `WindowFrame` the same way and
        // for the same reason.
        //
        // Nothing describes a dock over a wire today, so the arguments a real one needs are passed as
        // null or empty rather than invented. A decoded one is inert, which is what a description of a
        // dock would mean anyway.
        UINodeRegistry.registerTag(DockArea.NAME, NodeContract.INERT);
        // A REAL area and leaf, because the constructor syncs its tab strip from the leaf's panels.
        // `null, null` was the obvious spelling and the walk caught it in one run -- which is the
        // difference between a registration that exists and one that works.
        UINodeRegistry.register(DockGroup.NAME,
                () -> new DockGroup(emptyArea(), new DockLeaf()), NodeContract.INERT);
        UINodeRegistry.registerTag(DockWindow.NAME, NodeContract.INERT);
        // A banner bar is built from a Notification and never decoded; registered so the tag exists,
        // since `ua/workbench.css` styles it. An EMPTY notification rather than null, because unlike
        // the three above it reads its argument in the constructor -- which the coverage walk found
        // by building every registered kind, and is the whole reason that walk builds them.
        UINodeRegistry.register(DockBannerBar.NAME,
                () -> new DockBannerBar(Notification.info("")), NodeContract.INERT);

        // THE SHELL AND ITS PARTS. Same argument as the dock's above: none of these has a
        // no-argument constructor and all of them need a TAG, because a node declaring no kind
        // inherits `crystalgui:element` and matches every bare `element` rule there is. Three of them
        // -- workbench, projectfiletree, viewcontainer -- are named by `ua/workbench.css` outright,
        // so without this they would have matched NOTHING written for them, which is the
        // ToolWindowFrame failure three times over.
        //
        // Nothing describes a workbench over a wire, so the arguments a live one needs are passed as
        // null or empty rather than invented; a decoded one is inert, which is what a description of
        // a workbench would mean anyway.
        UINodeRegistry.registerTag(Workbench.NAME, NodeContract.INERT);
        UINodeRegistry.registerTag(ProjectFileTree.NAME, NodeContract.INERT);
        UINodeRegistry.register(ViewContainer.NAME,
                () -> new ViewContainer("", ""), NodeContract.INERT);
        UINodeRegistry.register(RegionHost.NAME,
                () -> new RegionHost(DockRegion.SIDEBAR), NodeContract.INERT);
        UINodeRegistry.registerTag(RegionDropOverlay.NAME, NodeContract.INERT);
        UINodeRegistry.registerTag(StripeView.NAME, NodeContract.INERT);
        UINodeRegistry.register(DiffView.NAME,
                () -> new DiffView("", "", "", ""), NodeContract.INERT);
        // A REAL merge, because the constructor reads it. `null` was the obvious spelling and the
        // coverage walk caught it in one run, which is the difference between a registration that
        // exists and one that works -- the same lesson DockGroup's `null, null` taught two stages ago.
        UINodeRegistry.register(MergeView.NAME,
                () -> new MergeView(ThreeWayMerge.of("", "", "")), NodeContract.INERT);
        // A tool window's frame: built per show and destroyed per hide, never decoded. Registered so
        // the TAG exists -- and it has one of its own rather than answering `window`, because a Name
        // is bound to a factory. `WindowFrame.WINDOW_CLASS` is what carries a window's look to it.
        UINodeRegistry.registerTag(ToolWindowFrame.NAME, NodeContract.INERT);
    }

    /** One empty dock, for the registrations above. */
    private static DockArea emptyArea() {
        return new DockArea(new DockPanelRegistry<UINode>(), DockLayout.of(new DockLeaf()));
    }
}
