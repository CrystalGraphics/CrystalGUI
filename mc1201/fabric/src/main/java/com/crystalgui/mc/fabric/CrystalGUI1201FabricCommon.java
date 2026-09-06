package com.crystalgui.mc.fabric;

import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.platform.Lifecycle1201;
import com.crystalgui.net.wire.CgNetworkChannel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;

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

            // Pinned windows. ScreenOverlay decides; the loader only forwards and honours the boolean.
            HudRenderCallback.EVENT.register((graphics, tickDelta) -> Lifecycle1201.paintHud());
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                    ScreenEvents.afterRender(screen).register(
                            (s, g, mx, my, td) -> Lifecycle1201.paintOverlay()));

            ClientLifecycleEvents.CLIENT_STARTED.register(
                    client -> Input.install(client.getWindow().getWindow()));
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
                            && Lifecycle1201.offerMouse(button, action == GLFW.GLFW_PRESS, 0f)) {
                        return;
                    }
                    if (prevButton != null) prevButton.invoke(win, button, action, mods);
                });

                GLFWKeyCallback prevKey = GLFW.glfwSetKeyCallback(window, null);
                GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
                    if (action != GLFW.GLFW_REPEAT
                            && Lifecycle1201.offerKey(key, (char) 0, action == GLFW.GLFW_PRESS)) {
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
                    if (Lifecycle1201.offerKey(0, (char) codepoint, true)) return;
                    if (prevChar != null) prevChar.invoke(win, codepoint, mods);
                });

                GLFWScrollCallback prevScroll = GLFW.glfwSetScrollCallback(window, null);
                GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                    if (Lifecycle1201.offerMouse(-1, false, (float) dy)) return;
                    if (prevScroll != null) prevScroll.invoke(win, dx, dy);
                });
            }
        }
    }
}
