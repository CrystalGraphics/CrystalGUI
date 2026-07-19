package com.crystalgui.core.data;

import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.List;

public class FocusableSubtreeCache {
    private List<UIElement> flatList;
    private final UIElement element;
    private boolean isDirty;

    public FocusableSubtreeCache invalidate() {
        this.isDirty = true;
        if (element.getParent() != null) {
            element.getParent().getRuntimeCache().focusableSubtree.invalidate();
        }
        return this;
    }

    public FocusableSubtreeCache(UIElement element) {
        this.element = element;
        invalidate();
    }

    public List<UIElement> get() {
        if (isDirty) {
            calculate();
        }

        return flatList;
    }

    private void calculate() {
        flatList = new ArrayList<>();
        if (element.getParent() == null && element.focusable())
            flatList.add(element);

        for (var child : element.getChildren()) {
            if (child.focusable())
                flatList.add(child);
            flatList.addAll(child.getRuntimeCache().focusableSubtree.get());
        }
    }
}
