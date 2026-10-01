package com.crystalgui.ui.input;

import com.crystalgraphics.platform.input.CgModifiers;

/**
 * Which presses a key-down listener takes, declared where it is attached.
 *
 * <pre>{@code
 * field.events.getGroup(KeyboardEvent.Down.class).attachListener((el, e) -> { ... Enter, Escape ... });
 * field.claimKeys((key, typed, modifiers) -> key == CgKeyCodes.KEY_RETURN || key == CgKeyCodes.KEY_ESCAPE);
 * }</pre>
 *
 * <p>Ask only state the listener itself reads: the answer is taken before the press is dispatched.</p>
 */
@FunctionalInterface
public interface KeyClaim {

    boolean claims(int key, char typed, int modifiers);

    /** A claim on exactly these keys, when no Ctrl or Alt is held. */
    static KeyClaim plain(int... keys) {
        return (key, typed, modifiers) -> {
            if (CgModifiers.hasCtrl(modifiers) || CgModifiers.hasAlt(modifiers)) return false;
            for (int k : keys) {
                if (k == key) return true;
            }
            return false;
        };
    }
}
