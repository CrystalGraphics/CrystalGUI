package com.crystalgui.mc.fabric;

import com.crystalgui.mc.client.CgUiHud1201;
import com.crystalgui.mc.client.CgUiKeybinds1201;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

/** Registration only: the keys live in {@code CgUiKeybinds1201}, the screen in {@code CgUiScreen1201}. */
final class CgUiFabricEvents {

    private CgUiFabricEvents() {}

    static void register() {
        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_EDITOR);
        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_DESKTOP);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CgUiKeybinds1201.tick());

        // Pinned windows over the HUD, and over a foreign screen. ScreenOverlay decides; these forward
        // and honour its answer -- Fabric's allow* events cancel by returning false.
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> CgUiHud1201.paint());

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterRender(screen).register((s, g, mx, my, td) -> CgUiHud1201.paint());

            ScreenMouseEvents.allowMouseClick(screen).register(
                    (s, mx, my, button) -> !CgUiHud1201.offerMouse(button, true, 0f));
            ScreenMouseEvents.allowMouseRelease(screen).register(
                    (s, mx, my, button) -> !CgUiHud1201.offerMouse(button, false, 0f));
            ScreenMouseEvents.allowMouseScroll(screen).register(
                    (s, mx, my, hAmount, vAmount) -> !CgUiHud1201.offerMouse(-1, false, (float) vAmount));

            ScreenKeyboardEvents.allowKeyPress(screen).register(
                    (s, key, scancode, modifiers) -> !CgUiHud1201.offerKey(key, (char) 0, true));
        });
    }
}
