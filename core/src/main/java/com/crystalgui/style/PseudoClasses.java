package com.crystalgui.style;

import com.crystalgui.ui.UIElement;

import java.util.Locale;
import java.util.function.Predicate;

public enum PseudoClasses {
    ENABLED(UIElement::isEnabled),
    DISABLED(el -> !el.isEnabled()),
    CHECKED(UIElement::isChecked),
    BLANK(UIElement::isBlank),
    INVALID(UIElement::isInvalid),
    HOVER(UIElement::isHovered),
    ACTIVE(UIElement::isPressed),
    /** Focused by any means — click included. */
    FOCUS(UIElement::isFocused),
    /**
     * Focused in a way that should show a ring, i.e. the web's {@code :focus-visible}.
     *
     * <p>True for keyboard (Tab) and programmatic focus; false when a pointer click moved focus, unless
     * the element takes text input, which browsers always ring. See {@code UIInputHandler}'s
     * {@code FocusSource} for where that is decided.</p>
     */
    FOCUS_VISIBLE(UIElement::isFocusVisible);

    final Predicate<UIElement> elementPredicate;
    PseudoClasses(Predicate<UIElement> predicate) {
        this.elementPredicate = predicate;
    }

    /**
     * Resolves a CSS pseudo-class name to its constant — {@code focus-visible} to {@link #FOCUS_VISIBLE}.
     *
     * <p>The only difference between the two spellings is the separator, so this is a case fold plus a
     * hyphen swap. It exists because {@code valueOf} alone throws on any hyphenated name, and the
     * selector parser validates eagerly: an unmapped name doesn't skip one rule, it propagates out of
     * {@code StyleSheet.parse} and takes the <b>entire sheet</b> with it.</p>
     *
     * @throws IllegalArgumentException if no such pseudo-class exists — deliberate, so a typo fails at
     *         parse time rather than silently never matching at paint time.
     */
    public static PseudoClasses lookup(String cssName) {
        return valueOf(cssName.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public boolean applies(UIElement element) {
        return elementPredicate.test(element);
    }
}
