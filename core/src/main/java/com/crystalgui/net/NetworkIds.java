package com.crystalgui.net;

import com.crystalgui.ui.UIElement;

/**
 * Assigns each element the number both sides will address it by.
 *
 * <h3>Derived, not transmitted</h3>
 * <p>Ids are the element's position in a depth-first walk, so the client computes exactly the same
 * ones from its rebuilt tree and nothing has to carry them. Two consequences follow, both good: the
 * description stays a pure description (so its content hash is about the UI, not about numbering),
 * and there is no id table to get out of step.</p>
 *
 * <p><b>Internal children are numbered too.</b> They exist identically on both sides — a Button's
 * label is built by the same constructor in both processes — so they are addressable, and skipping
 * them would make the two walks disagree the moment a composite appeared.</p>
 *
 * <h3>Where this can go wrong, and what catches it</h3>
 * <p>The walk assumes both sides build the same structure. If a client's widget constructor differs
 * from the server's — a version skew that the description itself cannot reveal, since internals are
 * never serialized — every id past that point would shift by one and updates would land on the wrong
 * elements. {@link #count} is sent at open for exactly that reason: a mismatch is refused rather
 * than silently misapplied.</p>
 */
public final class NetworkIds {

    private NetworkIds() {
    }

    /** Numbers {@code root}'s subtree from 0 in document order. Returns how many were assigned. */
    public static int assign(UIElement root) {
        return assign(root, 0);
    }

    private static int assign(UIElement element, int next) {
        element.setNetworkId(next++);
        for (UIElement child : element.getChildren()) next = assign(child, next);
        return next;
    }

    /** How many elements {@link #assign} would number, without touching anything. */
    public static int count(UIElement root) {
        int total = 1;
        for (UIElement child : root.getChildren()) total += count(child);
        return total;
    }

    /** The element with {@code networkId}, or {@code null}. Linear, which is fine at UI sizes and
     * avoids a map that could drift out of step with the tree. */
    public static UIElement find(UIElement root, int networkId) {
        if (root.getNetworkId() == networkId) return root;
        for (UIElement child : root.getChildren()) {
            UIElement found = find(child, networkId);
            if (found != null) return found;
        }
        return null;
    }
}
