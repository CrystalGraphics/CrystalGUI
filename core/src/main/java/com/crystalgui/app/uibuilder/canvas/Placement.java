package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * Putting a new node into a builder's document — one rule for the Hierarchy's New ▸, a Library double-click and
 * a drop on either surface.
 *
 * <pre>{@code
 * Placement.intoSelection(builder, new Button("Button"));    // New ▸, a double-click
 * Placement.at(builder, container, 2, node);                 // a drop that resolved a place
 * }</pre>
 *
 * <p>Each placement is one undo step, selects what it placed, and opens in-place editing on a text leaf so
 * the first thing typed is its words.</p>
 */
public final class Placement {

    private Placement() {
    }

    /**
     * Into the selected container, after a selected leaf in its parent, or at the end of the root with nothing
     * selected.
     *
     * @return whether it was placed
     */
    public static boolean intoSelection(BuilderContext builder, UIElement node) {
        UIElement root = builder.getDocument().root();
        List<UIElement> selected = builder.builderSelection().nodes();
        UIElement anchor = selected.isEmpty() ? root : selected.get(selected.size() - 1);
        UIElement parent = TreeDropRules.isContainer(root, anchor) ? anchor : anchor.parentElement();
        if (parent == null) return false;
        int index = parent == anchor ? parent.children().size() : parent.indexOf(anchor) + 1;
        return at(builder, parent, index, node);
    }

    /**
     * Into {@code parent} at child {@code index}, clamped to its children.
     *
     * @return whether it was placed — false where {@code parent} takes no children
     */
    public static boolean at(BuilderContext builder, UIElement parent, int index, UIElement node) {
        if (!TreeDropRules.isContainer(builder.getDocument().root(), parent)) return false;
        int clamped = Math.max(0, Math.min(index, parent.children().size()));
        builder.getDocument().apply(new BuilderEdit.Insert(parent, node, clamped));
        builder.builderSelection().replaceWith(List.of(node));
        if (node instanceof UIText) {
            UIBuilderView editor = editorOf(builder);
            if (editor != null) editor.textEditing().begin(node);
        }
        return true;
    }

    @Nullable
    private static UIBuilderView editorOf(BuilderContext builder) {
        return builder instanceof BuilderSurface surface ? surface.owner() : null;
    }
}
