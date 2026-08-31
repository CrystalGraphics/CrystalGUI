package com.crystalgui.core.collection.tree;

import com.crystalgui.core.collection.tree.TreeDataSource;

/**
 * One visible node in a flattened tree — what the underlying {@link com.crystalgui.ui.elements.list.ListView}
 * actually holds.
 *
 * <p>A tree is a list of these, recomputed whenever expansion changes. Depth is carried here rather than
 * derived, because after flattening there is no parent pointer to walk and indentation needs it on every
 * row.</p>
 *
 * @param item        the caller's own node
 * @param depth       0 for a root, incrementing per level — what indentation is drawn from
 * @param expandable  whether it can be opened, from {@code TreeDataSource.hasChildren}
 * @param expanded    whether it currently is
 * @param parentIndex the flattened index of this row's parent, or -1 for a root. Precomputed because
 *                    Left-arrow has to reach the parent, and a flattened list has no other way back up —
 *                    the alternative is scanning backwards for the first row of lower depth on every
 *                    keypress.
 */
public record TreeRow<T>(T item, int depth, boolean expandable, boolean expanded, int parentIndex) {
}
