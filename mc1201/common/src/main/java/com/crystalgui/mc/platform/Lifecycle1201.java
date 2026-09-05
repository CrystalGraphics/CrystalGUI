package com.crystalgui.mc.platform;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.mc.client.CgUiHud1201;
import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.net.Connections1201;
import com.crystalgui.mc.net.WorkspaceHost1201;
import com.crystalgui.net.wire.CgNetworkChannel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The one class a 1.20.x loader talks to.</b> One method per lifecycle moment; a loader subscribes
 * its events and forwards, and supplies nothing but its own transport.
 *
 * <p>The point is that a moment happens in ONE place. Wired per loader, "what a server tick does" is
 * written three times and drifts -- and the drift shows up as a feature that works on one loader and
 * not another, which is the least debuggable shape this module can produce. Same reason
 * {@code ScreenOverlay} keeps the input arbitration in {@code core/}.</p>
 */
public final class Lifecycle1201 {

    private Lifecycle1201() {}

    /** One server tick, at 20 Hz. */
    private static final float SERVER_TICK_SECONDS = 1f / 20f;

    /**
     * Mod init. The transport is the one thing only a loader can build -- three different networking
     * APIs -- so it is passed in; everything else is the same on all three.
     */
    public static void bootstrap(CgNetworkChannel channel) {
        CgPlatformService1201.getInstance();
        CgPlatform.provide(CgNetworkChannel.SERVICE, channel);
        Connections1201.register();
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    public static void serverStarting(MinecraftServer server) {
        WorkspaceHost1201.setServer(server);
        WorkspaceHost1201.register();
    }

    public static void serverStopping() {
        Connections1201.closeAll("server stopping");
        WorkspaceHost1201.setServer(null);
    }

    public static void serverTick() {
        Connections1201.onServerTick();
        WorkspaceHost1201.tick(SERVER_TICK_SECONDS);
    }

    public static void playerJoined(@Nullable ServerPlayer player) {
        if (player != null) Connections1201.onPlayerJoin(player);
    }

    public static void playerLeft(@Nullable ServerPlayer player) {
        if (player != null) Connections1201.onPlayerLeave(player);
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    public static void clientTick() {
        CgUiKeybinds1201.tick();
        Connections1201.onClientTick();
    }

    public static void clientConnected() {
        Connections1201.onClientConnected();
    }

    public static void clientDisconnected() {
        Connections1201.onClientDisconnected();
    }

    // ── Pinned windows. ScreenOverlay in core/ makes every decision; these only carry it. ────────

    public static void paintOverlay() {
        CgUiHud1201.paint();
    }

    /** @return whether the desktop consumed it and the foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float wheel) {
        return CgUiHud1201.offerMouse(button, pressed, wheel);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int glfwKey, char typed, boolean pressed) {
        return CgUiHud1201.offerKey(glfwKey, typed, pressed);
    }
}
