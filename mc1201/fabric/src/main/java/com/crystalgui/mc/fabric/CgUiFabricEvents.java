package com.crystalgui.mc.fabric;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Fabric event subscription. Every body is one forward into {@link Lifecycle1201}. */
final class CgUiFabricEvents {

    private CgUiFabricEvents() {}

    /** Both sides. A dedicated server runs this and no client class may be touched from it. */
    static void registerCommon() {
        NetworkChannel1201.registerServerReceiver();

        ServerLifecycleEvents.SERVER_STARTING.register(Lifecycle1201::serverStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Lifecycle1201.serverStopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> Lifecycle1201.serverTick());

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> Lifecycle1201.playerJoined(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> Lifecycle1201.playerLeft(handler.getPlayer()));
    }

    static void registerClient() {
        NetworkChannel1201.registerClientReceiver();

        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_EDITOR);
        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_DESKTOP);
        ClientTickEvents.END_CLIENT_TICK.register(client -> Lifecycle1201.clientTick());

        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> Lifecycle1201.clientConnected());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> Lifecycle1201.clientDisconnected());

        // Pinned windows. ScreenOverlay decides; Fabric's allow* events cancel by returning false.
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> Lifecycle1201.paintOverlay());

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterRender(screen).register((s, g, mx, my, td) -> Lifecycle1201.paintOverlay());

            ScreenMouseEvents.allowMouseClick(screen).register(
                    (s, mx, my, button) -> !Lifecycle1201.offerMouse(button, true, 0f));
            ScreenMouseEvents.allowMouseRelease(screen).register(
                    (s, mx, my, button) -> !Lifecycle1201.offerMouse(button, false, 0f));
            ScreenMouseEvents.allowMouseScroll(screen).register(
                    (s, mx, my, hAmount, vAmount) -> !Lifecycle1201.offerMouse(-1, false, (float) vAmount));

            ScreenKeyboardEvents.allowKeyPress(screen).register(
                    (s, key, scancode, modifiers) -> !Lifecycle1201.offerKey(key, (char) 0, true));
        });
    }
}
