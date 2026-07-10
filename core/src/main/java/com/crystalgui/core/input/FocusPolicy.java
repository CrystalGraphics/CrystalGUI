package com.crystalgui.core.input;

/**
 * Defines how an element participates in focus.
 *
 * <p>Set on {@link com.crystalgui.ui.UIElement} to control whether the element
 * can receive keyboard focus and how it acquires it.</p>
 */
public enum FocusPolicy {

    /** Element cannot receive focus. */
    NONE,

    /** Element can receive focus via programmatic {@code requestFocus()} or tab traversal. */
    FOCUSABLE,

    /** Element can receive focus via click, programmatic request, or tab traversal. */
    CLICK
}
