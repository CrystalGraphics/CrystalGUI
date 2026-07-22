package com.crystalgui.style;

import com.crystalgui.ui.UIElement;

import java.util.function.Predicate;

public enum PseudoClasses {
    ENABLED(UIElement::isEnabled),
    DISABLED(el -> !el.isEnabled()),
    CHECKED(UIElement::isChecked),
    BLANK(UIElement::isBlank),
    HOVER(UIElement::isHovered),
    ACTIVE(UIElement::isPressed),
    FOCUS(UIElement::isFocused);

    final Predicate<UIElement> elementPredicate;
    PseudoClasses(Predicate<UIElement> predicate) {
        this.elementPredicate = predicate;
    }

    public boolean applies(UIElement element) {
        return elementPredicate.test(element);
    }
}
