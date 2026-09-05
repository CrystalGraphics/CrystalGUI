package com.crystalgui.mc.neoforge;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge event subscription. Every body is one forward into {@link Lifecycle1201}. */
final class CgUiNeoForgeEvents {

    private CgUiNeoForgeEvents() {}

    static void register(IEventBus modBus) {
        modBus.addListener(CgUiNeoForgeEvents::onRegisterKeyMappings);
        modBus.addListener(NetworkChannel1201::register);

        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onPlayerLeave);

        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onClientLoggedIn);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onClientLoggedOut);

        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onRenderGuiOverlay);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onScreenRender);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMousePressed);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMouseReleased);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onMouseScrolled);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onCharTyped);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CgUiKeybinds1201.OPEN_EDITOR);
        event.register(CgUiKeybinds1201.OPEN_DESKTOP);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Lifecycle1201.serverStarting(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        Lifecycle1201.serverStopping();
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Lifecycle1201.serverTick();
    }

    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Lifecycle1201.playerJoined(player);
    }

    private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Lifecycle1201.playerLeft(player);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Lifecycle1201.clientTick();
    }

    private static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Lifecycle1201.clientConnected();
    }

    private static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Lifecycle1201.clientDisconnected();
    }

    private static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        Lifecycle1201.paintOverlay();
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        Lifecycle1201.paintOverlay();
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (Lifecycle1201.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
    }

    private static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (Lifecycle1201.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
    }

    private static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (Lifecycle1201.offerMouse(-1, false, (float) event.getScrollDeltaY())) event.setCanceled(true);
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (Lifecycle1201.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
    }

    private static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (Lifecycle1201.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
    }
}
