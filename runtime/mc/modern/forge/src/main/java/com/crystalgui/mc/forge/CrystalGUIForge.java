package com.crystalgui.mc.forge;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;
import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Forge — the mod entry point, its {@link Network} transport and its {@link Events}
 * subscriptions.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 */
@Mod(MODID)
public final class CrystalGUIForge {
    
    public CrystalGUIForge() {
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgui.mc.forge.common.* is the only thing
        // that says which one ran -- and asking a reporter to work that out is asking them to know how
        // the jar is built. @see CrashVariant
        CrashReportCallables.registerCrashCallable(CrashVariant.label(NAME),
                () -> CrashVariant.report(CrystalGUIForge.class));
        LifecycleCrystalGUI.bootstrap(Network.register());
    }

    // -- Network ----------------------------------------------------------------

    /** The MC 1.20.1 Forge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
    public static final class Network implements CgNetworkChannel {

        private static final String VERSION = "1";

        /**
         * Forge splits a payload across partials above ~1 MB. Staying under it keeps one frame one packet,
         * which is what the multiplexer above assumes when it sizes its chunks.
         */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(MODID, "wire"))
                .networkProtocolVersion(() -> VERSION)
                .clientAcceptedVersions(VERSION::equals)
                .serverAcceptedVersions(VERSION::equals)
                .simpleChannel();

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        /** Called once from the mod constructor, before anything can send. */
        public static Network register() {
            CHANNEL.registerMessage(0, byte[].class,
                    (frame, buf) -> buf.writeByteArray(frame),
                    FriendlyByteBuf::readByteArray,
                    Network::receive);
            
            return INSTANCE;
        }

        private static void receive(byte[] frame, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            // enqueueWork: the handler runs on the network thread, and the tree is the frame thread's.
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();   // null on the client
                INSTANCE.inbound.accept(sender, frame);
            });
            ctx.setPacketHandled(true);
        }

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            CHANNEL.sendToServer(frame);
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer)) return;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), frame);
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
            inbound = handler == null ? (sender, frame) -> { } : handler;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    // -- Events -----------------------------------------------------------------

    /** Forge event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    public static final class Events {

        private Events() {}

        @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
        public static final class ModBus {
            private ModBus() {}

            @SubscribeEvent
            public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
                LifecycleCrystalGUI.bootstrapClient();
                CgUiKeybinds.all().forEach(event::register);
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
                LifecycleCrystalGUI.serverStarting(event.getServer());
            }

            @SubscribeEvent
            public static void onServerStarted(ServerStartedEvent event) {
                LifecycleCrystalGUI.serverStarted(event.getServer());
            }

            @SubscribeEvent
            public static void onServerStopping(ServerStoppingEvent event) {
                LifecycleCrystalGUI.serverStopping();
            }

            @SubscribeEvent
            public static void onServerTick(TickEvent.ServerTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.serverTick();
            }

            @SubscribeEvent
            public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
                if (event.getEntity() instanceof ServerPlayer player) LifecycleCrystalGUI.playerJoined(player);
            }

            @SubscribeEvent
            public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
                if (event.getEntity() instanceof ServerPlayer player) LifecycleCrystalGUI.playerLeft(player);
            }
        }

        @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
        public static final class ClientBus {
            private ClientBus() {}

            @SubscribeEvent
            public static void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.clientTick();
            }

            @SubscribeEvent
            public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
                LifecycleCrystalGUI.clientConnected();
            }

            @SubscribeEvent
            public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
                LifecycleCrystalGUI.clientDisconnected();
            }

            /**
             * ONCE a frame. RenderGuiOverlayEvent fires per vanilla overlay element -- hotbar, crosshair,
             * boss bar, chat and a dozen more -- so painting from it laid out and drew the whole
             * compositor fifteen times a frame and put the game at ten fps.
             */
            @SubscribeEvent
            public static void onRenderGui(RenderGuiEvent.Post event) {
                LifecycleCrystalGUI.paintHud();
            }

            @SubscribeEvent
            public static void onScreenRender(ScreenEvent.Render.Post event) {
                LifecycleCrystalGUI.paintOverlay();
            }

            @SubscribeEvent
            public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
            }

            @SubscribeEvent
            public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
            }

            @SubscribeEvent
            public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(-1, false, (float) event.getScrollDelta())) event.setCanceled(true);
            }

            @SubscribeEvent
            public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
                if (LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
            }

            @SubscribeEvent
            public static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
                if (LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, false)) event.setCanceled(true);
            }

            @SubscribeEvent
            public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
                if (LifecycleCrystalGUI.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
            }
        }
    }
}
