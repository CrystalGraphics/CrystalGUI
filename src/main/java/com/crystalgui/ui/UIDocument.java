package com.crystalgui.ui;

public final class UIDocument {

    private final UIElement root;

    private UIDocument(UIElement root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root;
    }

    public static UIDocument of(UIElement root) {
        return new UIDocument(root);
    }

    public UIElement getRoot() {
        return root;
    }
}
