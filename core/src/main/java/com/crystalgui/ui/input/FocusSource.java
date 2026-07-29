package com.crystalgui.ui.input;

/**
 * How an element came to be focused — which decides two separate things that happen to coincide today:
 * whether a focus ring shows ({@code :focus-visible}) and whether the target is scrolled into view.
 *
 * <p>Exists as an enum rather than a {@code boolean} precisely because those two are separate concerns.
 * A single flag named for one of them, silently governing the other, is the kind of thing that reads
 * correctly right up until someone adds a fourth way to focus something.</p>
 *
 * <p>The mapping matches the web's {@code :focus-visible}: keyboard and programmatic focus draw a ring,
 * a pointer click does not — with the standard carve-out that elements taking text input always do (see
 * {@link com.crystalgui.ui.UIElement#consumesTextInput()}), which is handled at the call site rather
 * than here since it depends on the target, not the source.</p>
 */
public enum FocusSource {
    /** Tab traversal. Rings, and scrolls the target into view — you cannot see where you tabbed to
     *  otherwise. */
    KEYBOARD(true, true),
    /** {@code requestFocus} from code. Treated as keyboard-equivalent, matching {@code element.focus()}
     *  in a browser. */
    PROGRAMMATIC(true, true),
    /**
     * A mouse click. No ring — you already know what you clicked, which is the whole reason
     * {@code :focus-visible} exists — and no scroll, since you clicked something already on screen.
     */
    POINTER(false, false);

    private final boolean ringByDefault;
    private final boolean scrollsIntoView;

    FocusSource(boolean ringByDefault, boolean scrollsIntoView) {
        this.ringByDefault = ringByDefault;
        this.scrollsIntoView = scrollsIntoView;
    }

    /** Whether focus from this source rings, before the text-input carve-out is applied. */
    public boolean ringsByDefault() {
        return ringByDefault;
    }

    public boolean scrollsIntoView() {
        return scrollsIntoView;
    }
}
