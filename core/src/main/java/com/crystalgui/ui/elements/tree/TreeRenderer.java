package com.crystalgui.ui.elements.tree;

import com.crystalgui.ui.UIElement;

/**
 * Builds and fills tree rows — {@link com.crystalgui.ui.elements.list.ListRenderer}'s split, with the
 * node's tree context handed alongside it.
 *
 * <p>{@link #createTemplate()} runs once per recycled element and is the only place listeners belong;
 * {@link #bind} runs per row and writes data. Same contract, same reason.</p>
 *
 * <p>The renderer does <b>not</b> apply indentation or draw the twisty's state — {@code TreeView} sets
 * {@code padding-left} from the depth and puts {@code __expanded__}/{@code __collapsed__}/{@code __leaf__}
 * on the row, so a theme draws the marker and the renderer never has to know the rules. What the renderer
 * does own is the expander <em>element</em>, if it wants one, and calling
 * {@link TreeView#toggleExpandedAt} from its listener.</p>
 */
public interface TreeRenderer<T> {

    UIElement createTemplate();

    /**
     * @param row the flattened context — depth, expandable, expanded. Passed because a renderer usually
     *            wants at least {@code expandable} to decide whether to show a twisty at all.
     */
    void bind(T item, TreeRow<T> row, int index, UIElement template);

    default void unbind(UIElement template) {
    }
}
