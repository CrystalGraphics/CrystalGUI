package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Which region this type belongs to — its remembered placement's, or its descriptor's. */
    public DockRegion regionOf(String typeId) {
        ToolWindowState state = toolWindows.get(typeId);
        if (state != null) return state.region();
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return descriptor != null ? descriptor.region() : DockRegion.SIDEBAR;
    }

    /** Its position within its stripe group — IntelliJ's {@code order}. */
    public int orderOf(String typeId) {
        ToolWindowState state = toolWindows.get(typeId);
        return state != null ? state.order() : Integer.MAX_VALUE;
    }

    /**
     * Every tool window in one stripe group, in stripe order.
     *
     * <p>The unit a drop reorders within, and deliberately <b>(region, side)</b> rather than region alone:
     * a stripe holds both halves of its anchor, but they are separated in it — IntelliJ draws a
     * {@code StripeButtonSeparator} between them — so an insertion index only means something inside one
     * half.</p>
     */
    public List<String> groupOf(DockRegion region, RegionSide side) {
        // FROM THE REGISTRY, not from the stored placements. A tool window only gets a ToolWindowState the
        // first time something asks where it is, so a group read from the states alone omits every member
        // nobody has touched yet -- and a drop would then renumber the two it could see and leave the rest
        // sharing their old orders. Which reads as "reordering works, except with the ones I have not
        // clicked", i.e. as nothing to do with ordering at all.
        List<String> found = new ArrayList<>();
        for (DockPanelDescriptor descriptor : registry.descriptors()) {
            if (!descriptor.isSingleton()) continue;
            String typeId = descriptor.typeId();
            if (regionOf(typeId) == region && sideOf(typeId) == side) found.add(typeId);
        }
        // Stable, so the ones with no order yet keep registration order behind the ones that have.
        found.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
        return found;
    }

    /** Which half of that region — see {@link RegionSide}. */
    public RegionSide sideOf(String typeId) {
        ToolWindowState state = toolWindows.get(typeId);
        if (state != null) return state.side();
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return descriptor != null ? descriptor.side() : RegionSide.PRIMARY;
    }

    /**
     * Moves a tool window to another region, or another half of the one it is in.
     *
     * <h3>The whole of dragging a stripe button</h3>
     *
     * <p>One write of two fields, which is what {@code plan.md} §24.5 meant by <i>"dragging a container
     * from one stripe to another <b>is</b> changing its region"</i>. There is no second model to keep in
     * step: {@link StripeRail} derives the rail and the group from the pair this stores, so the button
     * arrives in the right place by being asked again rather than by being moved.</p>
     *
     * <p><b>Hidden first, and only then repointed.</b> {@link #hidePanel} reads the region a tool window is
     * <em>currently</em> in — both to find the host holding it and to record that region's width before it
     * leaves the split. Writing the new placement first would send it to look for its occupant in the region
     * it is moving <em>to</em>, find nothing there, and return early: the old region keeps showing a
     * container that the model says is somewhere else.</p>
     */
    public void moveTo(String typeId, DockRegion region, RegionSide side) {
        if (typeId == null || region == null || region == DockRegion.EDITOR) return;
        ToolWindowState current = placementOf(typeId);
        if (current.region() == region && current.side() == side) return;

        boolean wasOpen = isPanelOpen(typeId);
        if (wasOpen) hidePanel(typeId);
        toolWindows.put(placementOf(typeId).withRegion(region).withSide(side));
        if (wasOpen) showPanel(typeId);
        // EVEN WHEN IT WAS CLOSED. A closed tool window still has a button, and moving that button is the
        // ordinary way to say where it should open next time -- announcing only on the visible case would
        // leave the rails showing the placement they had before the drag.
        onDidChangePlacement.emit(typeId);
    }

    /**
     * Moves a tool window and puts it at {@code index} within its new stripe group.
     *
     * <h3>The index is what the insertion marker promised</h3>
     *
     * <p>A drag shows a slot opening in the rail where the button would land. Honouring the region and
     * dropping the index makes that marker a lie in the most annoying possible way — it is right about the
     * region every time, so it reads as working, and wrong about the position only when you were paying
     * attention to it.</p>
     *
     * <p><b>The whole group is renumbered, not just the newcomer.</b> Orders start as registration order and
     * are therefore dense but arbitrary; inserting "between 3 and 4" has no integer to use. Both references
     * renumber for the same reason. Cheap: a stripe group is a handful of entries.</p>
     */
    public void moveTo(String typeId, DockRegion region, RegionSide side, int index) {
        moveTo(typeId, region, side);
        if (index < 0) return;

        List<String> group = groupOf(region, side);
        group.remove(typeId);
        group.add(Math.min(index, group.size()), typeId);
        for (int at = 0; at < group.size(); at++) {
            // placementOf, never toolWindows.get(): a tool window has no ToolWindowState until something
            // asks where it is, and `get` returns null for those. Skipping them left every untouched
            // button on Integer.MAX_VALUE while its neighbours took 0..n-1 -- and MAX_VALUE sorts last,
            // permanently. The symptom is precise and was reported as such: nothing can be dropped BELOW
            // the last button until that button has itself been moved once and earned a real order.
            toolWindows.put(placementOf(group.get(at)).withOrder(at));
        }
        onDidChangePlacement.emit(typeId);
    }

    /**
     * A tool window's region or side changed — the stripes' cue to re-ask which of them owns its button.
     *
     * <p>Carries the type id rather than nothing, so a listener can be selective; both stripes currently
     * re-sync wholesale, because a move is two rails' business and the sync is a walk over a handful of
     * descriptors.</p>
     */
    public final Signal.Value<String> onDidChangePlacement = new Signal.Value<>();

    /** This type's placement, seeded from its descriptor the first time it is asked for. */
    private ToolWindowState placementOf(String typeId) {
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return toolWindows.getOrCreate(typeId,
                descriptor != null ? descriptor.region() : DockRegion.SIDEBAR,
                descriptor != null ? descriptor.side() : RegionSide.PRIMARY);
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
