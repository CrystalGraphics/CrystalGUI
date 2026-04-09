package com.crystalgui.core.tree;

import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class TreeTraversal {

    private TreeTraversal() {
    }

    public static void depthFirst(UIElement root, Consumer<UIElement> visitor) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (visitor == null) {
            throw new IllegalArgumentException("visitor must not be null");
        }

        visitor.accept(root);
        for (UIElement child : root.getChildren()) {
            depthFirst(child, visitor);
        }
    }

    public static List<UIElement> depthFirst(UIElement root) {
        final List<UIElement> result = new ArrayList<UIElement>();
        depthFirst(root, new Consumer<UIElement>() {
            @Override
            public void accept(UIElement element) {
                result.add(element);
            }
        });
        return result;
    }
}
