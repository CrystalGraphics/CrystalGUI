package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockRegion;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool windows: which exist, where each belongs, and whether it is on screen — IntelliJ's
 * {@code ToolWindowManager}.
 *
 * <h3>What this used to be, and why it is a third of the size</h3>
 *
 * <p>It was 220 lines inside {@code Workbench}, and the bulk of it was {@code showPanel}'s <b>four-tier
 * restoration heuristic</b>: rejoin the tab strip it shared, else the exact branch and index it occupied,
 * else replay the drop that put it beside a surviving neighbour, else a wall. Every tier existed for one
 * reason — a tool window lived in the dock tree, and hiding one <em>collapsed the branch that held it</em>,
 * so its position had to be reconstructed from whatever survived.</p>
 *
 * <p>It was careful, correct and well-tested code for a problem that should not have existed. A tool window
 * belongs to a <b>region</b> now, and a region is not a position: nothing about it is destroyed by a hide,
 * so restoration is a lookup. Tiers 1–3 had nothing left to be about and are gone with the fields that fed
 * them ({@code path}, {@code groupedWith}, {@code relativeTo}).</p>
 *
 * <p>This is plan.md §23 F2b, deliberately held until §24 step 2 — a fallback cannot be deleted before the
 * thing that replaces it exists, and regions did not exist until now.</p>
 *
 * <h3>Content is built once per type, not per show</h3>
 *
 * <p>Toggling a tool window must not rebuild it. The file tree's expansion, the Problems panel's sort and
 * an inspector's scroll are all view state that a rebuild silently discards — the same reason
 * {@code DockGroup} caches content per panel rather than per layout rebuild.</p>
 */
public final class ToolWindowManager {

    private final WorkbenchRegions regions;
    private final DockPanelRegistry<UIElement> registry;

    /** Container id -> its built container. Survives every hide; see the class note. */
    private final Map<String, ViewContainer> containers = new LinkedHashMap<>();

    /** Which views each container holds, and its badge. */
    private final ViewContainerRegistry viewContainers = new ViewContainerRegistry();

    /** @see ViewContainerRegistry */
    public ViewContainerRegistry viewContainers() {
        return viewContainers;
    }

    private final ToolWindowLayout toolWindows = new ToolWindowLayout();

    public ToolWindowManager(WorkbenchRegions regions, DockPanelRegistry<UIElement> registry) {
        this.regions = regions;
        this.registry = registry;
    }

    /**
     * Every tool window's placement, open or closed — the model {@link WorkbenchSession} persists.
     *
     * <p>See {@link ToolWindowLayout} for why both references keep placement <em>beside</em> the layout
     * rather than deriving it from one. That argument is what this whole step acted on.</p>
     */
    public ToolWindowLayout toolWindows() {
        return toolWindows;
    }

    /** Whether this tool window is currently showing in its region. */
    public boolean isPanelOpen(String typeId) {
        RegionHost host = regions.host(regionOf(typeId));
        return host != null && typeId.equals(host.showing());
    }

    /**
     * Shows a tool window, or hides it if it is already showing — what a stripe button does.
     *
     * <p>Toggle, because that is what both editors do: clicking a visible tool window's stripe button
     * hides it in IntelliJ and in VS Code alike. Open-only would leave the rail able to fill the screen and
     * unable to clear anything.</p>
     *
     * @return whether it is open <em>after</em> this call
     */
    public boolean togglePanel(String typeId) {
        return isPanelOpen(typeId) ? hidePanel(typeId) : showPanel(typeId);
    }

    /**
     * Hides a tool window.
     *
     * <p>Once the whole of {@code hidePanel} was capturing placement <b>before</b> the close, because
     * closing destroyed it. Nothing is captured now: the region is where it belongs, and hiding a region's
     * occupant does not change which region that is.</p>
     *
     * @return false, always — it is closed after this
     */
    public boolean hidePanel(String typeId) {
        RegionHost host = regions.host(regionOf(typeId));
        if (host == null || !typeId.equals(host.showing())) return false;
        // The WIDTH IS READ BEFORE the clear, because a region with nothing in it is about to leave the
        // split and its share stops being readable. Same shape as the old hidePanel, which captured
        // placement before a close for the same reason -- that part of it was always right.
        float weight = regions.weightOf(regionOf(typeId));
        host.clear();
        regions.sync();
        toolWindows.put(placementOf(typeId).withVisible(false).withWeight(weight));
        return false;
    }

    /**
     * Shows a tool window in its region.
     *
     * <p>The whole of it. What replaced four tiers, and the tiers are worth remembering only as evidence:
     * a mechanism that elaborate is usually a mechanism recovering something that should never have been
     * thrown away.</p>
     *
     * @return true, always — it is open after this
     */
    public boolean showPanel(String typeId) {
        DockRegion region = regionOf(typeId);
        RegionHost host = regions.host(region);
        if (host == null) return false;

        ViewContainer container = containers.computeIfAbsent(typeId, this::buildContainer);
        if (container == null) return false;

        host.show(typeId, container);
        regions.sync();
        toolWindows.put(placementOf(typeId).withVisible(true));
        return true;
    }

    /** Which region this type belongs to — its descriptor's, or its remembered placement's. */
    public DockRegion regionOf(String typeId) {
        ToolWindowState state = toolWindows.get(typeId);
        if (state != null) return state.region();
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return descriptor != null ? descriptor.region() : DockRegion.SIDEBAR;
    }

    /** This type's placement, seeded from its descriptor the first time it is asked for. */
    private ToolWindowState placementOf(String typeId) {
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return toolWindows.getOrCreate(typeId,
                descriptor != null ? descriptor.region().wall() : DockRegion.SIDEBAR.wall());
    }

    /**
     * Applies every tool window's remembered visibility — what a session restore calls.
     *
     * <p>A lookup per entry, which is the point. The old equivalent was replaying drops into a tree and
     * hoping the branches it named were still there.</p>
     */
    public void applyVisibility() {
        for (ToolWindowState state : toolWindows.ordered()) {
            // BOTH DIRECTIONS. Showing alone is not a restore: the workbench opens Project and Problems in
            // its constructor and the application opens the Inspector, all BEFORE a session is read -- so
            // a region the record says is hidden is simply never told, and comes back open every launch.
            if (state.visible()) showPanel(state.typeId());
            else hidePanel(state.typeId());
        }
    }

    /**
     * Builds the container for a type: its header, and its views.
     *
     * <p>A tool window that registered no views is a container holding <b>one</b> view — itself, built by
     * the panel factory. That default is what let containers land without every existing panel being
     * re-registered, and it is also the honest description: Project really is one view.</p>
     */
    @Nullable
    private ViewContainer buildContainer(String typeId) {
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        String title = descriptor != null ? descriptor.title() : typeId;
        ViewContainer container = new ViewContainer(typeId, title);
        container.setViews(viewContainers.viewsOf(typeId, title,
                () -> registry.create(new DockPanelRef(typeId))));
        // The header's ✕ is the same verb the stripe button is, so it runs the same path rather than
        // reaching into the region -- otherwise the two can disagree about what "hidden" means.
        container.onHideRequested.connect(() -> hidePanel(typeId));
        return container;
    }

    /** The built container for a type, or null if it has never been shown. For tests and diagnostics. */
    @Nullable
    public ViewContainer containerOf(String typeId) {
        return containers.get(typeId);
    }
}
