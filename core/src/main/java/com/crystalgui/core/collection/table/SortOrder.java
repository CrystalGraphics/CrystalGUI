package com.crystalgui.core.collection.table;

/**
 * A column's sort state, cycled by clicking its header.
 *
 * <p><b>Three states, not two.</b> Explorer and Finder cycle ascending &harr; descending and never let
 * you get back to the file's own order; VS Code and every serious data grid offer {@link #NONE} as a
 * third click. It costs nothing here — the unsorted view <em>is</em> the source order, which
 * {@code TableView} keeps anyway because it never mutates the caller's list — and "put it back how it
 * was" is a thing people genuinely want once they have sorted by something useless.</p>
 */
public enum SortOrder {
    NONE,
    ASCENDING,
    DESCENDING;

    /** The next state on a header click: none → ascending → descending → none. */
    public SortOrder next() {
        return switch (this) {
            case NONE -> ASCENDING;
            case ASCENDING -> DESCENDING;
            case DESCENDING -> NONE;
        };
    }
}
