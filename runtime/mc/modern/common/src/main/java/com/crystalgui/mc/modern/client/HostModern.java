package com.crystalgui.mc.modern.client;

import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import com.crystalgraphics.mc.modern.platform.HostKeys;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.host.HostServices;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
*///?}

/**
 * How the modern tree hands a key back to its game; everything else the desktop asks is CrystalGraphics' ({@link HostServices}).
 */
final class HostModern implements HostServices {

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc == null ? null : CgMinecraftClient.screen(mc);
        if (screen instanceof CgUiScreen) ((CgUiScreen) screen).keyLeftByDesktop(key);
        else if (screen != null) giveToScreen(screen, key);
    }

    @Override
    public void showDesktop() {
        CgUiScreen.openDesktop();
    }

    /**
     * Gives {@code screen} a key through its own handlers, as if the desktop had never seen it: a key code to
     * {@code keyPressed}/{@code keyReleased}, a character to {@code charTyped}. No modifiers ride along.
     */
    private static void giveToScreen(Screen screen, CgSystemInput.Keyboard.Event key) {
        if (key.character() != 0) {
            //? if >=26.1 {
            /*screen.charTyped(new CharacterEvent(key.character()));
            *///?} elif >=1.21.9 {
            /*screen.charTyped(new CharacterEvent(key.character(), 0));
            *///?} else {
            screen.charTyped(key.character(), 0);
            //?}
            return;
        }
        int code = HostKeys.key(key.key());
        //? if >=1.21.9 {
        /*KeyEvent event = new KeyEvent(code, 0, 0);
        if (key.pressed()) screen.keyPressed(event);
        else screen.keyReleased(event);
        *///?} else {
        if (key.pressed()) screen.keyPressed(code, 0, 0);
        else screen.keyReleased(code, 0, 0);
        //?}
    }
}
