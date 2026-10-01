package com.crystalgui.mc.v1710.client;

import com.crystalgui.core.CrystalGuiCore;

import java.lang.reflect.Method;

import net.minecraft.client.gui.GuiScreen;

/**
 * Hands a screen a key through its own {@code keyTyped}, which is protected: what a desktop on its own thread gives back
 * to whatever screen is up. Reflective, by the development name and then the SRG one.
 */
final class ScreenKeys {

    private static final String[] NAMES = {"keyTyped", "func_73869_a"};

    private static Method keyTyped;
    private static boolean failed;

    private ScreenKeys() {
    }

    /** {@code screen.keyTyped(character, key)}, with LWJGL 2's key code. Logs once if it cannot be reached. */
    static void type(GuiScreen screen, char character, int key) {
        Method method = method();
        if (method == null) return;
        try {
            method.invoke(screen, character, key);
        } catch (ReflectiveOperationException thrown) {
            CrystalGuiCore.LOGGER.warn("[cgui] could not give {} a key the desktop left", screen.getClass().getName(),
                    thrown);
        }
    }

    private static Method method() {
        if (keyTyped != null || failed) return keyTyped;
        for (String name : NAMES) {
            try {
                Method found = GuiScreen.class.getDeclaredMethod(name, char.class, int.class);
                found.setAccessible(true);
                keyTyped = found;
                return found;
            } catch (NoSuchMethodException tryNext) {
                // the other name
            }
        }
        failed = true;
        CrystalGuiCore.LOGGER.warn("[cgui] GuiScreen.keyTyped not found; keys the desktop leaves will not reach a screen");
        return null;
    }
}
