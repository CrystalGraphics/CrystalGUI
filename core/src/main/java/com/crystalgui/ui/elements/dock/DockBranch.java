package com.crystalgui.ui.elements.dock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node that divides one axis among n children.
 *
 * <p><b>n children, not two.</b> Nesting binary splits produces the same picture and the wrong feel:
 * with three panes as {@code (A | (B | C))}, dragging the A/B divider resizes A against the <em>whole</em>
 * {@code (B|C)} group and splits the change proportionally between B and C. Every IDE moves only A and B.
 * No screenshot shows the difference, which is what makes it worth stating.</p>
 *
 * @see DockNode for why the orientation is derived from depth rather than stored here
 */
public final class DockBranch extends DockNode {

    private final List<DockNode> children = new ArrayList<>();

    public DockBranch() {
        this(0f);
    }

    public DockBranch(float size) {
        super(size);
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    public List<DockNode> children() {
        return Collections.unmodifiableList(children);
    }

    public int childCount() {
        return children.size();
    }

    public DockNode child(int index) {
        return children.get(index);
    }

    public int indexOf(DockNode child) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == child) return i;
        }
        return -1;
    }

    /** The axis this branch divides, given the tree's root orientation. Derived — see {@link DockNode}. */
    public DockOrientation orientation(DockOrientation rootOrientation) {
        return depth() % 2 == 0 ? rootOrientation : rootOrientation.orthogonal();
    }

    // ── Mutation ────────────────────────────────────────────────────────────────────────────────
    // Package-private: every structural change goes through DockLayout, which is the only place that
    // knows how to collapse afterwards. A caller that could add a child directly could also leave a
    // one-child branch behind, which is the state the whole collapse machinery exists to prevent.

    void addChild(DockNode child, int index) {
        if (child.parent != null) throw new IllegalStateException("node is already in a tree");
        children.add(index, child);
        child.parent = this;
    }

    DockNode removeChild(int index) {
        DockNode removed = children.remove(index);
        removed.parent = null;
        return removed;
    }

    void replaceChild(int index, DockNode replacement) {
        if (replacement.parent != null) throw new IllegalStateException("node is already in a tree");
        DockNode previous = children.set(index, replacement);
        previous.parent = null;
        replacement.parent = this;
        replacement.size = previous.size;
    }

    /** Gives every child an equal share of {@code total}. */
    public void distribute(float total) {
        if (children.isEmpty()) return;
        float each = total / children.size();
        for (DockNode child : children) child.size = each;
    }

    @Override
    void collectLeaves(List<DockLeaf> out) {
        for (DockNode child : children) child.collectLeaves(out);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("branch[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(children.get(i));
        }
        return sb.append(']').toString();
    }
}
