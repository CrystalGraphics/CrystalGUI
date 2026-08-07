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
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Signal;
import java.util.Objects;

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

    /**
     * A dock brings its own verbs — split, close, cycle tabs — with nothing installing them.
     *
     * <p>Their chords are declared on the commands rather than bound here, because they are genuinely
     * application-wide: a dock wraps everything, so "is there a dock above me" is true almost everywhere.
     * See {@code DockCommands}.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        DockCommands.register();
    }

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

    /**
     * Each live {@link SplitView} and the branch it was built for.
     *
     * <p>Paired at build time and never re-derived. The first version resolved the branch by walking the
     * layout and matching positional index, which is correct only while the tree has not changed shape —
     * i.e. never, at the moment it matters. A drop mutates the layout and <em>then</em> asks for the
     * weights, so the old split views were mapped onto the new branches and their weights written to the
     * wrong ones: split a pane and an unrelated column three panes away changed width.</p>
     */
    private final Map<SplitView, DockBranch> splitBranches = new IdentityHashMap<>();

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

    /**
     * The group {@code element} is inside, or null when it is not in this dock at all.
     *
     * <h3>The "next to me" primitive</h3>
     *
     * <p>Without it, a widget that wants to open something beside itself has to find its own panel ref
     * and ask the layout — {@code CrystalEditor.showCompiled} did precisely that, which is why "show the
     * generated shader next to its graph" was application code instead of a dock capability.</p>
     *
     * <p>Walks {@code getParent()}, which returns the real parent <b>regardless of how a child was
     * added</b>. That matters: a panel's content is often an internal child of a composite, so a walk
     * that skipped internal parents would answer null for exactly the widgets that are built properly —
     * the same rule {@code DataContext} follows and for the same reason.</p>
     */
    @Nullable
    public DockGroup groupOf(@Nullable UIElement element) {
        for (UIElement scope = element; scope != null; scope = scope.getParent()) {
            if (scope instanceof DockGroup group && group.dockArea() == this) return group;
        }
        return null;
    }

    /** The leaf holding {@code input}, or null when nothing does. */
    @Nullable
    public DockLeaf leafOf(DockInput input) {
        return input == null ? null : layout.leafContaining(input.ref());
    }

    /**
     * Tells the dock that a panel's presentation has changed, so its tab can catch up.
     *
     * <h3>Why an owner asks rather than reaching in</h3>
     *
     * <p>A tab's label is not always a function of the panel ref: a document gains a dirty marker by being
     * typed into, and nothing in the ref moves when that happens. Something outside therefore has to say
     * so — and before this existed, "something outside" did it by walking
     * {@code layout().leaves()} to {@code groupFor} to {@code panels()} to {@code tabFor} and calling
     * {@code setText} on a widget it did not own. That works until the dock changes how it builds a tab,
     * at which point every caller that learned the walk is wrong, silently, because the walk still
     * compiles.</p>
     *
     * <p>This is the same seam VS Code spells {@code onDidChangeLabel} and IntelliJ spells
     * {@code EditorTabTitleProvider}: the owner of the fact announces it, and the owner of the widget
     * decides what that means. Callers no longer need to know that a tab is a {@code Tab}, or that there
     * is a strip, or that groups exist.</p>
     *
     * <p><b>Refreshes in place</b>, never by rebuilding the strip — see
     * {@link DockGroup#refreshPresentation}. Unknown panels are ignored rather than reported: a panel
     * that has just been closed is the ordinary case, not a caller error.</p>
     */
    public void refreshPanelPresentation(DockPanelRef panel) {
        for (DockLeaf leaf : layout.leaves()) {
            DockGroup group = groups.get(leaf);
            if (group != null) group.refreshPresentation(panel);
        }
    }

    /**
     * The group commands and "what is active" resolve against.
     *
     * <h3>Falls back to the central work area rather than reporting nothing</h3>
     *
     * <p>{@code activeGroup} is set by a press or by focus, so <b>immediately after a restore it is
     * null</b> — the layout is on screen, a document is the front tab, and nothing has been clicked yet.
     * Reporting null there makes every question downstream answer wrongly: {@code activeFilePath()} says
     * no file is open, so {@code Ctrl+S} silently does nothing, and a panel that follows the active
     * document comes up blank. Both look like separate bugs and are one.</p>
     *
     * <p>The central leaf is the right default because it is the work area — the one leaf that always
     * exists and cannot be closed — and because it is where a restored session's document is. This is
     * what both editors do implicitly: VS Code restores an active editor <em>group</em>, and IntelliJ's
     * {@code FileEditorManager} has a selected editor the moment its state is loaded, neither waiting for
     * a click to decide.</p>
     *
     * <p>Read-side rather than assigned during the rebuild, so it cannot race the order in which groups
     * are built and needs no invalidation when the layout changes.</p>
     */
    @Nullable
    public DockGroup activeGroup() {
        if (activeGroup != null) return activeGroup;
        DockLeaf central = layout.centralLeaf();
        return central == null ? null : groups.get(central);
    }

    /**
     * The panel in front of the active group, or null when nothing is.
     *
     * <p>Derived, never stored — the same read-side reasoning {@link #activeGroup()} gives. What <em>is</em>
     * stored is the last value {@link #onDidChangeActivePanel} announced, which is a different thing:
     * one is the answer, the other is what listeners have been told.</p>
     */
    @Nullable
    public DockPanelRef activePanel() {
        DockGroup group = activeGroup();
        return group == null ? null : group.leaf().activePanel();
    }

    /**
     * The front panel changed — the single most-used signal in the dock.
     *
     * <h3>What it replaced</h3>
     *
     * <p>Three separate per-frame polls, each deriving the same answer from the dock and comparing it
     * with a remembered copy: {@code Workbench.revealActiveFile}, {@code Workbench}'s
     * {@code problems.bindTo} rebind, and {@code CrystalEditor.followActiveGraph}. That is not three
     * problems, it is one missing announcement used three times.</p>
     *
     * <p><b>Emits null</b> when nothing is active, which is a real state rather than an absence: focusing
     * chrome legitimately means no panel is in front. A listener that must keep showing the last real one
     * latches it itself — {@code CrystalEditor.followed} is exactly that, and IntelliJ answers the same
     * requirement the same way, by firing {@code selectionChanged} on <em>editor</em> selection so
     * clicking a tool window never clears what a tool window is looking at.</p>
     */
    public final Signal.Value<DockPanelRef> onDidChangeActivePanel = new Signal.Value<>();

    /** The last value announced, so a repeated announce is silent. Never read as "what is active". */
    @Nullable
    private DockPanelRef announcedPanel;

    /**
     * Announces the active panel if it moved. <b>Idempotent — call it freely.</b>
     *
     * <h3>Why a compare here rather than an emit at each mutation site</h3>
     *
     * <p>The front panel changes through several unrelated paths: a press, focus arriving, a tab
     * selection, a close, a rebuild that had to pick a new group. Emitting from each one means each one
     * has to know whether it really changed anything, and the paths overlap — a tab click both activates
     * a panel and activates its group, which would be two emissions for one change.</p>
     *
     * <p>Comparing against the last announced value makes the callers dumb and the signal exact, which is
     * the contract the tests pin: exactly once per change, and nothing at all on a settled frame.</p>
     *
     * <p>{@code Signal.Value} does <b>not</b> suppress equal values, so this guard is load-bearing rather
     * than belt-and-braces.</p>
     */
    void announceActivePanel() {
        DockPanelRef now = activePanel();
        if (Objects.equals(now, announcedPanel)) return;
        announcedPanel = now;
        onDidChangeActivePanel.emit(now);
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
        announceActivePanel();
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

    /**
     * Brings every strip into line with its leaf, <b>without</b> rebuilding the tree.
     *
     * <p>For the case where only the <em>selection</em> changed — activating a panel that is already open.
     * {@link #requestRebuild()} would work, but it discards and recreates every {@link SplitView} and
     * re-parents every group to do it, which is a structural change made for a state change.</p>
     *
     * <p>It also breaks a rule this engine states plainly: <b>a widget must never rebuild the elements it
     * is being clicked on.</b> Activating an open file is normally a click — on a tab, or on a row in a
     * file tree — and tearing the tree down underneath that click is the shape of bug the table header
     * already cost a session to.</p>
     */
    public void syncGroups() {
        for (DockGroup group : groups.values()) group.sync();
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
        // Weights are pulled BEFORE each mutation (see captureDividerPositions), not here: by now the
        // layout has already changed shape and a branch's child count may no longer match its split's
        // pane count. What is left here is the no-structural-change case -- a plain requestRebuild after
        // a resize -- where the pairing is still exact.
        pullWeightsIntoLayout();

        content.clearAllChildren();
        pruneStaleGroups();
        splitBranches.clear();

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
        // AFTER the tree is built and the fallback has run. A rebuild is how a close, a drop and a
        // restore all reach the front panel, and none of them announces on its own -- setActiveGroup
        // only fires when the GROUP moved, which a close within one group does not.
        announceActivePanel();
        announceLayoutChange();
    }

    /**
     * The arrangement changed <b>structurally</b> — a panel opened, closed or moved.
     *
     * <h3>Structural, not "a rebuild happened"</h3>
     *
     * <p>The dock rebuilds for reasons that are not layout changes: a resize, a presentation refresh. A
     * signal fired from the rebuild itself would therefore be a per-frame callback wearing an event's
     * name — the exact loop this replaces.</p>
     *
     * <p>So it compares the set of panels the last build produced against this one's. That is computed
     * once per rebuild, and rebuilds are already deferred and rare.</p>
     *
     * <p>Its consumer is the activity bar, whose buttons' {@code :checked} state <b>is</b> whether their
     * panel is open — derived from the layout, so it moves exactly when this does.</p>
     */
    public final Signal.Action onDidChangeLayout = new Signal.Action();

    /** The panels the last build put on screen, for the comparison above. Never a source of truth. */
    private List<DockPanelRef> builtPanels = new ArrayList<>();

    private void announceLayoutChange() {
        List<DockPanelRef> now = new ArrayList<>();
        for (DockLeaf leaf : layout.leaves()) now.addAll(leaf.panels());
        if (now.equals(builtPanels)) return;
        builtPanels = now;
        onDidChangeLayout.emit();
    }


    /** Groups whose leaf left the tree are dropped, or the map grows for the life of the window. */
    private void pruneStaleGroups() {
        List<DockLeaf> live = layout.leaves();
        groups.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey())) return false;
            // A departing group takes its panes with it. Closing the LAST panel of a leaf removes the
            // leaf, so this group never syncs again -- meaning the one case that most needs a release is
            // the one a per-sync prune cannot reach.
            entry.getValue().releaseAllPanes();
            return true;
        });
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
        splitBranches.put(split, branch);
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

    /**
     * Copies every live divider position back into the layout tree it came from.
     *
     * <p>Skips a split whose pane count no longer matches its branch — that pairing is stale, and writing
     * through it is how one pane's split silently resized a different column.</p>
     */
    public void pullWeightsIntoLayout() {
        for (Map.Entry<SplitView, DockBranch> entry : splitBranches.entrySet()) {
            SplitView split = entry.getKey();
            DockBranch branch = entry.getValue();
            float[] weights = split.getWeights();
            if (weights.length != branch.childCount()) continue;
            for (int i = 0; i < weights.length; i++) {
                branch.child(i).size(Math.max(0.0001f, weights[i]));
            }
        }
    }

    /**
     * Records where the user has dragged the dividers, before the layout is changed underneath them.
     *
     * <p>Every mutator calls this first. A drop reshapes the tree and only then asks for a rebuild, so
     * reading the weights afterwards means reading them against a tree the split views no longer
     * describe.</p>
     */
    private void captureDividerPositions() {
        pullWeightsIntoLayout();
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
        captureDividerPositions();
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

    /**
     * Asked before a panel closes; returning false stops it.
     *
     * <p>The dock cannot know that a panel has anything worth keeping — it holds refs and layout, not
     * content — so the veto belongs to whoever owns the documents. {@code Workbench} installs one that
     * asks before discarding an edited file.</p>
     *
     * <p>A guard that wants to close <em>after</em> asking the user calls {@link #closePanelDiscarding}
     * from its own callback: the prompt is asynchronous, so the honest answer at the moment of the veto is
     * "not now", not "yes eventually".</p>
     */
    private java.util.function.Predicate<DockPanelRef> closeGuard = panel -> true;

    /** @see #closeGuard */
    public DockArea setCloseGuard(@Nullable java.util.function.Predicate<DockPanelRef> guard) {
        this.closeGuard = guard == null ? panel -> true : guard;
        return this;
    }

    /**
     * Closes a panel, unless the guard refuses.
     *
     * <p>Every route a user can take goes through here — the Ctrl+W command and anything else that asks
     * the area to close something — which is what makes one guard enough.</p>
     */
    public void closePanel(DockPanelRef panel) {
        if (!closeGuard.test(panel)) return;
        closePanelDiscarding(panel);
    }

    /**
     * Closes a panel <b>without</b> asking the guard — for the guard's own "yes, close it" callback.
     *
     * <p>Named for what it does rather than for who calls it: anything reaching for this is discarding
     * whatever the guard was protecting, and that should be uncomfortable to type by accident.</p>
     */
    public void closePanelDiscarding(DockPanelRef panel) {
        captureDividerPositions();
        if (!layout.closePanel(panel)) return;
        requestRebuild();
        // ANNOUNCED, because until now closing a tab told nobody. Its document stayed open, its editor
        // stayed reachable, and anything it owned -- a preview pool, a renderer -- lived until the
        // process did. `Disposer` could not help, because the thing that knew the panel was gone had no
        // way to say so. This is that way.
        onDidClosePanel.emit(panel);
    }

    /**
     * A panel left the layout — by a close, not by a drag.
     *
     * <p>The seam that lets a document be released when its last tab goes. It is deliberately about the
     * <b>panel</b> rather than the document: the dock does not know what a document is, and the workbench
     * that does can decide whether this was the last tab showing it.</p>
     *
     * <p>Not fired by a drag between groups, which removes and re-adds the same panel: that is a move,
     * and disposing there would destroy the thing being dragged mid-gesture.</p>
     */
    public final Signal.Value<DockPanelRef> onDidClosePanel = new Signal.Value<>();

    /** Maximizes a group, or restores when it is already the maximized one. */
    public void toggleMaximize(DockLeaf leaf) {
        captureDividerPositions();
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
