package com.crystalgui.mc;

import com.crystalgui.core.event.CgUiKeyCodes;
import com.crystalgui.core.event.Modifiers;
import org.lwjgl.input.Keyboard;

/**
 * Translates LWJGL 2 key codes and modifier state into platform-agnostic
 * CrystalGUI constants. Lives in the MC package because it imports LWJGL.
 */
public final class LwjglKeyTranslator {

    private LwjglKeyTranslator() {}

    public static int translateKeyCode(int lwjglKey) {
        switch (lwjglKey) {
            case Keyboard.KEY_TAB:       return CgUiKeyCodes.KEY_TAB;
            case Keyboard.KEY_BACK:      return CgUiKeyCodes.KEY_BACKSPACE;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER: return CgUiKeyCodes.KEY_ENTER;
            case Keyboard.KEY_DELETE:     return CgUiKeyCodes.KEY_DELETE;
            case Keyboard.KEY_LEFT:       return CgUiKeyCodes.KEY_LEFT;
            case Keyboard.KEY_RIGHT:      return CgUiKeyCodes.KEY_RIGHT;
            case Keyboard.KEY_HOME:       return CgUiKeyCodes.KEY_HOME;
            case Keyboard.KEY_END:        return CgUiKeyCodes.KEY_END;
            case Keyboard.KEY_A:          return CgUiKeyCodes.KEY_A;
            default:                      return lwjglKey;
        }
    }

    public static int currentModifiers() {
        int mods = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            mods |= Modifiers.SHIFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
            mods |= Modifiers.CTRL;
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
            mods |= Modifiers.ALT;
        return mods;
    }
}
