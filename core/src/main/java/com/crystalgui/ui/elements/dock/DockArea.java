package com.crystalgui.ui.elements.dock;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A {@link DockLayout}, drawn — and the one place a drag becomes a structural change.
 *
 * <p>Branches become {@link SplitView}s and leaves become {@link DockGroup}s. Nothing here decides what a
 * drop <em>means</em>: {@link DockDropZones} answers where the pointer is and {@link DockLayout} performs
 * the operation, both headlessly and both tested without a window. This class is the part that cannot be
 * tested that way — hit testing, element reuse, and when it is safe to rebuild.</p>
 *
 * <h3>Rebuilds are deferred by a frame, always</h3>
 *
 * <p>A drop arrives while the drag controller still holds references into the tree it is about to
 * restructure, so rebuilding inside the handler detaches the element the drag is anchored on. The table
 * header froze exactly this way — sort once and no header could be clicked or resized again — and the
 * rule it produced is that a widget must never rebuild the elements it is being clicked or dragged on.
 * Every structural change here therefore sets a flag and the ticker does the work on the next frame.</p>
 */
public class DockArea extends UIElement implements UIFrameTicker {

    /** On the area while a dock drag is in flight, so a theme can dim or outline the whole thing. */
    public static final String DRAGGING_CLASS = "__dock-dragging__";

    /**
     * The one child this area owns, holding whatever the layout currently builds to.
     *
     * <p><b>Marked internal exactly once, while empty, and never again.</b> {@code markAsInternal()}
     * recurses into the whole subtree, so calling {@code addInternalChild} on a built tree stamps every
     * descendant internal — including every {@code Tab}. {@code removeChild} refuses internal children
     * outright, so from the next rebuild on {@code TabView.clearTabs()} emptied its list and left the tab
     * ELEMENTS in the rail: strips that grew by one dead, unclickable tab per rebuild while every
     * assertion against {@code getTabs()} stayed green.</p>
     *
     * <p>Going through a wrapper means the built tree is added with an ordinary public
     * {@code addChild}, which marks nothing.</p>
     */
    private final UIElement content = new UIElement();

    private final DockPanelRegistry<UIElement> registry;
    private DockLayout layout;

    /**
     * Leaf → its group. Identity-keyed, because two leaves holding the same panels are still two
     * different panes and must not share a group.
     */
    private final Map<DockLeaf, DockGroup> groups = new IdentityHashMap<>();

    /** Reused across rebuilds so a split does not throw away every pane's scroll position. */
    private final List<SplitView> splitPool = new ArrayList<>();

    private boolean rebuildPending = true;
    private boolean ticking;
    @Nullable
    private DockGroup activeGroup;
    @Nullable
    private DockGroup previewGroup;
    @Nullable
    private DockDropZone previewZone;
    private boolean previewIsOuterEdge;
    /** Where in the target strip a MERGE would land, or negative to append. */
    private int previewTabIndex = -1;

    public DockArea(DockPanelRegistry<UIElement> registry, DockLayout layout) {
        this.registry = registry;
        this.layout = layout;
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        StyleGroup.defaultPipeline(content.getStyle().getLayoutGroup(), l -> l.flexGrow(1f).flexBasis(0));
        addInternalChild(content);
        registerDropHandling();
    }

    /** The tree is built from the layout; content comes from the registry. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public DockPanelRegistry<UIElement> registry() {
        return registry;
    }

    public DockLayout layout() {
        return layout;
    }

    /** Replaces the whole arrangement — a restore, or a reset to the default. */
    public DockArea setLayout(DockLayout layout) {
        this.layout = layout;
        this.activeGroup = null;
        requestRebuild();
        return this;
    }

    /**
     * What the layout currently builds to — a {@link SplitView} for a branch, a {@link DockGroup} for a
     * single leaf, or {@code null} before the first rebuild.
     */
    @Nullable
    public UIElement builtRoot() {
        return content.getChildren().isEmpty() ? null : content.getChildren().get(0);
    }

    public DockGroup groupFor(DockLeaf leaf) {
        return groups.get(leaf);
    }

    @Nullable
    public DockGroup activeGroup() {
        return activeGroup;
    }

    /**
     * The group commands resolve against.
     *
     * <p>Tracked explicitly and never inferred from focus: clicking inside a graph canvas focuses the
     * <em>canvas</em>, so a command asking "which group is active" by walking up from the focused element
     * would get the right answer only for groups whose content happens not to be focusable.</p>
     */
    public DockArea setActiveGroup(@Nullable DockGroup group) {
        if (activeGroup == group) return this;
        if (activeGroup != null) activeGroup.setActive(false);
        activeGroup = group;
        if (group != null) group.setActive(true);
        return this;
    }

    /**
     * Registers the ticker the first time this area is actually in a window.
     *
     * <p>There is no attach hook on {@code UIElement}, and the constructor runs while the area is still
     * detached, so registration rides on the first layout — the same lazy pattern {@code CanvasView} uses.
     * {@code registerTicker} is {@code HashSet}-backed and idempotent, so the guard is for the lookup
     * rather than for correctness.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        window.registerTicker(this);
        ticking = true;
    }

    // ── Rebuild ─────────────────────────────────────────────────────────────────────────────────

    /** Marks the tree for rebuilding on the next frame. Never rebuilds inline — see the class javadoc. */
    public void requestRebuild() {
        rebuildPending = true;
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (rebuildPending) {
            rebuildPending = false;
            rebuild();
        }
        return true;
    }

    private void rebuild() {
        // Read the user's divider positions back into the layout BEFORE tearing the split views down, or
        // every rebuild resets the panes to whatever weights the layout was last saved with -- i.e. a
        // split somewhere else in the tree silently undoes the drag you just did here.
        pullWeightsIntoLayout();

        content.clearAllChildren();
        pruneStaleGroups();
        splitPool.clear();

        UIElement built = buildNode(layout.root(), 0);
        if (built != null) {
            // flex-grow plus a zero basis, and deliberately NOT an explicit width/height: this area is a
            // flex container, so the child fills the main axis by growing and the cross axis by stretch.
            // A literal size here would have to be a real number, and there is no "auto" to write.
            StyleGroup.defaultPipeline(built.getStyle().getLayoutGroup(),
                    l -> l.flexGrow(1f).flexBasis(0));
            content.addChild(built);
        }
        if (activeGroup == null || activeGroup.leaf().parent() == null) {
            List<DockLeaf> leaves = layout.leaves();
            setActiveGroup(leaves.isEmpty() ? null : groups.get(leaves.get(0)));
        }
    }


    /** Groups whose leaf left the tree are dropped, or the map grows for the life of the window. */
    private void pruneStaleGroups() {
        List<DockLeaf> live = layout.leaves();
        groups.keySet().removeIf(leaf -> !live.contains(leaf));
    }

    @Nullable
    private UIElement buildNode(DockNode node, int depth) {
        if (node.isLeaf()) {
            DockLeaf leaf = (DockLeaf) node;
            DockGroup group = groups.computeIfAbsent(leaf, l -> new DockGroup(this, l));
            group.sync();
            return group;
        }

        DockBranch branch = (DockBranch) node;
        if (branch.childCount() == 0) return null;
        if (branch.childCount() == 1) return buildNode(branch.child(0), depth + 1);

        SplitView split = new SplitView();
        splitPool.add(split);
        split.setOrientation(branch.orientation(layout.rootOrientation()) == DockOrientation.HORIZONTAL
                ? SplitView.Orientation.HORIZONTAL
                : SplitView.Orientation.VERTICAL);
        // A dock divider must be able to reach the very edge; the 5..95 default is a two-pane nicety that
        // would stop a sidebar being collapsed to a sliver.
        split.setLimits(0f, 100f);

        while (split.paneCount() < branch.childCount()) split.addPane();

        float[] weights = new float[branch.childCount()];
        for (int i = 0; i < branch.childCount(); i++) {
            weights[i] = Math.max(0.0001f, branch.child(i).size());
            UIElement child = buildNode(branch.child(i), depth + 1);
            if (child != null) {
                StyleGroup.defaultPipeline(child.getStyle().getLayoutGroup(),
                        l -> l.flexGrow(1f).flexBasis(0));
                split.pane(i).addChild(child);
            }
        }
        split.setWeights(weights);
        return split;
    }

    /** Copies every live divider position back into the layout tree it came from. */
    public void pullWeightsIntoLayout() {
        for (SplitView split : splitPool) {
            DockBranch branch = branchFor(split);
            if (branch == null) continue;
            float[] weights = split.getWeights();
            for (int i = 0; i < Math.min(weights.length, branch.childCount()); i++) {
                branch.child(i).size(Math.max(0.0001f, weights[i]));
            }
        }
    }

    /**
     * Which branch a split view was built for.
     *
     * <p>Resolved by walking the layout rather than stored on the split, because a split view is reused
     * across rebuilds and a field would go stale exactly when the tree changed shape — the case it exists
     * to survive.</p>
     */
    @Nullable
    private DockBranch branchFor(SplitView split) {
        return branchFor(layout.root(), split);
    }

    @Nullable
    private DockBranch branchFor(DockBranch branch, SplitView split) {
        int index = splitPool.indexOf(split);
        if (index < 0) return null;
        List<DockBranch> ordered = new ArrayList<>();
        collectSplitBranches(layout.root(), ordered);
        return index < ordered.size() ? ordered.get(index) : null;
    }

    /** Branches that actually became a split view, in the order {@link #buildNode} visits them. */
    private void collectSplitBranches(DockNode node, List<DockBranch> out) {
        if (node.isLeaf()) return;
        DockBranch branch = (DockBranch) node;
        if (branch.childCount() >= 2) out.add(branch);
        for (DockNode child : branch.children()) collectSplitBranches(child, out);
    }

    // ── Operations ──────────────────────────────────────────────────────────────────────────────

    /** Performs a drop and schedules the rebuild. Returns the leaf the content landed in. */
    @Nullable
    public DockLeaf performDrop(DockDragPayload payload, @Nullable DockLeaf target,
                                DockDropZone zone, boolean outerEdge) {
        return performDrop(payload, target, zone, outerEdge, -1);
    }

    /** As above, with a position in the target strip for a merge. Negative appends. */
    @Nullable
    public DockLeaf performDrop(DockDragPayload payload, @Nullable DockLeaf target,
                                DockDropZone zone, boolean outerEdge, int tabIndex) {
        // A reorder inside one strip is a MOVE, not a detach-and-reinsert. Going through detach would
        // remove the panel, find the leaf empty, and collapse the pane the user is dragging within --
        // which for a single-tab group deletes the thing being reordered.
        if (!outerEdge && zone == DockDropZone.MERGE && target != null
                && target == payload.sourceLeaf() && !payload.isWholeGroup()) {
            int from = target.indexOf(payload.panel());
            if (from >= 0 && tabIndex >= 0) {
                // An insertion index counts boundaries, a target index counts slots: removing the panel
                // first shifts everything after it down by one.
                target.move(from, tabIndex > from ? tabIndex - 1 : tabIndex);
                requestRebuild();
                return target;
            }
            return target;
        }

        DockNode moved = detach(payload);
        if (moved == null) return null;

        DockLeaf landed;
        if (outerEdge) {
            landed = layout.dropOnOuterEdge(zone, moved);
        } else {
            if (target == null || target.parent() == null) {
                // The target left the tree while the drag was in flight — because detaching the source
                // collapsed the branch it was in. Putting the content back on the outer edge is the only
                // answer that cannot lose it.
                landed = layout.dropOnOuterEdge(DockDropZone.SPLIT_RIGHT, moved);
            } else {
                landed = layout.drop(target, zone, moved, tabIndex);
            }
        }
        requestRebuild();
        return landed;
    }

    /**
     * Takes what is being dragged out of its tree, as a detached node.
     *
     * <p>A single panel becomes a fresh one-panel leaf; a whole group is torn out as it stands. Both go
     * through {@link DockLayout#tearOut}, which is the same call as a close — the difference is only what
     * happens to the node next.</p>
     */
    @Nullable
    private DockNode detach(DockDragPayload payload) {
        DockArea source = payload.sourceArea();
        DockLeaf sourceLeaf = payload.sourceLeaf();

        if (payload.isWholeGroup()) {
            DockNode torn = source.layout.tearOut(sourceLeaf);
            if (torn != null) source.requestRebuild();
            return torn;
        }

        DockPanelRef panel = payload.panel();
        if (panel == null || sourceLeaf.indexOf(panel) < 0) return null;
        sourceLeaf.remove(panel);
        if (sourceLeaf.isEmpty() && !sourceLeaf.isCentral()) source.layout.remove(sourceLeaf);
        source.requestRebuild();
        return new DockLeaf(panel);
    }

    public void closePanel(DockPanelRef panel) {
        if (layout.closePanel(panel)) requestRebuild();
    }

    /** Maximizes a group, or restores when it is already the maximized one. */
    public void toggleMaximize(DockLeaf leaf) {
        layout.maximize(layout.maximizedLeaf() == leaf ? null : leaf);
        requestRebuild();
    }

    // ── Drag and drop ───────────────────────────────────────────────────────────────────────────

    /**
     * Makes one tab draggable.
     *
     * <p>Called for every tab on every strip rebuild. The panel is passed in rather than looked up,
     * because a listener may only be attached once and the tab it is attached to outlives any particular
     * position in the strip — capturing an index here is the pooled-gutter-arrow bug in a new costume.</p>
     */
    void installTabDrag(DockGroup group, DockPanelRef panel, Tab tab) {
        tab.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            UIWindow window = getAttachedWindow();
            if (window == null) return;
            setActiveGroup(group);
            addClass(DRAGGING_CLASS);
            window.getInputHandler().getDragController().startDrag(tab,
                    event.getPosition().x(), event.getPosition().y(),
                    DockDragPayload.ofPanel(this, group.leaf(), panel),
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // Nothing per-frame: the preview is driven by DragEvent.Over on the AREA,
                            // which is dispatched against what is geometrically under the pointer.
                            // This listener is anchored on the tab, which pointer capture pins to the
                            // source for the whole gesture — it can never tell where the drop would go.
                        }

                        @Override
                        public void onDragEnd(float mx, float my) {
                            endDragVisuals();
                        }

                        @Override
                        public void onDragCancel() {
                            endDragVisuals();
                        }
                    });
        }, false, false);
    }

    private void endDragVisuals() {
        removeClass(DRAGGING_CLASS);
        clearPreview();
    }

    private void registerDropHandling() {
        // Over and Drop bubble, so listening on the area alone covers every group inside it. Doing it per
        // group would mean re-attaching a listener on every strip rebuild for no gain.
        events.getGroup(DragEvent.Over.class).attachListener((el, event) -> {
            if (!(event.getPayload() instanceof DockDragPayload)) return;
            DockDragPayload payload = (DockDragPayload) event.getPayload();
            if (!updatePreview(payload, event.getPosition().x(), event.getPosition().y())) {
                clearPreview();
                return;
            }
            // Rejection is the default and is re-read every frame: accepting is calling preventDefault on
            // THIS event, never a latched flag. A latched accept keeps accepting after the pointer has
            // moved somewhere that should refuse.
            event.preventDefault();
        }, false, true);

        events.getGroup(DragEvent.Leave.class).attachListener((el, event) -> clearPreview(), false, false);

        events.getGroup(DragEvent.Drop.class).attachListener((el, event) -> {
            if (!(event.getPayload() instanceof DockDragPayload)) return;
            DockDragPayload payload = (DockDragPayload) event.getPayload();
            if (previewZone == null) {
                clearPreview();
                return;
            }
            DockLeaf target = previewGroup != null ? previewGroup.leaf() : null;
            performDrop(payload, target, previewZone, previewIsOuterEdge, previewTabIndex);
            clearPreview();
        }, false, true);
    }

    /**
     * Works out what a drop here would do, and shows it.
     *
     * @return whether a drop is possible at all
     */
    private boolean updatePreview(DockDragPayload payload, float pointerX, float pointerY) {
        var localArea = screenToLocal(pointerX, pointerY);
        var areaCache = getRuntimeCache();
        float areaX = localArea.x() - areaCache.getX();
        float areaY = localArea.y() - areaCache.getY();

        DockGroup group = groupUnder(pointerX, pointerY);

        // A tab strip beats the outer edge, and the ordering is load-bearing rather than a preference.
        // The topmost group's strip sits INSIDE the area's top edge band, so an edge-first order makes
        // tab reordering impossible in exactly the group people reorder tabs in most. An explicit aim at
        // a strip is unambiguous; an edge band is ambient.
        if (group != null && !payload.isWholeGroup() && group.isOverStrip(pointerX, pointerY)) {
            int index = group.insertionIndexAt(pointerX);
            if (isNoOpReorder(payload, group, index)) return false;
            setPreview(group, DockDropZone.MERGE, false, index);
            return true;
        }

        // Otherwise the outer edge wins over the group under the pointer: it is the only way to say
        // "beside ALL of these", and a pointer in the band is always over some group as well, so a
        // group-first order would never reach it.
        DockDropZone outer = DockDropZones.forOuterEdge(areaX, areaY, areaCache.getWidth(), areaCache.getHeight());
        if (outer != null) {
            setPreview(null, outer, true, -1);
            return true;
        }

        if (group == null) return false;

        // Dropping a group into itself or its own descendants would detach the tree from its own root.
        // Asked here, before the overlay is drawn, rather than at drop time — an overlay offering a drop
        // that will be refused is worse than no overlay.
        DockNode dragged = payload.isWholeGroup() ? payload.sourceLeaf() : null;
        if (dragged != null && group.leaf().isUnder(dragged)) return false;
        if (!payload.isWholeGroup() && group.leaf() == payload.sourceLeaf()
                && group.leaf().panelCount() == 1) {
            // The only panel in a strip, dropped back on that strip: nothing to do, and a split would
            // produce an empty pane on one side.
            return false;
        }

        var localGroup = group.screenToLocal(pointerX, pointerY);
        var groupCache = group.getRuntimeCache();
        DockDropZone zone = DockDropZones.forPane(
                localGroup.x() - groupCache.getX(), localGroup.y() - groupCache.getY(),
                groupCache.getWidth(), groupCache.getHeight(),
                true, payload.isGroupDrag());
        setPreview(group, zone, false, -1);
        return true;
    }

    /**
     * Whether dropping here would put the panel back exactly where it already is.
     *
     * <p>Both boundaries of a tab count as "no move": dragging a tab onto its own left edge and onto its
     * own right edge are the same non-gesture, and offering a caret for either is a promise the drop
     * cannot keep.</p>
     */
    private boolean isNoOpReorder(DockDragPayload payload, DockGroup group, int index) {
        if (group.leaf() != payload.sourceLeaf()) return false;
        int from = group.leaf().indexOf(payload.panel());
        return from >= 0 && (index == from || index == from + 1);
    }

    @Nullable
    private DockGroup groupUnder(float screenX, float screenY) {
        for (DockGroup group : groups.values()) {
            if (group.getAttachedWindow() != null && group.containsScreenPoint(screenX, screenY)) return group;
        }
        return null;
    }

    private void setPreview(@Nullable DockGroup group, DockDropZone zone, boolean outerEdge, int tabIndex) {
        if (previewGroup != null && previewGroup != group) hidePreviewOn(previewGroup);
        previewGroup = group;
        previewZone = zone;
        previewIsOuterEdge = outerEdge;
        previewTabIndex = tabIndex;
        if (group == null) return;
        if (tabIndex >= 0) {
            // A caret between tabs, not a half-pane wash: the two previews answer different questions and
            // showing both at once says the drop will do two things.
            group.hideDropPreview();
            group.showInsertionMarker(tabIndex);
        } else {
            group.hideInsertionMarker();
            group.showDropPreview(zone);
        }
    }

    private void clearPreview() {
        if (previewGroup != null) hidePreviewOn(previewGroup);
        previewGroup = null;
        previewZone = null;
        previewIsOuterEdge = false;
        previewTabIndex = -1;
    }

    private static void hidePreviewOn(DockGroup group) {
        group.hideDropPreview();
        group.hideInsertionMarker();
    }
}
