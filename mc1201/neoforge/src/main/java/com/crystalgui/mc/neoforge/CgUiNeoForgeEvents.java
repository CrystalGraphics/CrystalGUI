package com.crystalgui.mc.neoforge;

import com.crystalgui.mc.client.CgUiKeybinds1201;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Registration only: the keys live in {@code CgUiKeybinds1201}, the screen in {@code CgUiScreen1201}. */
final class CgUiNeoForgeEvents {

    private CgUiNeoForgeEvents() {}

    static void register(IEventBus modBus) {
        modBus.addListener(CgUiNeoForgeEvents::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onClientTick);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CgUiKeybinds1201.OPEN_EDITOR);
        event.register(CgUiKeybinds1201.OPEN_DESKTOP);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        CgUiKeybinds1201.tick();
    }
}
