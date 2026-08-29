package com.crystalgui.net;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.dom.ElementTreeSource;

/**
 * Assigns each element the number both sides will address it by.
 *
 * <h3>The numbers no longer live on the elements</h3>
 * <p>They used to: {@code UIElement.networkId}, written by the walk below. Since the number WAS the
 * position, inserting a sibling renumbered everything after it and a client-side reparent moved an
 * element out from under the number the server was still addressing it by. The table is now owned by
 * an {@link ElementTreeSource}, which is the seam the mirror will be written against
 * ({@code plan_ui_rewrite.md} M0), so a session can hold its own numbering instead of every session
 * over one tree fighting over one field.</p>
 *
 * <p><b>The POLICY is still positional, and M2 is where that changes.</b> Both sides still derive the
 * same ids from the same depth-first walk, so nothing on the wire has moved yet. Changing the storage
 * and the policy in one commit would leave any failure attributable to either.</p>
 *
 * <h3>Derived, not transmitted</h3>
 * <p>Ids are the element position in a depth-first walk, so the client computes exactly the same ones
 * from its rebuilt tree and nothing has to carry them. Two consequences follow, both good: the
 * description stays a pure description (so its content hash is about the UI, not about numbering), and
 * there is no id table to get out of step.</p>
 *
 * <p><b>Internal children are numbered too.</b> They exist identically on both sides -- a Button label
 * is built by the same constructor in both processes -- so they are addressable, and skipping them
 * would make the two walks disagree the moment a composite appeared.</p>
 *
 * <h3>Where this can go wrong, and what catches it</h3>
 * <p>The walk assumes both sides build the same structure. If a client widget constructor differs from
 * the server one -- a version skew the description itself cannot reveal, since internals are never
 * serialized -- every id past that point would shift by one and updates would land on the wrong
 * elements. {@link #count} is sent at open for exactly that reason: a mismatch is refused rather than
 * silently misapplied.</p>
 */
public final class NetworkIds {

    private NetworkIds() {
    }

    /**
     * Numbers {@code root}'s subtree from 0 in document order, into {@code ids}. Returns how many were
     * assigned.
     *
     * <p>Renumbers from scratch: a re-describe has to give the far side a numbering it can re-derive
     * from its own rebuilt tree, so a stale allocation surviving would be a number nobody agrees on.</p>
     */
    public static int assign(ElementTreeSource ids, UIElement root) {
        ids.resetIds();
        return ids.assignInDocumentOrder(root);
    }

    /** How many elements {@link #assign} would number, without touching anything. */
    public static int count(UIElement root) {
        int total = 1;
        for (UIElement child : root.getChildren()) total += count(child);
        return total;
    }

    /**
     * The element with {@code networkId}, or {@code null}.
     *
     * <p>A map lookup. It used to be a walk of the whole tree comparing a field on every element, per
     * packet -- justified at the time as avoiding "a map that could drift out of step with the tree",
     * which is exactly the drift a stable id removes: the table is the identity, so there is nothing
     * for it to disagree with.</p>
     */
    public static UIElement find(ElementTreeSource ids, int networkId) {
        return ids.byId(networkId);
    }
}
