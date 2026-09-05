package com.crystalgui.mc.neoforge;

import com.crystalgui.mc.client.CgUiHud1201;
import com.crystalgui.mc.client.CgUiKeybinds1201;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Registration only: the keys live in {@code CgUiKeybinds1201}, the screen in {@code CgUiScreen1201}. */
final class CgUiNeoForgeEvents {

    private CgUiNeoForgeEvents() {}

    static void register(IEventBus modBus) {
        modBus.addListener(CgUiNeoForgeEvents::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onClientTick);

        // Pinned windows over the HUD and over a foreign screen. ScreenOverlay decides; this forwards.
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onRenderGuiOverlay);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onScreenRender);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMousePressed);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMouseReleased);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMouseScrolled);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onCharTyped);
    }

    private static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        CgUiHud1201.paint();
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        CgUiHud1201.paint();
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (CgUiHud1201.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
    }

    private static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (CgUiHud1201.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
    }

    private static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (CgUiHud1201.offerMouse(-1, false, (float) event.getScrollDeltaY())) event.setCanceled(true);
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (CgUiHud1201.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
    }

    private static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (CgUiHud1201.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
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
