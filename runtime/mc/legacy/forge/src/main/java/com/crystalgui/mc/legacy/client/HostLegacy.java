package com.crystalgui.mc.legacy.client;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.host.HostServices;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * How Forge 1.8-1.12.2 hands a key back to its game; everything else the desktop asks is CrystalGraphics' ({@link HostServices}).
 * The client thread is the one the desktop is framed on.
 */
final class HostLegacy implements HostServices {

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof CgUiScreen) ((CgUiScreen) screen).keyLeftByDesktop(key);
        // keyTyped is a press, with its character beside its key; a release has no handler to reach.
        else if (screen != null && key.pressed()) ScreenKeys.type(screen, key.character(), key.key());
    }

    @Override
    public void showDesktop() {
        CgUiScreen.openDesktop();
    }
}
