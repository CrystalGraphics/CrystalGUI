package com.crystalgui.app.uibuilder.document;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.crystalgui.ui.dom.UIElement;

/**
 * A document's ids: how one may be spelled, which are taken, and a free one for a name that is.
 *
 * <pre>{@code
 * Set<String> taken = NodeIds.taken(document.root());
 * String id = NodeIds.free("save", taken);   // "save", or "save2" when save is taken, "save3" after that
 * }</pre>
 *
 * <p>One place for it because a duplicate and a rename both need a free id, and a document where the two
 * numbered differently would read as two features rather than one rule. Numbered from 2, as Windows names a
 * second {@code save}; its {@code save (2)} is not an id a selector can spell.</p>
 */
public final class NodeIds {

    /** What a {@code #id} selector can spell. Empty is an element with no id. */
    public static final Pattern SPELLING = Pattern.compile("[\\w-]*");

    private NodeIds() {
    }

    public static boolean isSpellable(String id) {
        return SPELLING.matcher(id).matches();
    }

    /** Every non-empty id in {@code root}'s light subtree, {@code root} included. A fresh, writable set. */
    public static Set<String> taken(UIElement root) {
        Set<String> taken = new HashSet<>();
        collect(root, taken);
        return taken;
    }

    /** {@code wanted} when nothing has taken it, else the first of {@code wanted2}, {@code wanted3}, … that is free. */
    public static String free(String wanted, Set<String> taken) {
        if (!taken.contains(wanted)) return wanted;
        for (int n = 2; ; n++) {
            if (!taken.contains(wanted + n)) return wanted + n;
        }
    }

    private static void collect(UIElement node, Set<String> into) {
        if (!node.id().isEmpty()) into.add(node.id());
        for (UIElement child : node.children()) collect(child, into);
    }
}
