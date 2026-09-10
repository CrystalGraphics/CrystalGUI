package com.crystalgui.mc.neoforge;

import java.util.function.BiConsumer;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgui.mc.shared.CrashVariant;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;

/**
 * Everything NeoForge — the mod entry point, its {@link Network} transport and its {@link Events}
 * subscriptions.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 */
@Mod(MODID)
public final class CrystalGUINeoForge {
    
    
    public CrystalGUINeoForge(IEventBus modBus) {
        // WHICH VARIANT, in the log rather than the crash report: NeoForge 20.4 exposes no crash
        // callable — CrashReportExtender is its own — so unlike Forge and 1.7.10 there is nothing to
        // register with, and `latest.log` is the file a report is attached with anyway. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.LABEL,
                CrashVariant.report(CrystalGUINeoForge.class));
        LifecycleCrystalGUI.bootstrap(Network.get());
        Events.register(modBus);
    }

    // -- Network ----------------------------------------------------------------

    /** The MC 1.20.4 NeoForge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
    public static final class Network implements CgNetworkChannel {

        private static final String VERSION = "1";
        private static final ResourceLocation ID = new ResourceLocation(MODID, "wire");

        /** Under the payload split threshold, so one frame stays one packet. */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        /** One payload carrying a frame. */
        public record Frame(byte[] bytes) implements CustomPacketPayload {

            public Frame(FriendlyByteBuf buf) {
                this(buf.readByteArray());
            }

            @Override
            public void write(FriendlyByteBuf buf) {
                buf.writeByteArray(bytes);
            }

            @Override
            public ResourceLocation id() {
                return ID;
            }
        }

        /** Wired to RegisterPayloadHandlerEvent on the mod bus. */
        public static void register(RegisterPayloadHandlerEvent event) {
            event.registrar(MODID)
                    .versioned(VERSION)
                    .play(ID, Frame::new, handler -> handler
                            .client(Network::receive)
                            .server(Network::receive));
        }

        private static void receive(Frame frame, PlayPayloadContext context) {
            // enqueueWork: the handler runs on the network thread and the tree is the frame thread's.
            context.workHandler().submitAsync(() -> {
                ServerPlayer sender = context.player().filter(p -> p instanceof ServerPlayer)
                        .map(p -> (ServerPlayer) p).orElse(null);
                INSTANCE.inbound.accept(sender, frame.bytes());
            });
        }

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            PacketDistributor.SERVER.noArg().send(new Frame(frame));
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            PacketDistributor.PLAYER.with(serverPlayer).send(new Frame(frame));
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

    /** NeoForge event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    static final class Events {

        private Events() {}

        static void register(IEventBus modBus) {
            modBus.addListener(Network::register);

            NeoForge.EVENT_BUS.addListener(Events::onServerStarting);
            NeoForge.EVENT_BUS.addListener(Events::onServerStarted);
            NeoForge.EVENT_BUS.addListener(Events::onServerStopping);
            NeoForge.EVENT_BUS.addListener(Events::onServerTick);
            NeoForge.EVENT_BUS.addListener(Events::onPlayerJoin);
            NeoForge.EVENT_BUS.addListener(Events::onPlayerLeave);

            if (FMLEnvironment.dist.isClient()) ClientBus.register(modBus);
        }

        private static void onServerStarting(ServerStartingEvent event) {
            LifecycleCrystalGUI.serverStarting(event.getServer());
        }

        private static void onServerStarted(ServerStartedEvent event) {
            LifecycleCrystalGUI.serverStarted(event.getServer());
        }

        private static void onServerStopping(ServerStoppingEvent event) {
            LifecycleCrystalGUI.serverStopping();
        }

        private static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.serverTick();
        }

        private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) LifecycleCrystalGUI.playerJoined(player);
        }

        private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) LifecycleCrystalGUI.playerLeft(player);
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

                NeoForge.EVENT_BUS.addListener(ClientBus::onRenderGui);
                NeoForge.EVENT_BUS.addListener(ClientBus::onScreenRender);
                NeoForge.EVENT_BUS.addListener(ClientBus::onMousePressed);
                NeoForge.EVENT_BUS.addListener(ClientBus::onMouseReleased);
                NeoForge.EVENT_BUS.addListener(ClientBus::onMouseScrolled);
                NeoForge.EVENT_BUS.addListener(ClientBus::onKeyPressed);
                NeoForge.EVENT_BUS.addListener(ClientBus::onKeyReleased);
                NeoForge.EVENT_BUS.addListener(ClientBus::onCharTyped);
            }

            private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
                LifecycleCrystalGUI.bootstrapClient();
                CgUiKeybinds.all().forEach(event::register);
            }

            private static void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.clientTick();
            }

            private static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
                LifecycleCrystalGUI.clientConnected();
            }

            private static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
                LifecycleCrystalGUI.clientDisconnected();
            }

            /**
             * ONCE a frame. RenderGuiOverlayEvent fires per vanilla overlay element, so painting from it
             * drew the whole compositor a dozen times a frame.
             */
            private static void onRenderGui(RenderGuiEvent.Post event) {
                LifecycleCrystalGUI.paintHud();
            }

            private static void onScreenRender(ScreenEvent.Render.Post event) {
                LifecycleCrystalGUI.paintOverlay();
            }

            private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(event.getButton(), true, 0f)) event.setCanceled(true);
            }

            private static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(event.getButton(), false, 0f)) event.setCanceled(true);
            }

            private static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
                if (LifecycleCrystalGUI.offerMouse(-1, false, (float) event.getScrollDeltaY())) event.setCanceled(true);
            }

            private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
                if (LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, true)) event.setCanceled(true);
            }

            private static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
                if (LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, false)) event.setCanceled(true);
            }

            private static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
                if (LifecycleCrystalGUI.offerKey(0, event.getCodePoint(), true)) event.setCanceled(true);
            }
        }
    }
}
