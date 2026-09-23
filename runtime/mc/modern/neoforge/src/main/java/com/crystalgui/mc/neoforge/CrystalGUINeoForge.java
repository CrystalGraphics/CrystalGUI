package com.crystalgui.mc.neoforge;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.shared.CrashVariant;
import java.util.function.BiConsumer;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
//? if >=1.20.5 {
/*import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
*///?} elif >=1.20.4 {
/*import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;
*///?} else {
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.network.NetworkEvent;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.simple.SimpleChannel;
//?}

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;
import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything NeoForge — the mod entry point, its {@link Network} transport and its {@link Events}
 * subscriptions.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 *
 * <p><b>No {@code @Mod} here.</b> One jar carries a NeoForge variant per era and the scanner reads
 * every class in it, so two variants bearing the same annotation are two mods of one id. The single
 * annotated class is {@code NeoForgeBootstrap}, beside this one, which reads
 * {@code variants.json} and constructs the row matching the running Minecraft version.</p>
 */
public final class CrystalGUINeoForge implements VariantEntry {

    /** @param context the {@code IEventBus} NeoForge handed {@code NeoForgeBootstrap}. */
    @Override
    public void start(Object context) {
        IEventBus modBus = (IEventBus) context;
        // WHICH VARIANT, in the log rather than the crash report: NeoForge exposes no crash callable --
        // CrashReportExtender is its own -- so unlike Forge and 1.7.10 there is nothing to register
        // with, and `latest.log` is the file a report is attached with anyway. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.label(NAME),
                CrashVariant.report(CrystalGUINeoForge.class));
        LifecycleCrystalGUI.bootstrap(Network.get());
        Events.register(modBus);
    }

    // -- Network ----------------------------------------------------------------

    /**
     * The NeoForge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s.
     *
     * <p>Three payload APIs: 20.2-20.3's Forge-shaped {@code SimpleChannel}, 1.20.4's registrar keyed on
     * an id, and 1.20.5's typed payloads with a {@code StreamCodec}. NeoForge splits an oversized payload
     * itself on all three, so one frame may exceed vanilla's 32 KiB serverbound cap.</p>
     */
    public static final class Network implements CgNetworkChannel {

        private static final String VERSION = "1";
        private static final ResourceLocation ID = ResourceIds.of(MODID, "wire");

        /** Under the payload split threshold, so one frame stays one packet. */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        //? if >=1.20.5 {
        /*public record Frame(byte[] bytes) implements CustomPacketPayload {

            static final CustomPacketPayload.Type<Frame> TYPE = new CustomPacketPayload.Type<>(ID);
            static final StreamCodec<ByteBuf, Frame> CODEC = ByteBufCodecs.BYTE_ARRAY.map(Frame::new, Frame::bytes);

            @Override
            public CustomPacketPayload.Type<Frame> type() {
                return TYPE;
            }
        }

        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar(VERSION).playBidirectional(Frame.TYPE, Frame.CODEC, Network::receive);
        }

        private static void receive(Frame frame, IPayloadContext context) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.player() instanceof ServerPlayer p ? p : null;
                INSTANCE.inbound.accept(sender, frame.bytes());
            });
        }
        *///?} elif >=1.20.4 {
        /*// One payload carrying a frame.
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

        // Wired to RegisterPayloadHandlerEvent on the mod bus.
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
        *///?} else {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(ID)
                .networkProtocolVersion(() -> VERSION)
                .clientAcceptedVersions(VERSION::equals)
                .serverAcceptedVersions(VERSION::equals)
                .simpleChannel();

        /** Called once from the entry point, before anything can send: 20.2 has no registration event. */
        public static void register() {
            CHANNEL.registerMessage(0, byte[].class,
                    (frame, buf) -> buf.writeByteArray(frame),
                    FriendlyByteBuf::readByteArray,
                    Network::receive);
        }

        private static void receive(byte[] frame, NetworkEvent.Context ctx) {
            // enqueueWork: the handler runs on the network thread, and the tree is the frame thread's.
            ctx.enqueueWork(() -> INSTANCE.inbound.accept(ctx.getSender(), frame));
            ctx.setPacketHandled(true);
        }
        //?}

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            //? if >=1.20.5 {
            /*PacketDistributor.sendToServer(new Frame(frame));
            *///?} elif >=1.20.4 {
            /*PacketDistributor.SERVER.noArg().send(new Frame(frame));
            *///?} else {
            CHANNEL.sendToServer(frame);
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            //? if >=1.20.5 {
            /*PacketDistributor.sendToPlayer(serverPlayer, new Frame(frame));
            *///?} elif >=1.20.4 {
            /*PacketDistributor.PLAYER.with(serverPlayer).send(new Frame(frame));
            *///?} else {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), frame);
            //?}
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
            //? if >=1.20.4 {
            /*modBus.addListener(Network::register);
            *///?} else {
            Network.register();
            //?}

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

        // 1.20.5 split the tick events by phase into classes of their own.
        //? if >=1.20.5 {
        /*private static void onServerTick(ServerTickEvent.Post event) {
            LifecycleCrystalGUI.serverTick();
        }
        *///?} else {
        private static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.serverTick();
        }
        //?}

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

            //? if >=1.20.5 {
            /*private static void onClientTick(ClientTickEvent.Post event) {
                LifecycleCrystalGUI.clientTick();
            }
            *///?} else {
            private static void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.clientTick();
            }
            //?}

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
