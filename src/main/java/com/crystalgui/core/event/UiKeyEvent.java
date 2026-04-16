package com.crystalgui.core.event;

import lombok.Getter;

/**
 * Keyboard event with platform-agnostic key code and modifier information.
 *
 * <p>Key codes use {@link CgUiKeyCodes} constants. Modifier flags use {@link Modifiers}.</p>
 */
@Getter
public final class UiKeyEvent extends UiEvent {

    private final int keyCode;
    private final int modifiers;
    private final char character;

    private UiKeyEvent(UiEventType type, int keyCode, int modifiers, char character) {
        super(type);
        this.keyCode = keyCode;
        this.modifiers = modifiers;
        this.character = character;
    }

    /** Creates a key down/up event. */
    public static UiKeyEvent key(UiEventType type, int keyCode, int modifiers) {
        return new UiKeyEvent(type, keyCode, modifiers, '\0');
    }

    /** Creates a KEY_TYPED event with the typed character. */
    public static UiKeyEvent typed(char character, int modifiers) {
        return new UiKeyEvent(UiEventType.KEY_TYPED, 0, modifiers, character);
    }
    
    public boolean hasShift() { return Modifiers.hasShift(modifiers); }
    public boolean hasCtrl() { return Modifiers.hasCtrl(modifiers); }
    public boolean hasAlt() { return Modifiers.hasAlt(modifiers); }
    public boolean hasSuper() { return Modifiers.hasSuper(modifiers); }
}
