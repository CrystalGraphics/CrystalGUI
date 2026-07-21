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

    public static UIElement firstFocusableIn(UIElement subtreeRoot) {
        if (subtreeRoot.focusable()) return subtreeRoot;
        for (UIElement child : subtreeRoot.getChildren()) {
            if (child.getRuntimeCache().hasFocusableDescendant.get()) return firstFocusableIn(child);
        }
        return null;
    }

    public static UIElement lastFocusableIn(UIElement subtreeRoot) {
        List<UIElement> children = subtreeRoot.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).getRuntimeCache().hasFocusableDescendant.get()) return lastFocusableIn(children.get(i));
        }
        return subtreeRoot.focusable() ? subtreeRoot : null;
    }

    public static UIElement previousFocusable(UIElement current) {
        UIElement node = current;
        while (node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() - 1; i >= 0; i--) {
                if (siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) return lastFocusableIn(siblings.get(i));
            }
            if (node.getParent().focusable()) return node.getParent();
            node = node.getParent();
        }
        return null;
    }

    public static UIElement nextFocusable(UIElement current) {
        for (UIElement child : current.getChildren()) {
            if (child.getRuntimeCache().hasFocusableDescendant.get()) return firstFocusableIn(child);
        }
        UIElement node = current;
        while (node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() + 1; i < siblings.size(); i++) {
                if (siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) return firstFocusableIn(siblings.get(i));
            }
            node = node.getParent();
        }
        return null;
    }
}