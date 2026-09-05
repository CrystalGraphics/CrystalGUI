package com.crystalgui.workbench.toolwindow;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionHost;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.desktop.window.WindowFrame;

import com.crystalgui.workbench.region.WorkbenchRegions;
import com.crystalgui.workbench.view.ViewContainer;
import com.crystalgui.workbench.view.ViewContainerRegistry;
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

    /** Every tool window currently in a frame rather than a region, by type id. */
    private final Map<String, ToolWindowFrame> frames = new LinkedHashMap<>();

    /**
     * Docked, floating or windowed — its remembered mode, or docked.
     *
     * <p>The mode is a property of the tool window and not of any frame, which is what lets a stripe
     * button reopen a float <em>as a float</em> after it has been closed and its frame destroyed.</p>
     */
    public ToolWindowType typeOf(String typeId) {
        ToolWindowState state = toolWindows.get(typeId);
        return state != null ? state.type() : ToolWindowType.DOCKED;
    }

    /** Whether this tool window is currently showing — in its region, or in its frame. */
    /**
     * Takes a tool window out of the region half that is holding it, if one is.
     *
     * <p><b>Going into a frame is leaving the region, and the region has to be told.</b> Putting a panel
     * in a frame reparents its container out from under the host ({@code setContent} does that), and a
     * host left recording an occupant it no longer contains answers {@code isEmpty() == false} — so
     * {@code sync()} keeps the region in the split and its whole width stays behind as a blank column
     * beside the editor. That is the standing invariant about the host being the truth, met from a new
     * direction.</p>
     *
     * <p><b>Reached only on the restore path, which is why it was missing.</b> Undocking by hand goes
     * through {@code setType}, which hides the panel <em>while its type is still DOCKED</em>, so
     * {@code hidePanel} takes its docked branch and clears the half. A session restore never hides
     * anything: it decodes a placement that already says WINDOWED and calls {@code showPanel}, so
     * {@code hidePanel}'s early return for a windowed type means nothing is ever cleared. Doing it here
     * makes it true however the frame was reached.</p>
     *
     * <p>Both shares are read BEFORE the clear, for the reason {@code hidePanel} gives: a region that
     * empties leaves the split and takes its divider with it, and neither is readable afterwards.</p>
     */
    private void releaseRegionSlot(String typeId) {
        DockRegion region = showingRegionOf(typeId);
        RegionHost host = region == null ? null : regions.host(region);
        RegionSide side = showingSideOf(host, typeId);
        if (host == null || side == null) return;
        float weight = regions.weightOf(region);
        float sideWeight = regions.sideWeightOf(region);
        host.clear(side);
        regions.sync();
        // THE SHARES ONLY. `visible` is deliberately untouched: this runs on the way IN to a frame, and
        // showInFrame sets it true a few lines later -- writing false here and true there would leave the
        // record correct only because two writes happened to be ordered.
        toolWindows.put(placementOf(typeId).withWeight(weight).withSideWeight(sideWeight));
    }

    /**
     * Tool windows asked for before there was anywhere to put them.
     *
     * <p>Both presentations, and it has to be both. A WINDOWED one has no {@code UIDocument} to open
     * into on the frame a session restores; a DOCKED one has no {@link RegionHost} to show in until the
     * workbench has joined a window and its regions have been built, which is a frame later still. The
     * docked branch recorded nothing and returned false, so a panel asked for on the frame the workbench
     * was attached simply never opened — invisible for as long as everything that shows a tool window
     * did so from a user gesture, and reached the moment a <b>server</b> could ask for one.</p>
     */
    private final java.util.Set<String> pendingWindowedShows = new java.util.LinkedHashSet<>();

    /**
     * Opens whatever was asked for while the tree had no window.
     *
     * <p>Called when the workbench joins one. The answer has to be given <b>twice</b> for the same reason
     * a window's focus delegate does: the moment something is asked for and the moment it can be
     * satisfied are not the same frame, and the first one silently answers "no".</p>
     *
     * <p>Nothing re-checks the record here, deliberately. {@link #hidePanel} takes a panel out of the set,
     * so a panel put away between the ask and the retry is already gone from it — and a second guard
     * reading {@code visible()} would be a redundant one no test can distinguish from the first, which is
     * how two mechanisms for one rule start disagreeing.</p>
     */
    public void retryPendingShows() {
        if (pendingWindowedShows.isEmpty()) return;
        for (String typeId : new java.util.ArrayList<>(pendingWindowedShows)) {
            pendingWindowedShows.remove(typeId);
            showPanel(typeId);
        }
    }

    public boolean isPanelOpen(String typeId) {
        if (typeOf(typeId).isWindowed()) return frames.containsKey(typeId);
        // BY IDENTITY, ACROSS BOTH HALVES -- "is this type on screen anywhere in its region".
        //
        // Not `host.showing(sideOf(typeId))`, which asks the stored record which half to look in and
        // therefore answers "closed" whenever the record and the host disagree. It still answers per
        // TYPE rather than per region, which is what the original note here is about: asking the region
        // flatly made a split region report its neighbour, so opening Problems bottom-left and Services
        // bottom-right had whichever the host answered with count as "the" open panel while the other
        // was reported shut. Both properties hold at once by matching the id in either half.
        //
        // The divergence is not hypothetical: a placement read back from a session can name the other
        // half, and every caller downstream believed the panel was already closed. hidePanel was then
        // never reached, the region kept recording an occupant that setContent had reparented into a
        // frame, and its width stayed behind as a blank column.
        return showingSideOf(regions.host(regionOf(typeId)), typeId) != null;
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
        // A PANEL PUT AWAY IS NO LONGER WAITING TO OPEN. Without this a hide between the ask and the
        // retry is undone by the retry, which is the same "an intent outlives the thing it described"
        // shape a stale watch has.
        pendingWindowedShows.remove(typeId);
        if (typeOf(typeId).isWindowed()) return hideFrame(typeId);
        // THE REGION AND THE HALF THE HOSTS ACTUALLY HOLD IT IN, not the ones the record names -- and
        // the two can disagree. The record is written by this class; the host is written by this class AND by a
        // session restore, so a placement read back from disk naming the other half left the guard
        // below refusing to clear anything. Nothing failed loudly: the panel then went into its frame
        // (setContent reparents it out from under the host), and the region was left recording an
        // occupant it no longer contained -- so `isEmpty()` stayed false, `sync()` kept the region in
        // the split, and its whole width stayed behind as a blank column. "Sometimes undocking leaves
        // the previous dock space empty", which is precisely what a stale record looks like from
        // outside. Asking the host makes the host the truth about where things are, which it already is.
        DockRegion holding = showingRegionOf(typeId);
        if (holding == null) return false;
        RegionHost host = regions.host(holding);
        RegionSide side = showingSideOf(host, typeId);
        if (host == null || side == null) return false;
        // BOTH SHARES ARE READ BEFORE the clear, because a region with nothing in it is about to leave the
        // frame's split and a half that just emptied takes its divider with it -- neither share stays
        // readable. Same shape as the old hidePanel, which captured placement before a close for the same
        // reason; that part of it was always right.
        float weight = regions.weightOf(holding);
        float sideWeight = regions.sideWeightOf(holding);
        host.clear(side);
        regions.sync();
        ToolWindowState placement = placementOf(typeId).withVisible(false);
        // ...AND THEY BELONG TO THE REGION THAT IS LOSING IT. Kept only when that is the region the record
        // names: a panel taken out of a region it had been MOVED away from would otherwise write one
        // region's size into another region's record, which is the size the panel comes back at.
        if (holding == regionOf(typeId)) {
            placement = placement.withWeight(weight).withSideWeight(sideWeight);
        }
        toolWindows.put(placement);
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
        ToolWindowType type = typeOf(typeId);
        if (type.isWindowed()) return showInFrame(typeId, type);
        DockRegion region = regionOf(typeId);
        RegionHost host = regions.host(region);
        if (host == null) {
            // NO REGION YET, which is an ordinary state rather than a refusal: the regions are built when
            // the workbench joins a window, and a restore -- or a server -- can ask before that.
            // Remembered and replayed, exactly as the windowed branch below does. @see #retryPendingShows
            pendingWindowedShows.add(typeId);
            return false;
        }

        ViewContainer container = containers.computeIfAbsent(typeId, this::buildContainer);
        if (container == null) return false;
        pendingWindowedShows.remove(typeId);

        // INTO ITS OWN HALF. A region holds two, so showing one no longer displaces the other -- which is
        // the whole of "Problems bottom-left, Services bottom-right, both at once".
        RegionSide side = sideOf(typeId);
        // AND OUT OF WHEREVER ELSE IT IS. Showing a panel in one half does not take it out of another:
        // `displaced` below clears the half being shown INTO, and nothing cleared the one it came FROM.
        // A restore that moves a panel between regions therefore left the old region recording an
        // occupant that had gone, so it kept its whole band on screen with nothing in it.
        DockRegion holding = showingRegionOf(typeId);
        if (holding != null
                && (holding != region || showingSideOf(regions.host(holding), typeId) != side)) {
            releaseRegionSlot(typeId);
        }
        // WHATEVER WAS IN THAT HALF IS NOW CLOSED, and it has to be told. A half holds one container, so
        // showing this one displaced the last -- and the displaced record still said `visible`, so a
        // session save wrote several tool windows as visible in the same half and the restore showed them
        // all, each displacing the one before. The arrangement that came back was whichever happened to be
        // applied last.
        String displaced = host.showing(side);
        if (displaced != null && !displaced.equals(typeId)) {
            toolWindows.put(placementOf(displaced).withVisible(false));
        }
        host.setSideWeight(placementOf(typeId).sideWeight());
        host.show(side, typeId, container);
        regions.sync();
        toolWindows.put(placementOf(typeId).withVisible(true));

        // SHOWING A TOOL WINDOW FOCUSES IT, which is what both references do and what makes the rail
        // usable from the keyboard: Alt+6 opens Problems AND puts you in it, rather than opening it
        // and leaving the caret wherever it was.
        //
        // It is also the only way the rail's own button can ever light up. Clicking the button focuses
        // THE BUTTON -- a rail button is an ordinary focusable Button -- which is in the stripe, not in
        // the panel, so `__panel-focused__` was correctly false for the very gesture that opened it.
        // Focus follows the thing you asked for, not the control you asked with.
        //
        // requestPointerFocus, never requestFocus: the latter rings, and a panel outlined on every
        // open is exactly the noise :focus-visible exists to remove.
        UIDocument window = container.document();
        if (window != null) window.focus().requestPointerFocus(container);
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

    /**
     * The region a type is <b>actually</b> showing in, or null when nothing is holding it.
     *
     * <p>The other half of {@link #showingSideOf}, and it was missing for the same reason: the record
     * names a region and the hosts are the truth, and the two can disagree. A session restore is where
     * they do — it can move a panel between regions, and every lookup that started from the record then
     * searched the region the panel is going to rather than the one it is in. The band it left behind
     * stayed on screen with nothing in it.</p>
     */
    @Nullable
    private DockRegion showingRegionOf(String typeId) {
        for (DockRegion region : DockRegion.values()) {
            if (showingSideOf(regions.host(region), typeId) != null) return region;
        }
        return null;
    }

    /**
     * Which half of {@code host} is actually showing {@code typeId}, or null if neither is.
     *
     * <p>Distinct from {@link #sideOf}, which answers from the stored placement — that is the right
     * answer for "where should this go", and the wrong one for "where is it now".</p>
     */
    @Nullable
    private RegionSide showingSideOf(@Nullable RegionHost host, String typeId) {
        if (host == null) return null;
        for (RegionSide candidate : RegionSide.values()) {
            if (typeId.equals(host.showing(candidate))) return candidate;
        }
        return null;
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
    /**
     * Takes out anything on screen that the record about to be applied says <b>nothing</b> about.
     *
     * <p>A session is applied by replacing the store and re-applying every entry in it, so the loop below
     * can only reach panels the record names. A panel that is open and absent from it is told nothing at
     * all: it keeps its half, its region keeps its band, and what comes back is the arrangement from
     * before the restore rather than the one that was saved.</p>
     *
     * <p>Ordinary rather than exotic — a record written by a build with a different set of extensions
     * enabled, or before that panel existed. And the panel is usually opened by whoever contributed it,
     * <em>before</em> the session is read, so this is the common case rather than the corner one.</p>
     *
     * <p>Only the unrecorded ones. Everything the record does name is placed by the loop, which now takes
     * a panel out of the half it is in on its way to the one it is going to.</p>
     */
    private void releaseUnrecordedOccupants() {
        List<String> orphans = new ArrayList<>();
        for (DockRegion region : DockRegion.values()) {
            RegionHost host = regions.host(region);
            if (host == null) continue;
            for (RegionSide side : RegionSide.values()) {
                String typeId = host.showing(side);
                if (typeId != null && !toolWindows.contains(typeId)) orphans.add(typeId);
            }
        }
        // COLLECTED FIRST: hidePanel clears a half and re-syncs the regions, which is a walk over the
        // very hosts being iterated.
        for (String typeId : orphans) hidePanel(typeId);
    }

    public void applyVisibility() {
        releaseUnrecordedOccupants();
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

    /** Its frame while it is floating or windowed, or null. For tests and diagnostics. */
    @Nullable
    public ToolWindowFrame frameOf(String typeId) {
        return frames.get(typeId);
    }

    // ── Presentation ────────────────────────────────────────────────────────────────────────────

    /**
     * Changes how a tool window is presented — the whole of Dock / Float / Window.
     *
     * <p><b>Hidden first, and only then repointed</b>, which is the discipline {@link #moveTo} already
     * had to learn one field over. {@link #hidePanel} branches on the <em>current</em> mode to decide
     * whether it is clearing a region or destroying a frame; writing the new mode first sends it to
     * dismantle a presentation the tool window is not in yet, so the one it <em>is</em> in is left on
     * screen while the model says it has gone.</p>
     *
     * <p>Reopened afterwards only if it was open. Changing the mode of a closed tool window is a
     * legitimate thing to do — it is how "next time, float it" is said — and must not pop it open.</p>
     */
    public void setType(String typeId, ToolWindowType type) {
        if (typeId == null || type == null) return;
        if (typeOf(typeId) == type) return;

        boolean wasOpen = isPanelOpen(typeId);
        if (wasOpen) hidePanel(typeId);
        toolWindows.put(placementOf(typeId).withType(type));
        if (wasOpen) showPanel(typeId);
        onDidChangePlacement.emit(typeId);
    }

    /**
     * Floats a tool window at {@code (left, top)} — what dragging a stripe button off its rail does.
     *
     * <p>The position is written <b>before</b> the mode, because {@link #setType} is what opens the
     * frame and a frame reads its geometry from the record as it is built. Writing it afterwards would
     * open the float at its last remembered position and then move it, which is one visible frame of
     * the window in the wrong place — the sort of flicker that reads as a layout bug.</p>
     *
     * <h3>{@link ToolWindowType#WINDOWED}, not {@code FLOATING}</h3>
     *
     * <p>IntelliJ's tear-out produces its Float mode, which stays above the IDE frame. On a desktop
     * that already has windows, that is the more confining of the two answers and it does not match
     * what the gesture looks like it is doing: an owned window is clamped inside its owner, so a panel
     * dragged out onto the desktop springs back into the editor. Top-level is what "I pulled this out"
     * means here — free of the editor's bounds, with a stacking slot and a taskbar entry of its own.
     * The owned mode stays reachable through the overload, and the Dock button returns either.</p>
     */
    public void floatPanel(String typeId, float left, float top) {
        floatPanel(typeId, left, top, ToolWindowType.WINDOWED);
    }

    /**
     * Floats a tool window <b>under the pointer</b> — where a tear-out drops it.
     *
     * <p>Centred horizontally on {@code x}, so the pointer ends up in the middle of the window's caption
     * rather than at its top-left corner. A window that appears with its corner under the hand reads as
     * having been dropped beside where you let go; one whose title bar is under the hand reads as the
     * thing you were carrying, which is what it is — and it is where the next drag of that caption starts
     * from.</p>
     *
     * <p>Separate from {@link #floatPanel(String, float, float)} rather than a change to it: that one
     * takes the window's own top-left, which is what a session restore and a remembered placement mean,
     * and quietly reinterpreting it as a centre would move every restored window by half its width.</p>
     */
    public void floatPanelUnder(String typeId, float x, float y) {
        ToolWindowState.Bounds remembered = placementOf(typeId).floatingBounds();
        boolean usable = remembered != null && remembered.width() > 0f;
        float width = usable ? remembered.width() : ToolWindowFrame.DEFAULT_WIDTH;
        floatPanel(typeId, x - width / 2f, y, ToolWindowType.WINDOWED);
    }

    /** @see #floatPanel(String, float, float) */
    public void floatPanel(String typeId, float left, float top, ToolWindowType mode) {
        if (typeId == null || mode == null || !mode.isWindowed()) return;
        ToolWindowState.Bounds remembered = placementOf(typeId).floatingBounds();
        boolean usable = remembered != null && remembered.width() > 0f && remembered.height() > 0f;
        float width = usable ? remembered.width() : ToolWindowFrame.DEFAULT_WIDTH;
        float height = usable ? remembered.height() : ToolWindowFrame.DEFAULT_HEIGHT;
        toolWindows.put(placementOf(typeId)
                .withFloatingBounds(new ToolWindowState.Bounds(left, top, width, height)));
        setType(typeId, mode);
        if (!isPanelOpen(typeId)) showPanel(typeId);
    }

    /**
     * Where a windowed tool window is <b>right now</b>, or where it was last remembered.
     *
     * <p>The record is only written at the two moments the window is not being looked at — when it is
     * torn out, and when it is hidden — because those are the moments the geometry would otherwise be
     * lost. Everything in between is invisible to it: the window is moved and resized by dragging it,
     * which is a {@code WindowFrame}'s own business and reaches nothing here.</p>
     *
     * <p>That is fine until something asks the record a question while the window is still on screen, and
     * a session save is exactly that. It wrote the bounds from the tear-out — the drop point, at the
     * default size — so a float that had been moved and resized came back where it first appeared and at
     * a size nobody chose. The window was in the record and its geometry was a session old, which reads
     * as the placement not being restored at all.</p>
     *
     * <p>Asked rather than pushed, deliberately: a listener on every move and resize would write the
     * record sixty times a second during a drag to answer a question nobody has yet.</p>
     */
    @Nullable
    public ToolWindowState.Bounds floatingGeometryOf(String typeId) {
        ToolWindowFrame frame = frames.get(typeId);
        // `bounds()` never answers a zero box -- it falls back to the last measurement it had. @see
        // ToolWindowFrame#bounds()
        return frame != null ? frame.bounds() : placementOf(typeId).floatingBounds();
    }

    /** Puts a floating or windowed tool window back in the region it never stopped belonging to. */
    public void dockPanel(String typeId) {
        setType(typeId, ToolWindowType.DOCKED);
    }

    /**
     * Opens a tool window's frame.
     *
     * <p><b>Built fresh every time, and destroyed on every hide.</b> A frame is chrome around a content
     * slot — cheap — while the {@link ViewContainer} it hosts is the expensive, stateful thing, and that
     * is cached across every presentation change. Keeping a hidden frame around instead would mean
     * tracking which parent it is currently in across mode switches, and the two attachment paths
     * ({@code attachOwned} versus {@code openWindow}) are exactly the pair that must not be confused.
     * The one thing a frame knows that the container does not is its geometry, and that is precisely
     * what {@link ToolWindowState#floatingBounds()} exists to carry.</p>
     */
    private boolean showInFrame(String typeId, ToolWindowType type) {
        ViewContainer container = containers.computeIfAbsent(typeId, this::buildContainer);
        if (container == null) return false;
        UIElement anchor = regions.root();
        UIDocument window = anchor.document();
        if (window == null) {
            // NOT A FAILURE — A "NOT YET". A windowed tool window needs a UIDocument to open into, and a
            // session restore can legitimately run before the tree has one: a host that restores on its
            // first frame does so before anything called UIDocument.init, and the harness does exactly
            // that. The DOCKED path needs no window at all, so it succeeded and the windowed ones
            // returned false into a caller that ignores the result — every float and every windowed
            // tool window silently failed to come back, with the record on disk perfectly correct.
            //
            // Remembered and replayed the moment a window appears. @see #retryPendingShows
            pendingWindowedShows.add(typeId);
            return false;
        }
        pendingWindowedShows.remove(typeId);
        releaseRegionSlot(typeId);

        ToolWindowFrame frame = frames.get(typeId);
        if (frame == null) {
            DockPanelDescriptor descriptor = registry.descriptor(typeId);
            frame = new ToolWindowFrame(typeId, descriptor != null ? descriptor.title() : typeId, container);
            frame.onDockRequested.connect(() -> dockPanel(typeId));
            // ITS OWN ✕ AND ITS OWN MINIMISE both come through here, and both mean hide -- the frame's
            // policy is HIDE_ON_CLOSE. Routed back through hidePanel rather than left to the frame, or
            // the record would still say visible and a session restore would reopen something the user
            // had just put away.
            frame.onHidden.connect(() -> hidePanel(typeId));
            frames.put(typeId, frame);
        }
        frame.setMode(type);
        // The frame's caption has a close button, so the container's own would be a second one beside it.
        container.setHideButtonVisible(false);

        // A ZERO RECT IS REFUSED, not honoured, and it is the same test the codec makes on the way in:
        // a 0x0 frame at the origin is a legal encoding and an unusable window, so it cannot be told
        // from "never floated" by looking at it. Restoring one puts a window on screen with nothing to
        // see and nothing to grab.
        ToolWindowState.Bounds bounds = placementOf(typeId).floatingBounds();
        if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
            frame.moveTo(bounds.left(), bounds.top());
            frame.resizeTo(bounds.width(), bounds.height());
        } else {
            frame.resizeTo(ToolWindowFrame.DEFAULT_WIDTH, ToolWindowFrame.DEFAULT_HEIGHT);
        }

        if (type == ToolWindowType.FLOATING) {
            // OWNED BY THE WORKBENCH'S OWN WINDOW, which is what makes a float travel with the thing it
            // was torn out of -- and NON-BLOCKING, or the owner's whole surface goes under a transparent
            // slot and the panel reads as a modal dialog. Falls back to a top-level window when the
            // workbench is not in a frame at all: a bare UIDocument with no desktop window open is a
            // legitimate host, and refusing there would make the gesture work in the application and
            // not in a test.
            UIElement scope = window.focus().scopeOf(anchor);
            if (scope instanceof WindowFrame owner) owner.attachOwned(frame);
            else Desktop.of(window).addWindow(frame);
        } else {
            Desktop.of(window).addWindow(frame);
            // TOP-LEVEL, BUT OWNED. The frame keeps everything a window has -- it can be dragged
            // anywhere on the desktop and has its own taskbar entry -- and gains the one thing a tool
            // window needs from the editor it came out of: it stays above it. Without this, clicking the
            // editor buries the panel that was torn out of it, which is the behaviour no desktop has.
            if (window.focus().scopeOf(anchor) instanceof WindowFrame owner) frame.setOwnerWindow(owner);
        }

        toolWindows.put(placementOf(typeId).withVisible(true));
        window.focus().requestPointerFocus(container);
        return true;
    }

    /**
     * Closes a tool window's frame, remembering where it was.
     *
     * <p>The geometry is read <b>before</b> the destroy, for the reason {@link #hidePanel} reads both
     * region shares before clearing a host: a destroyed frame has been detached, and a detached element
     * measures nothing.</p>
     *
     * <p><b>And the owner is told</b>, which is the half that fails silently. A frame attached with
     * {@code attachOwned} is in its owner's live set, and that set is what sizes the owned surface —
     * destroying the frame without releasing it leaves a full-size transparent slot over the owner's
     * content, swallowing every click on the window the float came out of. Nothing about that symptom
     * points at a tool window.</p>
     *
     * <p><b>The removal is the first statement, and that is what makes this re-entrant-safe.</b> The
     * frame's own ✕ and its minimise both reach here through {@code onHidden}, and {@code destroy()}
     * below emits {@code onHidden} on its way out — so this method calls itself, every time, on the very
     * path that a user close takes. Taking the frame out of the map before destroying it means the
     * second entry finds nothing and returns immediately. Doing it in the obvious order instead would
     * re-read the bounds of a frame that is mid-destroy and release the owner twice.</p>
     */
    private boolean hideFrame(String typeId) {
        ToolWindowFrame frame = frames.remove(typeId);
        if (frame == null) return false;

        toolWindows.put(placementOf(typeId).withFloatingBounds(frame.bounds()).withVisible(false));

        // NOT RELEASED FROM ITS OWNER HERE, and the attempt is worth recording: reading the owner off
        // the tree (`frame.parent().parent()`) finds nothing, because this method is reached
        // through the frame's own onHidden and by then the detach has already happened. WindowFrame.hide
        // captures the owner before detaching and releases it there, which is the only place that can.

        // The container is going back to a region eventually, so it gets its own close button back.
        ViewContainer container = containers.get(typeId);
        if (container != null) container.setHideButtonVisible(true);

        frame.destroy();
        return false;
    }
}
