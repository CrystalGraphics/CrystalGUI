package com.crystalgui.mc.modern.client;

import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.host.HostServices;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * How the modern tree hands a key back to its game; everything else the desktop asks is CrystalGraphics' ({@link HostServices}).
 */
final class HostModern implements HostServices {

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc == null ? null : CgMinecraftClient.screen(mc);
        if (screen instanceof CgUiScreen) ((CgUiScreen) screen).keyLeftByDesktop(key);
        else if (screen != null) CgUiInput.giveToScreen(screen, key);
    }
}
