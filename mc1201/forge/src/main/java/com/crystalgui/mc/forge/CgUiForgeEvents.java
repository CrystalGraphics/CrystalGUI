package com.crystalgui.mc.forge;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/** Forge event subscription. Every body is one forward into {@link Lifecycle1201}. */
public final class CgUiForgeEvents {

    private CgUiForgeEvents() {}

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            Lifecycle1201.bootstrapClient();
            CgUiKeybinds1201.all().forEach(event::register);
        }
    }

    /**
     * Both sides, so no {@code Dist}: a dedicated server has to open connections and tick the workspace,
     * and a client-only subscriber would leave it with neither -- silently.
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class CommonBus {
        private CommonBus() {}

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            Lifecycle1201.serverStarting(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            Lifecycle1201.serverStopping();
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) Lifecycle1201.serverTick();
        }

        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) Lifecycle1201.playerJoined(player);
        }

        @SubscribeEvent
        public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) Lifecycle1201.playerLeft(player);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientBus {
        private ClientBus() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) Lifecycle1201.clientTick();
        }

        @SubscribeEvent
        public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            Lifecycle1201.clientConnected();
        }

        @SubscribeEvent
        public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            Lifecycle1201.clientDisconnected();
        }

        @SubscribeEvent
        public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
            Lifecycle1201.paintOverlay();
        }

        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Post event) {
            Lifecycle1201.paintOverlay();
        }

        @SubscribeEvent
        public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
            if (Lifecycle1201.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
            if (Lifecycle1201.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
            if (Lifecycle1201.offerMouse(-1, false, (float) event.getScrollDelta())) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
            if (Lifecycle1201.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
            if (Lifecycle1201.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
        }
    }
}
