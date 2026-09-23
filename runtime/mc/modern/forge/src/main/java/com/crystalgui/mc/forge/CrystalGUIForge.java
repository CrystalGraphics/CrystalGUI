package com.crystalgui.mc.forge;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//? if >=1.21.8 {
/*import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
*///?} elif >=1.21.6 {
/*// Forge 56-57 have no HUD event.
*///?} elif >=1.20.6 {
/*import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
*///?} else {
import net.minecraftforge.client.event.RenderGuiEvent;
//?}
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
//? if >=1.21.6 {
/*import net.minecraftforge.eventbus.api.bus.BusGroup;
*///?} else {
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
//?}
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.PacketDistributor;
//? if >=1.20.2 {
/*import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
*///?} else {
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
//?}

import java.util.function.BiConsumer;
//? if <1.20.2 {
import java.util.function.Supplier;
//?}

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;
import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Forge — the mod entry point, its {@link Network} transport and its {@link Events}
 * subscriptions.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 *
 * <p><b>No {@code @Mod} and no {@code @EventBusSubscriber} here.</b> One jar carries a Forge variant
 * per era, and Forge's scanner reads every class in it — two variants bearing the same annotation
 * are two mods of one id, which it refuses to load rather than choosing between. The single
 * annotated class is {@code ForgeBootstrap}, beside this one; it reads
 * {@code variants.json}, picks the row for the running Minecraft version, and constructs this.
 * Subscriptions that were annotations are {@link Events#register} calls now.</p>
 */
public final class CrystalGUIForge implements VariantEntry {

    /** @param context Forge's {@link FMLJavaModLoadingContext}, from the bootstrapper. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgui.mc.forge.common.* is the only thing
        // that says which one ran -- and asking a reporter to work that out is asking them to know how
        // the jar is built. @see CrashVariant
        CrashReportCallables.registerCrashCallable(CrashVariant.label(NAME),
                () -> CrashVariant.report(CrystalGUIForge.class));
        LifecycleCrystalGUI.bootstrap(Network.register());
        Events.register((FMLJavaModLoadingContext) context);
    }

    // -- Network ----------------------------------------------------------------

    /** The Forge transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
    public static final class Network implements CgNetworkChannel {

        //? if >=1.20.2 {
        /*// Forge 48+ rewrote networking and no payload split is measured there, so a frame stays under
        // vanilla's 32767-byte serverbound cap.
        private static final int MAX_FRAME_BYTES = 32_000;

        private static final SimpleChannel CHANNEL = ChannelBuilder
                .named(ResourceIds.of(MODID, "wire"))
                .networkProtocolVersion(1)
                .simpleChannel();
        *///?} else {
        private static final String VERSION = "1";

        /**
         * Forge splits a payload across partials above ~1 MB. Staying under it keeps one frame one packet,
         * which is what the multiplexer above assumes when it sizes its chunks.
         */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(ResourceIds.of(MODID, "wire"))
                .networkProtocolVersion(() -> VERSION)
                .clientAcceptedVersions(VERSION::equals)
                .serverAcceptedVersions(VERSION::equals)
                .simpleChannel();
        //?}

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        /** Called once from the mod entry point, before anything can send. */
        public static Network register() {
            //? if >=1.20.2 {
            /*// consumerMainThread: the tree is the frame thread's. getSender() is null on the client.
            CHANNEL.messageBuilder(byte[].class, 0)
                    .encoder((frame, buf) -> buf.writeByteArray(frame))
                    .decoder(buf -> buf.readByteArray())
                    .consumerMainThread((frame, ctx) -> INSTANCE.inbound.accept(ctx.getSender(), frame))
                    .add();
            *///?} else {
            CHANNEL.registerMessage(0, byte[].class,
                    (frame, buf) -> buf.writeByteArray(frame),
                    FriendlyByteBuf::readByteArray,
                    Network::receive);
            //?}
            //? if >=1.20.6 {
            /*CHANNEL.build();
            *///?}

            return INSTANCE;
        }

        //? if <1.20.2 {
        private static void receive(byte[] frame, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            // enqueueWork: the handler runs on the network thread, and the tree is the frame thread's.
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();   // null on the client
                INSTANCE.inbound.accept(sender, frame);
            });
            ctx.setPacketHandled(true);
        }
        //?}

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            //? if >=1.20.2 {
            /*CHANNEL.send(frame, PacketDistributor.SERVER.noArg());
            *///?} else {
            CHANNEL.sendToServer(frame);
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer)) return;
            //? if >=1.20.2 {
            /*CHANNEL.send(frame, PacketDistributor.PLAYER.with((ServerPlayer) player));
            *///?} else {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), frame);
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

    /** Forge event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    public static final class Events {

        private Events() {}

        /**
         * Both buses, from the entry point rather than from an annotation.
         *
         * <p>No {@code Dist} on the common half: a dedicated server has to open connections and tick
         * the workspace, and a client-only subscriber would leave it with neither — silently.</p>
         */
        public static void register(FMLJavaModLoadingContext context) {
            // Forge 56's EventBus 7: every event carries its own bus, ticks come as Pre and Post, and a
            // mod-bus event hands out one bus per mod's bus group.
            //? if >=1.21.6 {
            /*ServerStartingEvent.BUS.addListener(Events::onServerStarting);
            ServerStartedEvent.BUS.addListener(Events::onServerStarted);
            ServerStoppingEvent.BUS.addListener(Events::onServerStopping);
            TickEvent.ServerTickEvent.Post.BUS.addListener(event -> LifecycleCrystalGUI.serverTick());
            PlayerEvent.PlayerLoggedInEvent.BUS.addListener(Events::onPlayerJoin);
            PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(Events::onPlayerLeave);
            BusGroup modBus = context.getModBusGroup();
            *///?} else {
            IEventBus forgeBus = MinecraftForge.EVENT_BUS;
            forgeBus.addListener(Events::onServerStarting);
            forgeBus.addListener(Events::onServerStarted);
            forgeBus.addListener(Events::onServerStopping);
            forgeBus.addListener(Events::onServerTick);
            forgeBus.addListener(Events::onPlayerJoin);
            forgeBus.addListener(Events::onPlayerLeave);
            IEventBus modBus = context.getModEventBus();
            //?}

            // A SEPARATE CLASS, not a branch inside this one: naming a client-only event type in a
            // method of `Events` would resolve it when a dedicated server links this class.
            if (FMLEnvironment.dist == Dist.CLIENT) ClientBus.register(modBus);
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

        //? if <1.21.6 {
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

        /** Client-only, and a class of its own so a dedicated server never links one of these types. */
        public static final class ClientBus {

            private ClientBus() {}

            // A cancelling listener returns whether it consumed the event: Forge 56+ takes that as a
            // Predicate, and older Forge is told through setCanceled.
            //? if >=1.21.6 {
            /*static void register(BusGroup modBus) {
                RegisterKeyMappingsEvent.getBus(modBus).addListener(ClientBus::onRegisterKeyMappings);
                registerHud(modBus);
                TickEvent.ClientTickEvent.Post.BUS.addListener(event -> LifecycleCrystalGUI.clientTick());
                ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(ClientBus::onClientLoggedIn);
                ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(ClientBus::onClientLoggedOut);
                ScreenEvent.Render.Post.BUS.addListener(ClientBus::onScreenRender);
                ScreenEvent.MouseButtonPressed.Pre.BUS.addListener(ClientBus::onMousePressed);
                ScreenEvent.MouseButtonReleased.Pre.BUS.addListener(ClientBus::onMouseReleased);
                ScreenEvent.MouseScrolled.Pre.BUS.addListener(ClientBus::onMouseScrolled);
                ScreenEvent.KeyPressed.Pre.BUS.addListener(ClientBus::onKeyPressed);
                ScreenEvent.KeyReleased.Pre.BUS.addListener(ClientBus::onKeyReleased);
                ScreenEvent.CharacterTyped.Pre.BUS.addListener(ClientBus::onCharTyped);
            }
            *///?} else {
            static void register(IEventBus modBus) {
                IEventBus forgeBus = MinecraftForge.EVENT_BUS;
                modBus.addListener(ClientBus::onRegisterKeyMappings);
                registerHud(modBus);
                forgeBus.addListener(ClientBus::onClientTick);
                forgeBus.addListener(ClientBus::onClientLoggedIn);
                forgeBus.addListener(ClientBus::onClientLoggedOut);
                forgeBus.addListener(ClientBus::onScreenRender);
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonPressed.Pre.class,
                        e -> { if (onMousePressed(e)) e.setCanceled(true); });
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseButtonReleased.Pre.class,
                        e -> { if (onMouseReleased(e)) e.setCanceled(true); });
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.MouseScrolled.Pre.class,
                        e -> { if (onMouseScrolled(e)) e.setCanceled(true); });
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyPressed.Pre.class,
                        e -> { if (onKeyPressed(e)) e.setCanceled(true); });
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.KeyReleased.Pre.class,
                        e -> { if (onKeyReleased(e)) e.setCanceled(true); });
                forgeBus.addListener(EventPriority.NORMAL, false, ScreenEvent.CharacterTyped.Pre.class,
                        e -> { if (onCharTyped(e)) e.setCanceled(true); });
            }

            private static void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) LifecycleCrystalGUI.clientTick();
            }
            //?}

            // The HUD: a layer where Forge offers one. Forge 56-57 (1.21.6-1.21.7) offer none, and a
            // node mixin paints it there. @see com.crystalgui.mc.forge.mixin.HudHook
            //? if >=1.21.8 {
            /*private static void registerHud(BusGroup modBus) {
                AddGuiOverlayLayersEvent.getBus(modBus).addListener(ClientBus::onAddGuiLayers);
            }
            *///?} elif >=1.21.6 {
            /*private static void registerHud(BusGroup modBus) {
            }
            *///?} elif >=1.20.6 {
            /*private static void registerHud(IEventBus modBus) {
                modBus.addListener(ClientBus::onAddGuiLayers);
            }
            *///?} else {
            private static void registerHud(IEventBus modBus) {
                MinecraftForge.EVENT_BUS.addListener(ClientBus::onRenderGui);
            }
            //?}

            private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
                LifecycleCrystalGUI.bootstrapClient();
                CgUiKeybinds.all().forEach(event::register);
            }

            private static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
                LifecycleCrystalGUI.clientConnected();
            }

            private static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
                LifecycleCrystalGUI.clientDisconnected();
            }

            /**
             * ONCE a frame. RenderGuiOverlayEvent fires per vanilla overlay element -- hotbar, crosshair,
             * boss bar, chat and a dozen more -- so painting from it laid out and drew the whole
             * compositor fifteen times a frame and put the game at ten fps.
             */
            //? if >=1.21.8 {
            /*private static void onAddGuiLayers(AddGuiOverlayLayersEvent event) {
                event.getLayeredDraw().add(ResourceIds.of(MODID, "hud"), (graphics, partialTick) -> LifecycleCrystalGUI.paintHud());
            }
            *///?} elif >=1.21.6 {
            /*// Forge 56-57 have no HUD event: a node mixin paints it.
            *///?} elif >=1.20.6 {
            /*// Forge 50 dropped RenderGuiEvent for vanilla's layered HUD: one layer, added last, so on top.
            private static void onAddGuiLayers(AddGuiOverlayLayersEvent event) {
                event.getLayeredDraw().add(ResourceIds.of(MODID, "hud"), (graphics, partialTick) -> LifecycleCrystalGUI.paintHud());
            }
            *///?} else {
            private static void onRenderGui(RenderGuiEvent.Post event) {
                LifecycleCrystalGUI.paintHud();
            }
            //?}

            private static void onScreenRender(ScreenEvent.Render.Post event) {
                LifecycleCrystalGUI.paintOverlay();
            }

            private static boolean onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
                return LifecycleCrystalGUI.offerMouse(event.getButton(), true, 0f);
            }

            private static boolean onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
                return LifecycleCrystalGUI.offerMouse(event.getButton(), false, 0f);
            }

            private static boolean onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
                //? if >=1.20.2 {
                /*return LifecycleCrystalGUI.offerMouse(-1, false, (float) event.getDeltaY());
                *///?} else {
                return LifecycleCrystalGUI.offerMouse(-1, false, (float) event.getScrollDelta());
                //?}
            }

            private static boolean onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
                return LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, true);
            }

            private static boolean onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
                return LifecycleCrystalGUI.offerKey(event.getKeyCode(), (char) 0, false);
            }

            private static boolean onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
                return LifecycleCrystalGUI.offerKey(0, event.getCodePoint(), true);
            }
        }
    }
}
