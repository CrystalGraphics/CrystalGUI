package com.crystalgui.mc.fabric;

import com.crystalgui.mc.client.CgUiKeybinds1201;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/** Registration only: the keys live in {@code CgUiKeybinds1201}, the screen in {@code CgUiScreen1201}. */
final class CgUiFabricEvents {

    private CgUiFabricEvents() {}

    static void register() {
        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_EDITOR);
        KeyBindingHelper.registerKeyBinding(CgUiKeybinds1201.OPEN_DESKTOP);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CgUiKeybinds1201.tick());
    }
}
