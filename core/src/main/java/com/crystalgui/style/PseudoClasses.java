package com.crystalgui.style;


import java.util.Locale;
import java.util.function.Predicate;

public enum PseudoClasses {
    ENABLED(Styleable::isEnabled),
    DISABLED(el -> !el.isEnabled()),
    CHECKED(Styleable::isChecked),
    BLANK(Styleable::isBlank),
    INVALID(Styleable::isInvalid),
    HOVER(Styleable::isHovered),
    ACTIVE(Styleable::isPressed),
    /** Focused by any means — click included. */
    FOCUS(Styleable::isFocused),
    /**
     * Focused in a way that should show a ring, i.e. the web's {@code :focus-visible}.
     *
     * <p>True for keyboard (Tab) and programmatic focus; false when a pointer click moved focus, unless
     * the element takes text input, which browsers always ring. See {@code UIInputHandler}'s
     * {@code FocusSource} for where that is decided.</p>
     */
    FOCUS_VISIBLE(Styleable::isFocusVisible),
    /**
     * True for the focused element AND every ancestor of it — the web's {@code :focus-within}.
     *
     * <p>The state a container needs to say "the focus is in me": which editor group a keystroke
     * goes to, which tool window is current. Both references answer that question this way — CSS
     * names it {@code :focus-within}, and IntelliJ's "active tool window" is defined as the one
     * owning the focus — and both mark it on the TAB rather than by outlining the whole region.</p>
     *
     * <p>It was very nearly a {@code __focused__} class maintained by hand in ViewContainer and
     * DockGroup instead. That is the same behaviour with a worse name, two copies to keep in step,
     * and no answer for the next container that wants it. The invariant that an unknown pseudo-class
     * poisons the whole sheet is an argument for EXTENDING this set deliberately, not for routing
     * around it — a {@code :focus-within} rule is exactly what once took six unrelated panels down,
     * because it was not registered here.</p>
     */
    FOCUS_WITHIN(Styleable::isFocusWithin),
    /** The top of a tree: a document, or a detached subtree's root. Where a UA sheet sets its font size. */
    OPEN(Styleable::isOpen),
    ROOT(Styleable::isRoot);

    final Predicate<Styleable> elementPredicate;
    PseudoClasses(Predicate<Styleable> predicate) {
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

    /**
     * Whether this pseudo-class matches {@code element} right now.
     *
     * <p><b>A forced state is asked first</b>, which is the one place that has to happen: every selector
     * goes through here, so a devtools override applied at the getters would have to be applied at
     * twelve of them and would still miss the thirteenth.</p>
     */
    public boolean applies(Styleable element) {
        Boolean forced = element.forcedState(this);
        if (forced != null) return forced;
        return elementPredicate.test(element);
    }
}
