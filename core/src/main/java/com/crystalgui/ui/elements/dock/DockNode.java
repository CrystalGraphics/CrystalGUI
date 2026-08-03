package com.crystalgui.ui.elements.dock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in the layout tree: either a {@link DockBranch} that divides an axis, or a {@link DockLeaf}
 * that holds a tab strip of panels.
 *
 * <p>Six independent docking systems — VS Code, IntelliJ, Dear ImGui, Qt, Golden Layout and wxAUI —
 * converge on exactly this shape, so it is a port rather than a design. Ported from VS Code's
 * {@code base/browser/ui/grid/gridview.ts} ({@code BranchNode}/{@code LeafNode}, MIT).</p>
 *
 * <h3>Orientation is derived from depth, never stored</h3>
 *
 * <p>VS Code's {@code getLocationOrientation} is one line, and it is the load-bearing one:</p>
 *
 * <pre>return location.length % 2 === 0 ? orthogonal(rootOrientation) : rootOrientation;</pre>
 *
 * <p>A branch at even depth divides one axis, at odd depth the other, all the way down. Storing an
 * orientation per branch would permit a tree that cannot be drawn — two nested branches both splitting
 * horizontally, which is not a nested split at all but one branch with more children. Deriving it makes
 * that state unrepresentable.</p>
 *
 * <p>It also dissolves a trap that is real in VS Code. There, collapsing a one-child branch has to rebuild
 * the surviving leaf with {@code orthogonal(sibling.orientation)}, because a {@code LeafNode} carries its
 * own orientation and has just moved up a level. Four lines, invisible in every screenshot, and the
 * symptom is a pane resizing along the wrong axis after an <em>unrelated</em> close. Here, moving a node
 * changes its depth and the orientation re-derives itself — there is nothing to forget.</p>
 */
public abstract class DockNode {

    DockBranch parent;

    /**
     * Extent along the parent's axis, in pixels.
     *
     * <p>Absolute rather than proportional, and saved next to the viewport it was measured in — see
     * {@link DockLayoutCodec}. Golden Layout stores percentages and has spent a decade patching what that
     * does to minimum sizes: a percentage cannot express "this sidebar is 240px regardless".</p>
     */
    float size;

    DockNode(float size) {
        this.size = size;
    }

    public final DockBranch parent() {
        return parent;
    }

    public final float size() {
        return size;
    }

    public final void size(float size) {
        this.size = Math.max(0f, size);
    }

    public abstract boolean isLeaf();

    /** Root is 0. */
    public final int depth() {
        int depth = 0;
        for (DockNode node = parent; node != null; node = node.parent) depth++;
        return depth;
    }

    /**
     * Child indices from the root down to this node. Empty for the root.
     *
     * <p>VS Code's {@code GridLocation}, and the same thing it is used for: identifying a node without
     * holding a reference to it, which is what makes a drop operation describable before it is performed.</p>
     */
    public final List<Integer> path() {
        List<Integer> reversed = new ArrayList<>();
        for (DockNode node = this; node.parent != null; node = node.parent) {
            reversed.add(node.parent.indexOf(node));
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /** Whether {@code ancestor} is this node or one of its ancestors. */
    public final boolean isUnder(DockNode ancestor) {
        for (DockNode node = this; node != null; node = node.parent) {
            if (node == ancestor) return true;
        }
        return false;
    }

    /** Every leaf at or below this node, left to right in the order they are laid out. */
    public final List<DockLeaf> leaves() {
        List<DockLeaf> out = new ArrayList<>();
        collectLeaves(out);
        return out;
    }

    abstract void collectLeaves(List<DockLeaf> out);
}
