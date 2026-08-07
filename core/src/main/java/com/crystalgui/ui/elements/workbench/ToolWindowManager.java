package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBranch;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockNode;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockOrientation;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockPath;
import com.crystalgui.ui.elements.dock.DockPlacement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool windows: which exist, where each belongs, and whether it is on screen — IntelliJ's
 * {@code ToolWindowManager}.
 *
 * <h3>Why this is its own object</h3>
 *
 * <p>It was 220 lines inside {@code Workbench}, which {@code plan.md} §1.1 calls "five services in a
 * trench coat" and §7 stage 6 scheduled for extraction at <b>High</b> risk. Stage 6 was skipped, correctly
 * at the time: the complaint behind it was that callers had to reach the whole workbench, and that was
 * answered by decoupling the callers instead.</p>
 *
 * <p>The Parts model un-skips it. Parts is a rewrite of <em>precisely this surface</em> — regions,
 * visibility, sizes — and doing that inside a 1300-line class would re-entangle what steps 1–10
 * separated. This is the one extraction Parts needs, deliberately not the four-way split §5.3 imagined
 * and which never happened.</p>
 *
 * <h3>What is about to change here, and what is not</h3>
 *
 * <p>Everything below assumes a tool window <b>lives in the dock tree</b>. That assumption is what the
 * four-tier {@link #showPanel} heuristic exists to survive, and it is what §23 F2 removes: once a view
 * belongs to a <em>region</em>, restoration is a lookup and tiers 1–3 have nothing to be about. The
 * behaviour those tiers deliver — hide then show is exact — must survive that change, which is what its
 * tests are for.</p>
 *
 * <p>The <em>public</em> surface is expected to survive: {@code isPanelOpen} / {@code showPanel} /
 * {@code hidePanel} / {@code togglePanel} are what a command and an activity bar button call, and none of
 * them says anything about a tree.</p>
 */
public final class ToolWindowManager {

    /**
     * How this manager opens a panel it has decided the position of.
     *
     * <p>The one call that reaches back out, and an explicit seam rather than a reference to the whole
     * workbench: this needs exactly one thing from its owner — "put this panel in that leaf" — and taking
     * the owner instead would make the dependency unbounded and circular.</p>
     */
    @FunctionalInterface
    public interface PanelOpener {
        DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options);
    }

    private final DockArea dock;
    private final DockPanelRegistry<UIElement> registry;
    private final PanelOpener opener;

    public ToolWindowManager(DockArea dock, DockPanelRegistry<UIElement> registry, PanelOpener opener) {
        this.dock = dock;
        this.registry = registry;
        this.opener = opener;
    }

    /** Whether a singleton panel type is currently anywhere in the layout. */
    public boolean isPanelOpen(String typeId) {
        return dock.layout().leafContaining(new DockPanelRef(typeId)) != null;
    }

    /**
     * Shows a singleton panel, or hides it if it is already showing — what an activity bar button does.
     *
     * <h3>Toggle, because that is what both editors do</h3>
     *
     * <p>Clicking the visible tool window's stripe button <b>hides</b> it, in IntelliJ and in VS Code
     * alike ({@code hideActivePaneComposite}). Open-only would leave the rail able to fill the screen
     * with panels and unable to clear one, which is how a bar of buttons stops being a bar of toggles and
     * starts needing a close affordance on every panel.</p>
     *
     * <p>Reopens against the type's {@linkplain DockPanelDescriptor#anchor anchor} rather than into the
     * central strip. {@link #openPanel} is right for a document — a thing you opened, which belongs where
     * you are working — and wrong for a tool window, which has a home. Dropping Project into the middle
     * would bury the file you were reading behind the file tree.</p>
     *
     * @return whether the panel is open <em>after</em> this call
     */
    public boolean togglePanel(String typeId) {
        return isPanelOpen(typeId) ? hidePanel(typeId) : showPanel(typeId);
    }

    /**
     * Hides a tool window, recording where it was so that showing it again is exact.
     *
     * <p>Everything is read <b>before</b> the close, because closing collapses the branch that held the
     * leaf: the weight goes with it, the path stops resolving, and the strip-mates are no longer
     * reachable. Capturing afterwards would record the tree the close produced rather than the one the
     * user arranged.</p>
     *
     * @return false, always -- the panel is closed after this
     */
    public boolean hidePanel(String typeId) {
        DockPanelRef ref = new DockPanelRef(typeId);
        DockLeaf showing = dock.layout().leafContaining(ref);
        if (showing == null) return false;

        List<DockPanelRef> neighbours = new ArrayList<>(showing.panels());
        neighbours.remove(ref);
        DockPath parent = showing.parent() == null ? null : dock.layout().pathOf(showing.parent());
        int index = showing.parent() == null ? -1 : showing.parent().indexOf(showing);

        ToolWindowState state = placementOf(typeId)
                .withVisible(false)
                .withWeight(showing.size())
                .withGroupedWith(neighbours)
                .withActive(ref.equals(showing.activePanel()))
                .withPlacement(parent, index);
        DockDropZone edge = outerEdgeOf(showing);
        if (edge != null) state = state.withAnchor(edge);
        state = withRelativePosition(state, showing);
        toolWindows.put(state);

        dock.layout().closePanel(ref);
        // requestRebuild, NOT syncGroups. Both showing and hiding change the SHAPE of the tree -- a close
        // removes a leaf and normalise() may collapse the branch, a show inserts one -- and syncGroups only
        // reconciles tabs inside groups that already exist. The asymmetry is what made the button look like
        // it "only closes": closing emptied the group so the pane visibly went away, while opening added a
        // leaf no SplitView had been built for and nothing appeared.
        dock.requestRebuild();
        return false;
    }

    /**
     * Shows a tool window at the most specific remembered position that still exists.
     *
     * <h3>Three tiers, most specific first</h3>
     *
     * <ol>
     *   <li><b>A strip-mate</b> -- rejoin the tab strip it shared. First because it is the only tier that
     *       names a <em>leaf</em>; the rest name a position between leaves, so letting one of them win for
     *       a panel that was a tab reopens it beside its own strip rather than in it.</li>
     *   <li><b>The structural path</b> -- the exact branch and index its leaf occupied. Precise, and it
     *       only survives while that branch does.</li>
     *   <li><b>A surviving neighbour</b> -- replay the drop that put it beside that panel. This is what
     *       carries the common case: hiding a panel that was alone in its pane collapses the branch tier 2
     *       names, and the neighbour is still on screen.</li>
     *   <li><b>The anchor</b> -- which wall. The answer for a panel that has never been open, and the
     *       backstop when everything else has moved.</li>
     * </ol>
     *
     * <p>The order is load-bearing in both directions. An anchor always succeeds, so checking it early
     * means the specific tiers are never consulted and every nested tool window drifts to a wall. And the
     * positional tiers always succeed for a panel that was a <em>tab</em>, so checking those first splits
     * a strip that the user had deliberately grouped.</p>
     *
     * @return true, always -- the panel is open after this
     */
    public boolean showPanel(String typeId) {
        DockPanelRef ref = new DockPanelRef(typeId);
        if (dock.layout().leafContaining(ref) != null) return true;
        ToolWindowState state = placementOf(typeId);

        DockLeaf placed = null;
        // 1. THE STRIP IT WAS A TAB IN. First because it is the only tier that names a LEAF; every other
        //    one names a position between leaves, so honouring one of those for a panel that was a tab
        //    reopens it beside its own strip instead of in it.
        for (DockPanelRef mate : state.groupedWith()) {
            DockLeaf strip = dock.layout().leafContaining(mate);
            if (strip == null) continue;
            opener.open(DockInput.of(ref), DockPlacement.leaf(strip), DockOpenOptions.INACTIVE);
            placed = dock.layout().leafContaining(ref);
            break;
        }
        // 2. THE EXACT BRANCH AND INDEX, when that branch is still there.
        if (placed == null && state.path() != null) {
            DockLeaf candidate = new DockLeaf(ref);
            candidate.size(state.weight());
            if (dock.layout().insertAt(state.path(), state.indexInParent(), candidate)) {
                placed = candidate;
            }
        }
        // 3. BESIDE A SURVIVING NEIGHBOUR, replaying the drop that produced the arrangement. This is what
        //    carries the common case, where hiding collapsed the branch tier 2 named.
        if (placed == null && state.relativeTo() != null) {
            DockLeaf beside = dock.layout().leafContaining(state.relativeTo());
            if (beside != null) {
                DockLeaf candidate = new DockLeaf(ref);
                dock.layout().drop(beside, state.relativeZone(), candidate);
                candidate.size(state.weight());
                placed = candidate;
            }
        }
        // 4. A WALL.
        if (placed == null) {
            DockLeaf opened = new DockLeaf(ref);
            dock.layout().dropOnOuterEdge(state.anchor(), opened);
            // AFTER the drop, never before: dropOnOuterEdge assigns size(1f) itself, so a weight set on
            // the way in is overwritten -- and a weight of 1 against siblings summing to 1 is what made a
            // reopened Project take half the window.
            opened.size(state.weight());
            placed = opened;
        }

        // BRING IT TO THE FRONT wherever it landed. openPanelWith deliberately restores the previous
        // selection -- right for its original caller, which opens the inspector beside the source without
        // stealing the source's tab, and wrong here: this panel is open because someone pressed its button,
        // and one that joins a strip behind another tab has, from the user's side, not opened at all.
        if (placed != null) placed.activate(ref);
        toolWindows.put(state.withVisible(true));
        dock.requestRebuild();
        if (placed != null) dock.setActiveGroup(dock.groupFor(placed));
        return true;
    }

    /**
     * Every tool window's placement, open or closed -- the model {@link WorkbenchSession} persists.
     *
     * <p>This is the whole of what replaced three ad-hoc maps of remembered fragments. See
     * {@link ToolWindowLayout} for why both editors keep placement <em>beside</em> the layout rather than
     * deriving it from one.</p>
     */
    public ToolWindowLayout toolWindows() {
        return toolWindows;
    }

    private final ToolWindowLayout toolWindows = new ToolWindowLayout();

    /**
     * Records the panel's position <b>relative to a neighbour</b> — the tier that survives a collapse.
     *
     * <p>{@link ToolWindowState#path()} names a branch, and hiding a panel usually destroys that branch:
     * a leaf alone with one sibling leaves the sibling behind, and {@code normalise()} correctly dissolves
     * the now-pointless branch. So the most common arrangement of all — a tool window in a pane of its own
     * — is precisely the one whose path stops resolving the moment it is hidden. A neighbouring
     * <em>panel</em> is still on screen and still findable, so "to the right of that one" keeps working.</p>
     *
     * <p>The neighbour is taken from the adjacent child of the same branch, and the zone from which side
     * it is on and which axis the branch divides — so reopening replays the very drop that produced the
     * arrangement.</p>
     */
    private ToolWindowState withRelativePosition(ToolWindowState state, DockLeaf showing) {
        DockBranch parent = showing.parent();
        if (parent == null || parent.childCount() < 2) return state;
        int mine = parent.indexOf(showing);
        int besideIndex = mine > 0 ? mine - 1 : mine + 1;
        if (besideIndex < 0 || besideIndex >= parent.childCount()) return state;

        List<DockLeaf> leaves = parent.child(besideIndex).leaves();
        if (leaves.isEmpty() || leaves.get(0).panels().isEmpty()) return state;
        DockPanelRef neighbour = leaves.get(0).panel(0);

        boolean after = mine > besideIndex;
        boolean horizontal =
                parent.orientation(dock.layout().rootOrientation()) == DockOrientation.HORIZONTAL;
        DockDropZone zone = horizontal
                ? (after ? DockDropZone.SPLIT_RIGHT : DockDropZone.SPLIT_LEFT)
                : (after ? DockDropZone.SPLIT_DOWN : DockDropZone.SPLIT_UP);
        return state.withRelativeTo(neighbour, zone);
    }

    /** This type's placement, seeded from its descriptor the first time it is asked for. */
    private ToolWindowState placementOf(String typeId) {
        DockPanelDescriptor descriptor = registry.descriptor(typeId);
        return toolWindows.getOrCreate(typeId,
                descriptor != null ? descriptor.anchor() : DockDropZone.SPLIT_LEFT);
    }

    /**
     * Which outer edge a leaf sits against, or null when it is not against one.
     *
     * <p>Read off the <b>top-level</b> ancestor: whichever child of the root the leaf descends from, and
     * whether that child is first or last. The root's orientation says which axis that is, so the answer
     * round-trips exactly through {@link DockLayout#dropOnOuterEdge}, which inverts the same rule.</p>
     *
     * <p><b>Null for anything nested</b>, and that is the honest answer rather than a gap -- a panel
     * between two others is not on an edge, and naming the nearest one would move it on reopen. Since the
     * structural path handles exactly that case, this is now the backstop it should always have been
     * rather than the whole answer it was briefly asked to be.</p>
     */
    @Nullable
    private DockDropZone outerEdgeOf(DockLeaf leaf) {
        DockNode node = leaf;
        while (node.parent() != null && node.parent() != dock.layout().root()) node = node.parent();
        DockBranch root = dock.layout().root();
        if (node.parent() != root) return null;
        int index = root.children().indexOf(node);
        if (index != 0 && index != root.childCount() - 1) return null;
        boolean after = index != 0;
        boolean horizontal = root.orientation(dock.layout().rootOrientation()) == DockOrientation.HORIZONTAL;
        if (horizontal) return after ? DockDropZone.SPLIT_RIGHT : DockDropZone.SPLIT_LEFT;
        return after ? DockDropZone.SPLIT_DOWN : DockDropZone.SPLIT_UP;
    }
}
