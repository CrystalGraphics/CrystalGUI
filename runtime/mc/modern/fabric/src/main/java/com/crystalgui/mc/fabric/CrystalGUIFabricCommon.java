package com.crystalgui.mc.fabric;

import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >=1.15 {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//?}
//? if >=1.16 {
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
//?}
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
/*import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
*///?} else {
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//?}

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.util.function.BiConsumer;

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;
import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Fabric that runs on <b>both sides</b> — the common entry point, the {@link Network}
 * transport and the {@link Events} subscriptions.
 *
 * <p>Separate from {@link CrystalGUIFabric} because a dedicated server runs this one and must
 * touch no client class: the workspace and the connection table are server-side. The client half of
 * {@code Events} is registered from there instead.</p>
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks.</p>
 */
public final class CrystalGUIFabricCommon implements VariantEntry {

    /** @param context null — Fabric hands an entry point nothing; {@code FabricBootstrap} passes it on. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the log rather than the crash report: Fabric Loader exposes no crash
        // callable, so unlike Forge and 1.7.10 there is nothing to register with. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.label(NAME),
                CrashVariant.report(CrystalGUIFabricCommon.class));
        LifecycleCrystalGUI.bootstrap(Network.get());
        Events.registerCommon();
    }

    // -- Network ----------------------------------------------------------------

    /**
     * The Fabric transport: bytes in, bytes out. Framing and routing are {@code net.wire}'s.
     *
     * <p>Two payload APIs: 1.20.1's channel keyed on an id with a raw buffer, and 1.20.5's typed payloads,
     * registered in {@code PayloadTypeRegistry} with a {@code StreamCodec} before any receiver.</p>
     */
    public static final class Network implements CgNetworkChannel {

        private static final ResourceLocation ID = ResourceIds.of(MODID, "wire");

        // 1.20.1: Fabric's custom-payload limit is ~1 MB, so one frame is one packet. 1.20.5+: nothing
        // here has shown Fabric lifting vanilla's 32 767-byte serverbound cap, and Fabric does not split,
        // so a frame is kept under it -- net.wire already splits a message into as many frames as needed.
        //? if >=1.20.5 {
        /*private static final int MAX_FRAME_BYTES = 32_000;
        *///?} else {
        private static final int MAX_FRAME_BYTES = 900_000;
        //?}

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
        *///?}

        /**
         * The server half, and on 1.20.5+ the payload type both directions share. Safe on a dedicated
         * server; names no client class. The tree is the frame thread's and a receiver runs on the netty
         * thread, so each hands its frame across.
         */
        public static void registerServerReceiver() {
            // Through the player below 1.21.9: Context.server() is fabric-api 0.99+, and 1.20.5's stops at
            // 0.97. 1.21.9 took getServer() off the player, and every fabric-api for it has server().
            //? if >=1.21.9 {
            /*PayloadTypeRegistry.playC2S().register(Frame.TYPE, Frame.CODEC);
            PayloadTypeRegistry.playS2C().register(Frame.TYPE, Frame.CODEC);
            ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.server().execute(() -> INSTANCE.inbound.accept(context.player(), frame.bytes())));
            *///?} elif >=1.20.5 {
            /*PayloadTypeRegistry.playC2S().register(Frame.TYPE, Frame.CODEC);
            PayloadTypeRegistry.playS2C().register(Frame.TYPE, Frame.CODEC);
            ServerPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.player().getServer().execute(() -> INSTANCE.inbound.accept(context.player(), frame.bytes())));
            *///?} else {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                server.execute(() -> INSTANCE.inbound.accept(player, frame));
            });
            //?}
        }

        /** The client half, called only from the client initialiser. */
        public static void registerClientReceiver() {
            //? if >=1.20.5 {
            /*ClientPlayNetworking.registerGlobalReceiver(Frame.TYPE, (frame, context) ->
                    context.client().execute(() -> INSTANCE.inbound.accept(null, frame.bytes())));
            *///?} else {
            ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, responder) -> {
                byte[] frame = buf.readByteArray();
                client.execute(() -> INSTANCE.inbound.accept(null, frame));
            });
            //?}
        }

        @Override
        public int maxFrameBytes() {
            return MAX_FRAME_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
            //? if >=1.20.5 {
            /*ClientPlayNetworking.send(new Frame(frame));
            *///?} else {
            ClientPlayNetworking.send(ID, PacketByteBufs.create().writeByteArray(frame));
            //?}
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            //? if >=1.20.5 {
            /*ServerPlayNetworking.send(serverPlayer, new Frame(frame));
            *///?} else {
            ServerPlayNetworking.send(serverPlayer, ID, PacketByteBufs.create().writeByteArray(frame));
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

    /** Fabric event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    static final class Events {

        private Events() {}

        /** Both sides. A dedicated server runs this and no client class may be touched from it. */
        static void registerCommon() {
            Network.registerServerReceiver();

            ServerLifecycleEvents.SERVER_STARTING.register(LifecycleCrystalGUI::serverStarting);
            ServerLifecycleEvents.SERVER_STARTED.register(LifecycleCrystalGUI::serverStarted);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> LifecycleCrystalGUI.serverStopping());
            ServerTickEvents.END_SERVER_TICK.register(server -> LifecycleCrystalGUI.serverTick());

            // getPlayer() arrived in 1.17; before it the handler exposes the field.
            //? if >=1.17 {
            ServerPlayConnectionEvents.JOIN.register(
                    (handler, sender, server) -> LifecycleCrystalGUI.playerJoined(handler.getPlayer()));
            ServerPlayConnectionEvents.DISCONNECT.register(
                    (handler, server) -> LifecycleCrystalGUI.playerLeft(handler.getPlayer()));
            //?} else {
            /*ServerPlayConnectionEvents.JOIN.register(
                    (handler, sender, server) -> LifecycleCrystalGUI.playerJoined(handler.player));
            ServerPlayConnectionEvents.DISCONNECT.register(
                    (handler, server) -> LifecycleCrystalGUI.playerLeft(handler.player));
            *///?}
        }

        static void registerClient() {
            Network.registerClientReceiver();

            LifecycleCrystalGUI.bootstrapClient();
            CgUiKeybinds.all().forEach(KeyBindingHelper::registerKeyBinding);
            ClientTickEvents.END_CLIENT_TICK.register(client -> LifecycleCrystalGUI.clientTick());

            ClientPlayConnectionEvents.JOIN.register(
                    (handler, sender, client) -> LifecycleCrystalGUI.clientConnected());
            ClientPlayConnectionEvents.DISCONNECT.register(
                    (handler, client) -> LifecycleCrystalGUI.clientDisconnected());

            // Pinned windows. ScreenOverlay decides; the loader only forwards and honours the boolean.
            // Fabric API for 1.15 hands the HUD callback the tick delta alone; 1.14's has none, and the
            // HUD is a node mixin there. @see com.crystalgui.mc.fabric.mixin.HudHook
            //? if >=1.16 {
            HudRenderCallback.EVENT.register((graphics, tickDelta) -> LifecycleCrystalGUI.paintHud());
            //?} elif >=1.15 {
            /*HudRenderCallback.EVENT.register(tickDelta -> LifecycleCrystalGUI.paintHud());
            *///?}
            // Fabric API for 1.15 has no screen events, so pinned windows do not draw over another
            // mod's screen there; the desktop, the HUD and input are unaffected.
            //? if >=1.16 {
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                    ScreenEvents.afterRender(screen).register(
                            (s, g, mx, my, td) -> LifecycleCrystalGUI.paintOverlay()));
            //?}

            ClientLifecycleEvents.CLIENT_STARTED.register(
                    client -> Input.install(Windows.handle(client)));
        }

        /**
         * Input, on GLFW's own callbacks rather than Fabric's screen events.
         *
         * <p><b>Fabric API has neither half of what this needs.</b> {@code ScreenKeyboardEvents} has no
         * character event, so typing could not reach a pinned window at all, and there is no non-screen
         * input event, so HUD mode heard nothing -- the two gaps Forge fills with {@code CharacterTyped}
         * and {@code InputEvent}. Chaining GLFW is the pattern this project already uses for scroll,
         * which Fabric also has no event for, and it is preferred to a mixin.</p>
         *
         * <p>One path for the HUD and for a screen, which is what mc1710's own drain is. Not forwarding
         * to the previous callback IS the cancellation: Minecraft never sees what we consumed.</p>
         */
        private static final class Input {

            private Input() {}

            static void install(long window) {
                // Read back by setting null, because GLFW hands the previous callback to the SETTER --
                // there is no getter, and Minecraft's own must keep running for everything we decline.
                GLFWMouseButtonCallback prevButton = GLFW.glfwSetMouseButtonCallback(window, null);
                GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
                    if (action != GLFW.GLFW_REPEAT
                            && LifecycleCrystalGUI.offerMouse(button, action == GLFW.GLFW_PRESS, 0f)) {
                        return;
                    }
                    if (prevButton != null) prevButton.invoke(win, button, action, mods);
                });

                GLFWKeyCallback prevKey = GLFW.glfwSetKeyCallback(window, null);
                GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
                    if (action != GLFW.GLFW_REPEAT
                            && LifecycleCrystalGUI.offerKey(key, (char) 0, action == GLFW.GLFW_PRESS)) {
                        return;
                    }
                    if (prevKey != null) prevKey.invoke(win, key, scancode, action, mods);
                });

                // CHAR_MODS, not CHAR. Minecraft installs its character handler on the mods variant
                // (InputConstants.setupKeyboardCallbacks) and GLFW fires BOTH for one keystroke, so
                // hooking CHAR puts us beside its handler rather than in front of it: declining to
                // forward suppresses nothing and the character lands in chat and in the editor at once.
                GLFWCharModsCallback prevChar = GLFW.glfwSetCharModsCallback(window, null);
                GLFW.glfwSetCharModsCallback(window, (win, codepoint, mods) -> {
                    if (LifecycleCrystalGUI.offerKey(0, (char) codepoint, true)) return;
                    if (prevChar != null) prevChar.invoke(win, codepoint, mods);
                });

                GLFWScrollCallback prevScroll = GLFW.glfwSetScrollCallback(window, null);
                GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                    if (LifecycleCrystalGUI.offerMouse(-1, false, (float) dy)) return;
                    if (prevScroll != null) prevScroll.invoke(win, dx, dy);
                });
            }
        }
    }
}
