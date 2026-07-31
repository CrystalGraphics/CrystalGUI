package com.crystalgui.ui.elements.tree;

import java.util.List;

/**
 * Where a {@link TreeView} gets its nodes — <b>pull-based</b>, so children are asked for when a node is
 * expanded rather than supplied up front.
 *
 * <p>That is VS Code's {@code AsyncDataTree} idea with the async removed. A file explorer does not know a
 * folder's contents until it is opened, and a source shaped that way can still express a fully-known tree
 * at no cost — so pull is strictly more general than push and no harder to implement.</p>
 *
 * <p><b>{@link #hasChildren} is separate from {@code children().isEmpty()} on purpose.</b> A folder can be
 * known to be expandable without being read, which is the whole reason a tree can show a twisty next to
 * ten thousand directories without touching the disk. A source that genuinely cannot tell may answer from
 * {@code children}, and pays for it.</p>
 *
 * <p>Not async, deliberately: the realistic sources here are in-memory, and this interface is the seam if
 * that ever stops being true — which is why it is an interface rather than a concrete node type.</p>
 */
public interface TreeDataSource<T> {

    /** The top-level nodes. */
    List<T> roots();

    /** {@code parent}'s children, called when it is expanded. */
    List<T> children(T parent);

    /** Whether {@code item} can be expanded at all — decides the twisty, and is asked for every visible
     * row, so it must be cheap. */
    boolean hasChildren(T item);
}
