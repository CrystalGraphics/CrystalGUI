package com.crystalgui;

/**
 * Declarative UI description Just a root element handle;
 * no runtime state, no layout state, no GL state. Owned and driven by {@link UiRuntime}.
 *
 * <p>This is intentionally the minimal version needed to get painting
 * working.</p>
 */
public final class Ui {

    public final UIElement rootElement;

    private Ui(UIElement rootElement) {
        this.rootElement = rootElement;
    }

    public static Ui of(UIElement rootElement) {
        return new Ui(rootElement);
    }

    public static Ui of() {
        return new Ui(new UIElement());
    }
}
