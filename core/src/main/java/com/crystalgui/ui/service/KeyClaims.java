package com.crystalgui.ui.service;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.keymap.KeyStroke;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Which key presses a document takes from its host, decided from state rather than by running listeners.
 *
 * <pre>{@code
 * KeyClaims claims = KeyClaims.of(document);
 * boolean ours = claims.claims(CgKeyCodes.KEY_ESCAPE, '\0', 0);   // Minecraft must not see it when true
 * }</pre>
 *
 * <p>A document on its own thread cannot run its listeners to answer the host's synchronous "is this key
 * yours" (plan engine-threaded-ui §2.5), so it commits this and the render thread asks it. Each part is a
 * declaration: a live {@link InputMode}'s {@link InputMode#claimsKey}, the keymap's
 * {@link Input#claimedStrokes}, the focused path's {@link UIElement#claimsKey} and
 * {@link UIElement#claimsChord}, typing and editing keys in an element that
 * {@linkplain UIElement#consumesTextInput consumes text}, Tab while it has somewhere to go, and Escape while
 * something can be dismissed.</p>
 *
 * <p>{@code -Dcrystalgui.input.claimsCheck=true} compares this with the answer dispatch gives on every press
 * and reports each disagreement once (with {@code -Dlog4j2.level=WARN} in tests): the code that still
 * decides without declaring.</p>
 */
public final class KeyClaims {

    private final List<InputMode> modes;
    private final boolean tabTraverses;
    private final boolean textInput;
    private final boolean dismissable;
    private final Set<KeyStroke> bound;
    @Nullable
    private final UIElement focused;

    private KeyClaims(List<InputMode> modes, boolean tabTraverses, boolean textInput, boolean dismissable,
                      Set<KeyStroke> bound, @Nullable UIElement focused) {
        this.modes = modes;
        this.tabTraverses = tabTraverses;
        this.textInput = textInput;
        this.dismissable = dismissable;
        this.bound = bound;
        this.focused = focused;
    }

    /** {@code document}'s claims as its state stands now. */
    public static KeyClaims of(UIDocument document) {
        UIElement focused = document.focus().focused();
        Dismiss dismiss = document.dismiss();
        Focus focus = document.focus();
        return new KeyClaims(
                document.input().modes(),
                focused != null || focus.firstTabbableIn(tabScope(document, focus)) != null,
                focused != null && focused.consumesTextInput(),
                !dismiss.closeWatchers().isEmpty() || !dismiss.autoPopovers().isEmpty(),
                document.input().claimedStrokes(),
                focused);
    }

    /** Whether a press of {@code key}, typing {@code typed}, with {@code modifiers} held, is the document's. */
    public boolean claims(int key, char typed, int modifiers) {
        for (InputMode mode : modes) {
            if (mode.claimsKey(key, modifiers)) return true;
        }
        boolean chord = CgModifiers.hasCtrl(modifiers) || CgModifiers.hasAlt(modifiers);
        if (chord && focused != null && focused.claimsChord(key, modifiers)) return true;
        for (UIElement walk = focused; walk != null; walk = walk.composedParent()) {
            if (walk.claimsKey(key, typed, modifiers)) return true;
        }
        if (bound.contains(new KeyStroke(key, modifiers))) return true;
        if (textInput && !chord && (typing(typed) || editing(key))) return true;
        if (key == CgKeyCodes.KEY_ESCAPE && dismissable) return true;
        return key == CgKeyCodes.KEY_TAB && !chord && tabTraverses;
    }

    /** Where Tab lands from no focus: the scope {@link Focus#moveTabFocus} starts in. */
    private static UIElement tabScope(UIDocument document, Focus focus) {
        UIElement modal = focus.blockingModal(document);
        return modal != null ? modal : focus.scopeOf(null);
    }

    private static boolean typing(char typed) {
        return typed != 0 && !Character.isISOControl(typed);
    }

    /** What every text input does with a key; anything further (Up, Return) its widget declares. */
    private static boolean editing(int key) {
        return key == CgKeyCodes.KEY_BACK || key == CgKeyCodes.KEY_DELETE
                || key == CgKeyCodes.KEY_LEFT || key == CgKeyCodes.KEY_RIGHT
                || key == CgKeyCodes.KEY_HOME || key == CgKeyCodes.KEY_END;
    }
}
