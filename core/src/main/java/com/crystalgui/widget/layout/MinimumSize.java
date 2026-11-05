package com.crystalgui.widget.layout;

/**
 * The smallest an element may be squeezed to, when that is more than its own {@code min-width} or {@code min-height}
 * says: a split of splits, whose floor is its panes' floors added up, or a dock area whose floor is its panels'.
 * VS Code's {@code IView.minimumWidth} and {@code minimumHeight}.
 *
 * <pre>{@code
 * public final class Board extends UIElement implements MinimumSize {
 *     public float minimumSize(boolean vertical) {
 *         return SplitView.minimumOf(inner, vertical);   // whatever this element holds decides
 *     }
 * }
 * }</pre>
 *
 * <p>Asked by a {@link SplitView}'s dividers, by a resize drag on any resizable element, and by a window of its
 * content ({@link LayoutMinimum}, which folds a layout's children for an element that does not answer this). Most
 * content needs only a {@code min-width}/{@code min-height} in its sheet; implement this where the floor depends on
 * what is open inside it.</p>
 */
public interface MinimumSize {

    /** The smallest this element may be, in pixels, on the vertical axis or the horizontal one. */
    float minimumSize(boolean vertical);
}
