package com.crystalgui.ui.input.keymap;

import lombok.Getter;

import javax.annotation.Nullable;

/**
 * One chord → command id mapping, scoped to the {@link Keymap} that holds it.
 *
 * <p>Carries no condition of its own. Whether the action can run right now is
 * {@code Command.isEnabled()}'s answer, and whether it applies <em>here</em> is answered structurally by
 * which element owns this keymap — see {@link Keymap}. A binding that also carried a predicate would be a
 * second activation mechanism alongside scoping, and two mechanisms that can disagree about one question
 * is the whole failure mode to avoid.</p>
 */
public final class KeyBinding {

    @Getter private final KeyChord chord;
    @Getter private final String commandId;
    @Getter private KeyEventType eventType = KeyEventType.PRESS;
    @Getter private boolean allowedWhileTyping;
    @Getter @Nullable private Object args;

    KeyBinding(KeyChord chord, String commandId) {
        this.chord = chord;
        this.commandId = commandId;
    }

    /**
     * Fire on key release instead of key press.
     *
     * <p>What space-to-pan is made of, and what 6.2.2's canvas will want: a {@code PRESS} binding starts
     * the pan and a {@code RELEASE} binding ends it.</p>
     */
    public KeyBinding on(KeyEventType type) {
        this.eventType = type;
        return this;
    }

    /**
     * Let this binding fire even while the focused element is taking text input.
     *
     * <p>Off by default, and that default is the whole point. A bare {@code B} selects the brush in
     * Photoshop and types a "b" in a filename box; without this guard every single-key tool shortcut
     * corrupts every text field in the application. Bindings carrying a non-Shift modifier are unaffected
     * either way — {@code Mod+S} is unambiguous inside a text field — so this opt-in is only ever needed
     * for the genuinely bare ones, such as {@code Escape}.</p>
     */
    public KeyBinding allowWhileTyping() {
        this.allowedWhileTyping = true;
        return this;
    }

    /** Binding-scoped payload handed to the command — VS Code's {@code "args"}. Lets one command be bound
     * twice with different parameters instead of becoming two commands. */
    public KeyBinding withArgs(@Nullable Object args) {
        this.args = args;
        return this;
    }

    @Override
    public String toString() {
        return chord + " -> " + commandId + (eventType == KeyEventType.RELEASE ? " (on release)" : "");
    }
}
