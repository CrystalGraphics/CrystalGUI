package com.crystalgui.ui.input;

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
    CLICK,

    /**
     * Focusable by click and by {@code requestFocus()}, but <b>skipped by Tab</b>.
     *
     * <p>This is the web's {@code tabindex="-1"}, and it is what makes a composite widget behave like
     * one control instead of N. The
     * <a href="https://www.w3.org/WAI/ARIA/apg/practices/keyboard-interface/">ARIA Authoring
     * Practices</a> rule: "the tab sequence should include only one focusable element of a composite UI
     * component", while "the arrow keys move focus inside" it. A tablist is one tab stop; Left/Right
     * moves between its tabs; Tab leaves for whatever follows the whole strip.</p>
     *
     * <h3>Why not port {@code tabindex} as an integer</h3>
     * <p>The roving-tabindex pattern only ever uses {@code 0} and {@code -1}, and the APG never uses a
     * positive value. Positive {@code tabindex} reorders the entire document from a single element and
     * is widely regretted for exactly that reason — importing it would mean owning a misfeature, and it
     * would overlap this enum confusingly ({@code NONE} vs negative, {@code FOCUSABLE}/{@code CLICK} vs
     * zero). One extra enum constant expresses the whole pattern; arbitrary reordering is a separate
     * feature nobody has asked for.</p>
     */
    CLICK_NOT_TABBABLE;

    public boolean isFocusable() {
        return this != NONE;
    }

    /** Whether Tab/Shift+Tab may land here. False for {@code tabindex="-1"}-style elements, which are
     * still reachable by click, by arrow keys inside a composite, and by {@code requestFocus()}. */
    public boolean isTabbable() {
        return this == FOCUSABLE || this == CLICK;
    }

    /** Whether a click should move focus here. */
    public boolean focusesOnClick() {
        return this == CLICK || this == CLICK_NOT_TABBABLE;
    }
}