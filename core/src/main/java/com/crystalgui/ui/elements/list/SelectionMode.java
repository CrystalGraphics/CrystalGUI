package com.crystalgui.ui.elements.list;

/**
 * How many rows a {@link ListView} may have selected at once.
 *
 * <p>{@link #SINGLE} is the default, matching a plain HTML {@code <select>} and every file list: multiple
 * selection is a real feature with real interactions (anchor, range, toggle) and turning it on ought to be
 * a decision rather than something inherited by accident.</p>
 */
public enum SelectionMode {
    /** Rows can be focused but never selected — a read-only log, or a list that is purely a viewport. */
    NONE,
    SINGLE,
    MULTIPLE
}
