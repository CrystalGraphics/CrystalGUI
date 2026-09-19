package com.crystalgui.widget.layout;

/**
 * What an element placed in a {@link SplitView} pane may not be squeezed below, when it is more than its own
 * {@code min-width} says — a split of splits, whose floor is its panes' floors added up. VS Code's
 * {@code IView.minimumWidth} and {@code minimumHeight}.
 *
 * <pre>{@code
 * public final class Board extends UIElement implements MinimumSize {
 *     public float minimumSize(boolean vertical) {
 *         return SplitView.minimumOf(inner, vertical);   // whatever this element holds decides
 *     }
 * }
 * }</pre>
 *
 * <p>A split pane's divider stops where the element in it reaches this size. Only a pane's own content is asked:
 * an element deeper down says so through the one above it.</p>
 */
public interface MinimumSize {

    /** The smallest this element may be, in pixels, on the vertical axis or the horizontal one. */
    float minimumSize(boolean vertical);
}
