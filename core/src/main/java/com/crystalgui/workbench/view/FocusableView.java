package com.crystalgui.workbench.view;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * A view that says what should hold the keyboard when its tool window is activated — IntelliJ's
 * {@code Content.getPreferredFocusableComponent}.
 *
 * <pre>{@code
 * public UIElement focusTarget() {
 *     return tree;   // a press on the header puts the keys, and the blue selection band, in the tree
 * }
 * }</pre>
 *
 * <p>Without it the container focuses the first focusable element in the view, which for a panel that is
 * itself focusable is the panel rather than the list inside it.</p>
 */
public interface FocusableView {

    /** What {@link ViewContainer#focusView} focuses, or null to fall back to the first focusable element. */
    @Nullable
    UIElement focusTarget();
}
