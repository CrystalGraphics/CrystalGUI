package com.crystalgui.ui.elements.dock;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout tree, and every structural operation on it.
 *
 * <p><b>Pure data — there is no {@code UIElement} anywhere in this package's headless half.</b> The whole
 * of {@code text/cursor} earned its correctness this way: extracting the logic out of the widget made it
 * reachable without a {@code UIWindow}, fonts or a style engine, and exposed a real bug within minutes.
 * Split, join, collapse and serialise are exactly that kind of logic, so {@link DockArea} is a renderer
 * over this rather than the other way round.</p>
 *
 * <h3>Sizes are weights within a branch, not pixels</h3>
 *
 * <p>Ported from VS Code's {@code GridView} with one deliberate divergence. VS Code stores absolute pixel
 * sizes; our renderer is flex-based and {@code flex-grow} <em>is</em> a weight, so storing pixels would
 * mean converting to weights on every layout and back on every save — a lossy round trip that drifts.
 * Weights are relative within their parent, so a subtree keeps its proportions wherever it is moved.</p>
 *
 * <p>What the pixel model was really buying — "this sidebar is 240px regardless of the window" — is not
 * lost: it lives on {@link com.crystalgui.ui.elements.SplitView}'s per-pane minimum and maximum sizes,
 * which is where a constraint belongs. {@link DockLayoutCodec} still records the viewport a layout was
 * measured in, so absolute reconstruction stays possible.</p>
 *
 * <h3>The root is always a branch</h3>
 *
 * <p>Including when it holds a single leaf. VS Code's {@code _removeView} does the same, and it is what
 * lets every drop operation assume {@code target.parent() != null} instead of carrying a special case for
 * the one-pane layout — the case that is hit first and tested last.</p>
 */
public final class DockLayout {

    private DockBranch root;
    private DockOrientation rootOrientation;

    private DockLayout(DockBranch root, DockOrientation rootOrientation) {
        this.root = root;
        this.rootOrientation = rootOrientation;
    }

    /** A tree of one leaf, divided horizontally at the root. */
    public static DockLayout of(DockLeaf first) {
        DockBranch root = new DockBranch();
        root.addChild(first, 0);
        first.size(1f);
        return new DockLayout(root, DockOrientation.HORIZONTAL);
    }

    public static DockLayout of(DockBranch root, DockOrientation rootOrientation) {
        return new DockLayout(root, rootOrientation);
    }

    public DockBranch root() {
        return root;
    }

    public DockOrientation rootOrientation() {
        return rootOrientation;
    }

    public DockLayout rootOrientation(DockOrientation orientation) {
        this.rootOrientation = orientation;
        return this;
    }

    public List<DockLeaf> leaves() {
        return root.leaves();
    }

    /** The one leaf that cannot be closed, floated or absorbed, or {@code null} if none is marked. */
    public DockLeaf centralLeaf() {
        for (DockLeaf leaf : leaves()) {
            if (leaf.isCentral()) return leaf;
        }
        return null;
    }

    public DockLeaf maximizedLeaf() {
        for (DockLeaf leaf : leaves()) {
            if (leaf.isMaximized()) return leaf;
        }
        return null;
    }

    /** Exactly one leaf may be maximized; pass {@code null} to restore. */
    public DockLayout maximize(DockLeaf leaf) {
        for (DockLeaf other : leaves()) other.setMaximized(other == leaf);
        return this;
    }

    public DockLeaf leafContaining(DockPanelRef panel) {
        for (DockLeaf leaf : leaves()) {
            if (leaf.indexOf(panel) >= 0) return leaf;
        }
        return null;
    }

    // ── Dropping ────────────────────────────────────────────────────────────────────────────────

    /**
     * Performs a drop, and returns the leaf the dropped content ended up in.
     *
     * <p>{@code inserted} may be a leaf or a whole branch — tearing a group out and dropping it back is the
     * same operation as dropping a single panel, which is what keeps the two hardest-looking gestures from
     * needing separate code (see {@link #tearOut}).</p>
     *
     * @throws IllegalArgumentException if the target is inside the node being dropped. Dropping a group
     *         into itself would detach the tree from its own root; refusing it here rather than at the
     *         widget layer means the overlay can ask the same question before it is even drawn.
     */
    public DockLeaf drop(DockLeaf target, DockDropZone zone, DockNode inserted) {
        return drop(target, zone, inserted, -1);
    }

    /**
     * As {@link #drop(DockLeaf, DockDropZone, DockNode)}, but a {@link DockDropZone#MERGE} lands at
     * {@code mergeIndex} in the strip rather than at the end.
     *
     * <p>What separates "drop into this group" from "drop between these two tabs". Both are a merge — the
     * index is the only difference, so making it a parameter rather than a second zone keeps the drop-zone
     * map answering one question.</p>
     *
     * @param mergeIndex position in the target strip, or negative to append
     */
    public DockLeaf drop(DockLeaf target, DockDropZone zone, DockNode inserted, int mergeIndex) {
        if (target.isUnder(inserted)) {
            throw new IllegalArgumentException("cannot drop a node into itself or its own descendants");
        }
        if (inserted.parent != null) {
            throw new IllegalArgumentException("the dropped node must be detached first");
        }

        if (zone == DockDropZone.MERGE) {
            int at = mergeIndex;
            for (DockLeaf leaf : inserted.leaves()) {
                for (DockPanelRef panel : leaf.panels()) {
                    if (at < 0) {
                        target.add(panel);
                    } else {
                        target.add(panel, at++);   // several panels keep their order relative to each other
                    }
                }
            }
            return target;
        }

        DockBranch parent = target.parent;
        if (parent == null) throw new IllegalStateException("the root is always a branch — target has no parent");

        if (zone.axis() == parent.orientation(rootOrientation)) {
            // Same axis as the branch already divides: a plain sibling insert. The two split the target's
            // weight, so every other child of the branch keeps its proportion exactly.
            int index = parent.indexOf(target) + (zone.after() ? 1 : 0);
            float half = target.size() / 2f;
            target.size(half);
            inserted.size(half);
            parent.addChild(inserted, index);
        } else {
            // The other axis: wrap the target in a new branch. The wrapper takes the target's slot and so
            // its depth, which means its children are automatically on the orthogonal axis — the axis we
            // wanted. There are only two axes, so this case is always exactly right; nothing computes it.
            DockBranch wrapper = new DockBranch();
            int index = parent.indexOf(target);
            parent.replaceChild(index, wrapper);
            wrapper.addChild(target, 0);
            wrapper.addChild(inserted, zone.after() ? 1 : 0);
            target.size(1f);
            inserted.size(1f);
        }
        return firstLeafOf(inserted);
    }

    /**
     * Drops against the outer edge of the whole layout — a full-height column beside everything, or a
     * full-width row above it.
     *
     * <p>Visual Studio's compass has these and VS Code does not: VS Code reaches the frame edge through
     * whichever group happens to be there, which cannot express <em>"beside all four of these rows"</em>.</p>
     */
    /**
     * Where {@code node} sits, as a {@link DockPath} from the root.
     *
     * <p>Null when the node is not in this tree — which includes a node that has just been detached, so a
     * caller wanting to remember a position must ask <b>before</b> removing it.</p>
     */
    @Nullable
    public DockPath pathOf(DockNode node) {
        if (node == root) return DockPath.ROOT;
        List<Integer> reversed = new ArrayList<>();
        DockNode current = node;
        while (current != null && current != root) {
            DockBranch parent = current.parent();
            if (parent == null) return null;      // detached, or belongs to another tree
            int index = parent.indexOf(current);
            if (index < 0) return null;
            reversed.add(index);
            current = parent;
        }
        if (current != root) return null;
        int[] indices = new int[reversed.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = reversed.get(indices.length - 1 - i);
        return DockPath.of(indices);
    }

    /** The node a path names, or null when the tree no longer has that shape. */
    @Nullable
    public DockNode nodeAt(DockPath path) {
        if (path == null) return null;
        DockNode current = root;
        for (int step = 0; step < path.depth(); step++) {
            if (!(current instanceof DockBranch branch)) return null;
            int index = path.index(step);
            if (index >= branch.childCount()) return null;
            current = branch.child(index);
        }
        return current;
    }

    /**
     * Puts {@code inserted} back at a remembered position.
     *
     * <h3>Why this exists beside {@link #dropOnOuterEdge}</h3>
     *
     * <p>That one answers "against which wall", which is the only question a panel on the outside of the
     * tree has. A panel <em>inside</em> the tree — a tool window beside the editor area rather than
     * spanning the window — has no wall, and reopening it against one moves it. This restores the actual
     * position instead.</p>
     *
     * <p><b>Best effort, and it says so in the return value.</b> The tree changes shape while a panel is
     * closed, so the remembered path may name a leaf, a shorter branch, or nothing at all. Each of those
     * is ordinary rather than exceptional, so this reports failure and leaves the tree untouched for the
     * caller to fall back — silently inserting "somewhere near" would be the guessing this exists to
     * avoid.</p>
     *
     * @param parent where the node's own parent branch was
     * @param index  which child it was; clamped, since siblings may have come or gone
     * @return whether it was inserted
     */
    public boolean insertAt(DockPath parent, int index, DockNode inserted) {
        if (inserted == null || parent == null) return false;
        if (inserted.parent != null) {
            throw new IllegalArgumentException("the inserted node must be detached first");
        }
        if (!(nodeAt(parent) instanceof DockBranch branch)) return false;
        branch.addChild(inserted, Math.max(0, Math.min(index, branch.childCount())));
        normalise();
        return true;
    }

    public DockLeaf dropOnOuterEdge(DockDropZone zone, DockNode inserted) {
        if (!zone.isSplit()) throw new IllegalArgumentException("an outer-edge drop must be a split");
        if (inserted.parent != null) throw new IllegalArgumentException("the dropped node must be detached first");

        if (zone.axis() != root.orientation(rootOrientation)) {
            // The root divides the wrong axis. Push the whole tree down a level into a new root that
            // divides the one we want; every existing node gains a depth, so every axis flips with it —
            // which is why the root orientation flips too and the picture does not move.
            DockBranch newRoot = new DockBranch();
            DockBranch old = root;
            List<DockNode> moved = new ArrayList<>(old.children());
            for (int i = old.childCount() - 1; i >= 0; i--) old.removeChild(i);
            DockBranch carrier = new DockBranch();
            for (int i = 0; i < moved.size(); i++) carrier.addChild(moved.get(i), i);
            carrier.size(1f);
            newRoot.addChild(carrier, 0);
            root = newRoot;
            rootOrientation = rootOrientation.orthogonal();
            // carrier now sits at depth 1 and divides what the old root divided: axis(1) == orth(new root)
            // == the old root orientation. The subtree is untouched and looks identical.
        }
        inserted.size(1f);
        root.addChild(inserted, zone.after() ? root.childCount() : 0);
        // The carrier can be left holding a single node -- the common case is a drag that emptied the tree
        // down to one pane on its way here, since detaching the source collapses behind it. normalise
        // knows how to dissolve that correctly, including the splice a surviving branch needs.
        normalise();
        return firstLeafOf(inserted);
    }

    private static DockLeaf firstLeafOf(DockNode node) {
        List<DockLeaf> leaves = node.leaves();
        return leaves.isEmpty() ? null : leaves.get(0);
    }

    // ── Removal and collapse ────────────────────────────────────────────────────────────────────

    /**
     * Detaches a node and collapses whatever the removal left behind.
     *
     * @return the detached node, or {@code null} if it could not be removed (it is the root, or a central
     *         leaf, which by definition cannot leave the tree)
     */
    public DockNode remove(DockNode node) {
        if (node == root) return null;
        if (node instanceof DockLeaf && ((DockLeaf) node).isCentral()) return null;

        DockBranch parent = node.parent;
        if (parent == null) return null;

        int index = parent.indexOf(node);
        float freed = node.size();
        parent.removeChild(index);
        redistribute(parent, freed);
        collapse(parent);
        return node;
    }

    /**
     * Detaches a node so it can be dropped somewhere else — the first half of a move.
     *
     * <p>Deliberately the same call as {@link #remove}: "tear a tab out into a floating window" and
     * "close a pane" differ only in what happens to the node afterwards, and a system where they are two
     * code paths is a system where one of them forgets to collapse.</p>
     */
    public DockNode tearOut(DockNode node) {
        return remove(node);
    }

    /** Gives a removed child's weight back to its siblings, in proportion to what they already had. */
    private static void redistribute(DockBranch branch, float freed) {
        if (branch.childCount() == 0 || freed <= 0f) return;
        float total = 0f;
        for (DockNode child : branch.children()) total += child.size();
        if (total <= 0f) {
            branch.distribute(1f);
            return;
        }
        for (DockNode child : branch.children()) {
            child.size(child.size() + freed * (child.size() / total));
        }
    }

    /**
     * Dissolves a branch left holding a single child.
     *
     * <p>Ported from {@code gridview.ts}'s {@code _removeView}. The subtle half is that a surviving
     * <em>branch</em> has its children spliced into the grandparent rather than being moved there itself:
     * a branch at depth {@code D+1} divides {@code axis(D+1)}, the grandparent at {@code D-1} divides
     * {@code axis(D-1)}, and those are the same axis — so its children slot straight in, while the branch
     * itself would divide the wrong one.</p>
     *
     * <p><b>VS Code additionally has to rebuild a surviving leaf with {@code orthogonal(orientation)}</b>,
     * because its {@code LeafNode} stores an orientation and has just moved up a level. Here orientation is
     * derived from depth, so moving a node re-derives it and there is nothing to forget. That trap is real
     * and it is dissolved by the representation, not by remembering to handle it.</p>
     */
    private void collapse(DockBranch branch) {
        if (branch.childCount() != 1) return;

        if (branch == root) {
            DockNode sole = root.child(0);
            if (sole.isLeaf()) return; // a root branch holding one leaf is the resting state, not a defect

            // Promote the sole branch to root. Everything under it loses a level, so every axis flips;
            // flipping the root orientation with it is what keeps the picture identical. VS Code gets this
            // for free because its root orientation is simply `this.root.orientation`.
            root.removeChild(0);
            root = (DockBranch) sole;
            root.size(1f);
            rootOrientation = rootOrientation.orthogonal();
            collapse(root);
            return;
        }

        dissolveOneChildBranch(branch.parent, branch.parent.indexOf(branch));
    }

    /**
     * Replaces the one-child branch at {@code parent.child(index)} with what it was holding.
     *
     * <p>Shared by {@link #collapse} and {@link #normalise} deliberately. Two copies of a splice this
     * subtle is exactly the shape {@code gui_curve.shader} is a standing monument to — the cap logic was
     * wrong three times, every version rendered something plausible, and two copies meant the fourth fix
     * landed in one file.</p>
     */
    private static void dissolveOneChildBranch(DockBranch parent, int index) {
        DockBranch branch = (DockBranch) parent.child(index);
        float branchWeight = branch.size();

        DockNode sole = branch.removeChild(0);
        parent.removeChild(index);

        if (sole.isLeaf()) {
            sole.size(branchWeight);
            parent.addChild(sole, index);
            return;
        }

        DockBranch soleBranch = (DockBranch) sole;
        List<DockNode> grandchildren = new ArrayList<>(soleBranch.children());
        float total = 0f;
        for (DockNode child : grandchildren) total += child.size();
        float scale = total > 0f ? branchWeight / total : branchWeight / Math.max(1, grandchildren.size());

        for (int i = grandchildren.size() - 1; i >= 0; i--) soleBranch.removeChild(i);
        for (int i = 0; i < grandchildren.size(); i++) {
            DockNode child = grandchildren.get(i);
            child.size(total > 0f ? child.size() * scale : scale);
            parent.addChild(child, index + i);
        }
    }

    /**
     * Repairs a tree that was assembled rather than built up by operations — i.e. one that came out of
     * {@link DockLayoutCodec} after unknown panel types were dropped from it.
     *
     * <p>Every operation in this class maintains the invariants as it goes, so nothing else needs this.
     * A decoded tree does: dropping panels can empty a leaf, emptying leaves can empty a branch, and
     * removing branches can leave one-child branches anywhere. Doing it in one bottom-up pass rather than
     * at each removal is deliberate — the removals are not user actions, so there is nothing to keep
     * proportional between them.</p>
     */
    public DockLayout normalise() {
        normaliseBranch(root);
        while (root.childCount() == 1 && !root.child(0).isLeaf()) {
            DockNode sole = root.child(0);
            root.removeChild(0);
            root = (DockBranch) sole;
            root.size(1f);
            rootOrientation = rootOrientation.orthogonal();
        }
        return this;
    }

    private void normaliseBranch(DockBranch branch) {
        for (int i = branch.childCount() - 1; i >= 0; i--) {
            DockNode child = branch.child(i);
            if (child.isLeaf()) {
                DockLeaf leaf = (DockLeaf) child;
                if (leaf.isEmpty() && !leaf.isCentral()) branch.removeChild(i);
                continue;
            }
            DockBranch sub = (DockBranch) child;
            normaliseBranch(sub);
            if (sub.childCount() == 0) {
                branch.removeChild(i);
            } else if (sub.childCount() == 1) {
                dissolveOneChildBranch(branch, i);
            }
        }
    }

    // ── Panels ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Closes a panel, and the leaf with it if that was the last one.
     *
     * <p>A central leaf is kept even when empty — it is the guarantee that the main work area still
     * exists, and an empty one shows a watermark rather than disappearing.</p>
     */
    public boolean closePanel(DockPanelRef panel) {
        DockLeaf leaf = leafContaining(panel);
        if (leaf == null) return false;
        leaf.remove(panel);
        if (leaf.isEmpty() && !leaf.isCentral()) remove(leaf);
        return true;
    }

    /** Moves a panel into another leaf's strip, collapsing the source leaf if it empties. */
    public boolean movePanel(DockPanelRef panel, DockLeaf target, int index) {
        DockLeaf source = leafContaining(panel);
        if (source == null) return false;
        if (source == target) return source.move(source.indexOf(panel), index);
        source.remove(panel);
        target.add(panel, index);
        if (source.isEmpty() && !source.isCentral()) remove(source);
        return true;
    }

    // ── Invariants ──────────────────────────────────────────────────────────────────────────────

    /**
     * Throws if the tree has reached a state the operations above are supposed to make unreachable.
     *
     * <p>Public because it is the tests' whole leverage: every operation can assert the shape it produced
     * rather than the one it intended, which is the difference between testing an implementation and
     * testing a contract.</p>
     */
    public void checkInvariants() {
        checkNode(root, true);
        int central = 0;
        int maximized = 0;
        for (DockLeaf leaf : leaves()) {
            if (leaf.isCentral()) central++;
            if (leaf.isMaximized()) maximized++;
        }
        if (central > 1) throw new IllegalStateException("more than one central leaf: " + central);
        if (maximized > 1) throw new IllegalStateException("more than one maximized leaf: " + maximized);
    }

    private static void checkNode(DockNode node, boolean isRoot) {
        if (node.isLeaf()) {
            DockLeaf leaf = (DockLeaf) node;
            if (leaf.isEmpty() && !leaf.isCentral()) {
                throw new IllegalStateException("an empty non-central leaf survived: " + leaf.path());
            }
            return;
        }
        DockBranch branch = (DockBranch) node;
        if (branch.childCount() == 0) {
            throw new IllegalStateException("empty branch at " + branch.path());
        }
        if (!isRoot && branch.childCount() < 2) {
            throw new IllegalStateException("un-collapsed one-child branch at " + branch.path());
        }
        for (DockNode child : branch.children()) {
            if (child.parent() != branch) throw new IllegalStateException("broken parent link at " + child);
            if (child.size() <= 0f) throw new IllegalStateException("non-positive weight at " + child.path());
            checkNode(child, false);
        }
    }

    @Override
    public String toString() {
        return rootOrientation + " " + root;
    }
}
