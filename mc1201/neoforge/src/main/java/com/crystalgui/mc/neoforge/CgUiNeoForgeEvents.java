package com.crystalgui.mc.neoforge;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge event subscription. Every body is one forward into {@link Lifecycle1201}. */
final class CgUiNeoForgeEvents {

    private CgUiNeoForgeEvents() {}

    static void register(IEventBus modBus) {
        modBus.addListener(NetworkChannel1201::register);

        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(CgUiNeoForgeEvents::onPlayerLeave);

        if (FMLEnvironment.dist.isClient()) ClientBus.register(modBus);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Lifecycle1201.serverStarting(event.getServer());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        Lifecycle1201.serverStarted(event.getServer());
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

    /**
     * The client listeners, in a class a dedicated server never loads.
     *
     * <p><b>A guard around the CALL is not enough.</b> A method reference is an {@code invokedynamic} in
     * the method that writes it, so its parameter type resolves when that method RUNS, whatever branch
     * it sits in -- {@code RuntimeDistCleaner} then refuses {@code ScreenEvent} with
     * {@code BootstrapMethodError: Attempted to load class ... for invalid dist DEDICATED_SERVER} and
     * the mod never constructs. Only moving the references into another class defers it, because that
     * class is loaded on first use. Forge's twin gets this for free from
     * {@code @EventBusSubscriber(Dist.CLIENT)}; NeoForge subscribes by hand, so it must be said.</p>
     *
     * <p>The "client-only guard one level too high" defect {@code CgUiServerSmoke} was written for,
     * found by its 1.20.x twin on the first NeoForge boot.</p>
     */
    private static final class ClientBus {

        private ClientBus() {}

        static void register(IEventBus modBus) {
            modBus.addListener(ClientBus::onRegisterKeyMappings);

            NeoForge.EVENT_BUS.addListener(ClientBus::onClientTick);
            NeoForge.EVENT_BUS.addListener(ClientBus::onClientLoggedIn);
            NeoForge.EVENT_BUS.addListener(ClientBus::onClientLoggedOut);

            NeoForge.EVENT_BUS.addListener(ClientBus::onRenderGuiOverlay);
            NeoForge.EVENT_BUS.addListener(ClientBus::onScreenRender);
            NeoForge.EVENT_BUS.addListener(ClientBus::onMousePressed);
            NeoForge.EVENT_BUS.addListener(ClientBus::onMouseReleased);
            NeoForge.EVENT_BUS.addListener(ClientBus::onMouseScrolled);
            NeoForge.EVENT_BUS.addListener(ClientBus::onKeyPressed);
            NeoForge.EVENT_BUS.addListener(ClientBus::onCharTyped);
        }

        private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            Lifecycle1201.bootstrapClient();
            CgUiKeybinds1201.all().forEach(event::register);
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
}
