package com.crystalgui.ui.event;

import com.crystalgui.ui.UIElement;
import lombok.Getter;

@Getter
public abstract class KeyboardEvent extends UIEvent {
    private final int keyCode;
    private final char character;
    private final boolean repeat;
    private final int modifiers;
    private final long millis;

    protected KeyboardEvent(UIElement target, int keyCode, char scanCode, boolean repeat, int modifiers, long millis) {
        super(target, true);
        this.keyCode = keyCode;
        this.character = scanCode;
        this.repeat = repeat;
        this.modifiers = modifiers;
        this.millis = millis;
    }

    public static final class Down extends KeyboardEvent {
        public Down(UIElement target, int keyCode, char scanCode, boolean repeat, int modifiers, long millis) {
            super(target, keyCode, scanCode, repeat, modifiers, millis);
        }
    }

    public static final class Up extends KeyboardEvent {
        public Up(UIElement target, int keyCode, char scanCode, boolean repeat, int modifiers, long millis) {
            super(target, keyCode, scanCode, repeat, modifiers, millis);
        }
    }

}
