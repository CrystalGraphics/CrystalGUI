package com.crystalgui.mc.fabric;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

/**
 * Everything Fabric that runs on <b>both sides</b> — the common entry point, the {@link Network}
 * transport and the {@link Events} subscriptions.
 *
 * <p>Separate from {@link CrystalGUI1201Fabric} because a dedicated server runs this one and must
 * touch no client class: the workspace and the connection table are server-side. The client half of
 * {@code Events} is registered from there instead.</p>
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks.</p>
 */
public final class CrystalGUI1201FabricCommon implements ModInitializer {

    @Override
    public void onInitialize() {
        Lifecycle1201.bootstrap(Network.get());
        Events.registerCommon();
    }

    // -- Network ----------------------------------------------------------------

    /** The MC 1.20.1 Fabric transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s. */
    public static final class Network implements CgNetworkChannel {

        private static final ResourceLocation ID = new ResourceLocation(MODID, "wire");

        /** Fabric's custom-payload limit is ~1 MB; staying under it keeps one frame one packet. */
        private static final int MAX_FRAME_BYTES = 900_000;

        private static final Network INSTANCE = new Network();

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        private Network() {}

        public static Network get() {
            return INSTANCE;
        }

        /** The server half. Safe on a dedicated server; names no client class. */
        public static void registerServerReceiver() {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                // The tree is the frame thread's; the receiver runs on the netty thread.
                server.execute(() -> INSTANCE.inbound.accept(player, frame));
            });
        }

        /** The client half, called only from the client initialiser. */
        public static void registerClientReceiver() {
            ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                client.execute(() -> INSTANCE.inbound.accept(null, frame));
            });
        }

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            ClientPlayNetworking.send(ID, PacketByteBufs.create().writeByteArray(frame));
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            ServerPlayNetworking.send(serverPlayer, ID, PacketByteBufs.create().writeByteArray(frame));
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

    /** Fabric event subscription. Every body is one forward into {@link Lifecycle1201}. */
    static final class Events {

        private Events() {}

        /** Both sides. A dedicated server runs this and no client class may be touched from it. */
        static void registerCommon() {
            Network.registerServerReceiver();

            ServerLifecycleEvents.SERVER_STARTING.register(Lifecycle1201::serverStarting);
            ServerLifecycleEvents.SERVER_STARTED.register(Lifecycle1201::serverStarted);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> Lifecycle1201.serverStopping());
            ServerTickEvents.END_SERVER_TICK.register(server -> Lifecycle1201.serverTick());

            ServerPlayConnectionEvents.JOIN.register(
                    (handler, sender, server) -> Lifecycle1201.playerJoined(handler.getPlayer()));
            ServerPlayConnectionEvents.DISCONNECT.register(
                    (handler, server) -> Lifecycle1201.playerLeft(handler.getPlayer()));
        }

        static void registerClient() {
            Network.registerClientReceiver();

            Lifecycle1201.bootstrapClient();
            CgUiKeybinds1201.all().forEach(KeyBindingHelper::registerKeyBinding);
            ClientTickEvents.END_CLIENT_TICK.register(client -> Lifecycle1201.clientTick());

            ClientPlayConnectionEvents.JOIN.register(
                    (handler, sender, client) -> Lifecycle1201.clientConnected());
            ClientPlayConnectionEvents.DISCONNECT.register(
                    (handler, client) -> Lifecycle1201.clientDisconnected());

            // Pinned windows. ScreenOverlay decides; Fabric's allow* events cancel by returning false.
            HudRenderCallback.EVENT.register((graphics, tickDelta) -> Lifecycle1201.paintOverlay());

            ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
                ScreenEvents.afterRender(screen).register((s, g, mx, my, td) -> Lifecycle1201.paintOverlay());

                ScreenMouseEvents.allowMouseClick(screen).register(
                        (s, mx, my, button) -> !Lifecycle1201.offerMouse(button, true, 0f));
                ScreenMouseEvents.allowMouseRelease(screen).register(
                        (s, mx, my, button) -> !Lifecycle1201.offerMouse(button, false, 0f));
                ScreenMouseEvents.allowMouseScroll(screen).register(
                        (s, mx, my, hAmount, vAmount) -> !Lifecycle1201.offerMouse(-1, false, (float) vAmount));

                ScreenKeyboardEvents.allowKeyPress(screen).register(
                        (s, key, scancode, modifiers) -> !Lifecycle1201.offerKey(key, (char) 0, true));
            });
        }
    }
}
