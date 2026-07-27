package com.crystalgui.ui.tree;

import com.crystalgui.ui.UIElement;

import java.util.List;

/**
 * Pure, stateless queries over the UIElement tree structure.
 * No method here mutates any element or knows about input/focus/style state —
 * everything is derivable purely from parent/child/sibling relationships.
 */
public final class UITreeTraversal {

    private UITreeTraversal() {} // non-instantiable

    // ── Ancestry ─────────────────────────────────────────────────────────

    public static UIElement commonAncestor(UIElement a, UIElement b) {
        if (a == null || b == null) return null;

        var depthA = a.getRuntimeCache().getDepth();
        var depthB = b.getRuntimeCache().getDepth();

        var nodeA = a;
        var nodeB = b;

        while (depthA > depthB) { nodeA = nodeA.getParent(); depthA--; }
        while (depthB > depthA) { nodeB = nodeB.getParent(); depthB--; }

        while (nodeA != nodeB) {
            nodeA = nodeA.getParent();
            nodeB = nodeB.getParent();
        }
        return nodeA;
    }

    /** Root-to-element chain, root first. Empty-safe: single-element list if element has no parent. */
    public static UIElement[] pathToRoot(UIElement element) {
        UIElement[] path = new UIElement[element.getRuntimeCache().getDepth()];
        int i = path.length - 1;
        for (var e = element; e != null; e = e.getParent()) {
            path[i--] = e;
        }
        return path;
    }

    // ── Focus order (tab traversal) ─────────────────────────────────────

    /*
     * On the `hasFocusableDescendant` guards below: that cache is only a fast-path filter, never a
     * commitment. Every recursive descent keeps searching the remaining siblings when it comes back
     * empty, rather than returning the null straight out.
     *
     * This matters because the flag can legitimately be stale — `focusable()` depends on
     * enabled/focus-policy/display, and not every path that changes those invalidates the chain
     * (display in particular is written straight through the Taffy bridge). Before this, a single
     * stale-true bit made a walk descend into a subtree with nothing focusable, return null, and
     * never try the next sibling — which killed the Tab key outright rather than skipping one
     * element. Correct behaviour with a fresh cache is unchanged; this only bounds the blast radius
     * when it isn't.
     */

    public static UIElement firstFocusableIn(UIElement subtreeRoot) {
        if (subtreeRoot.focusable()) return subtreeRoot;
        for (UIElement child : subtreeRoot.getChildren()) {
            if (!child.getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = firstFocusableIn(child);
            if (found != null) return found;
        }
        return null;
    }

    public static UIElement lastFocusableIn(UIElement subtreeRoot) {
        List<UIElement> children = subtreeRoot.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            if (!children.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = lastFocusableIn(children.get(i));
            if (found != null) return found;
        }
        return subtreeRoot.focusable() ? subtreeRoot : null;
    }

    public static UIElement previousFocusable(UIElement current) {
        UIElement node = current;
        while (node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() - 1; i >= 0; i--) {
                if (!siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
                UIElement found = lastFocusableIn(siblings.get(i));
                if (found != null) return found;
            }
            if (node.getParent().focusable()) return node.getParent();
            node = node.getParent();
        }
        return null;
    }

    public static UIElement nextFocusable(UIElement current) {
        for (UIElement child : current.getChildren()) {
            if (!child.getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = firstFocusableIn(child);
            if (found != null) return found;
        }
        UIElement node = current;
        while (node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() + 1; i < siblings.size(); i++) {
                if (!siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
                UIElement found = firstFocusableIn(siblings.get(i));
                if (found != null) return found;
            }
            node = node.getParent();
        }
        return null;
    }
}