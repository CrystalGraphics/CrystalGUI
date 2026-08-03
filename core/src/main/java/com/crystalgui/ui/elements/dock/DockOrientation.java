package com.crystalgui.ui.elements.dock;

/**
 * Which axis a {@link DockBranch} divides.
 *
 * <p>Deliberately not {@code SplitView.Orientation}: the layout tree is pure data with no widget in it
 * (see {@link DockLayout}), and importing a {@code UIElement} subclass's nested enum would make the whole
 * headless half depend on the widget half for a two-constant type.</p>
 */
public enum DockOrientation {

    /** Children sit side by side; a divider between them moves horizontally. */
    HORIZONTAL,

    /** Children sit stacked; a divider between them moves vertically. */
    VERTICAL;

    public DockOrientation orthogonal() {
        return this == HORIZONTAL ? VERTICAL : HORIZONTAL;
    }
}
