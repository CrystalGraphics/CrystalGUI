package com.crystalgui.mc.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.cursor.CursorService;
import com.crystalgui.mc.platform.service.CursorService1201;
import com.crystalgui.mc.client.CgUiHud1201;
import com.crystalgui.mc.example.MachineExample1201;
import com.crystalgui.mc.example.MachineExampleClient1201;
import com.crystalgui.mc.client.CgUiKeybinds1201;
import com.crystalgui.mc.client.CgUiAutoTest1201;
import com.crystalgui.mc.client.ClientProbe1201;
import com.crystalgui.mc.net.Connections1201;
import com.crystalgui.mc.net.ServerSmoke1201;
import com.crystalgui.mc.net.WorkspaceHost1201;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.text.syntax.LanguageRegistry;

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
        CgPlatform.provide(CgNetworkChannel.SERVICE, channel);
        // Before the connections: a contributor binds only to connections opened after it registers.
        // Without it a client has no ClientWindows, and every requestOpen is refused locally.
        WindowProtocol.register();
        Connections1201.register();
        MachineExample1201.registerCommon();
    }

    /**
     * Client init, once. Everything client-side that has to be registered is registered from here, so a
     * loader never enumerates it -- including the example's key, which is why
     * {@link CgUiKeybinds1201#all()} must be read AFTER this runs.
     */
    public static void bootstrapClient() {
        // THE POINTER IS THIS PLATFORM'S TO DRESS, and it belongs to the process rather than to any one
        // screen -- so a cursor resolves the same whether the desktop has ever been opened or not. GLFW
        // has the whole standard set, so this one is a mapping table; the engine resolves a keyword and asks.
        CgPlatform.provide(CursorService.SERVICE, new CursorService1201());
        // WHAT THIS DEPLOYMENT CAN DO WITH A SOURCE FILE, read from core and naming no language
        // class: the language stack is a separate mod since J8, and this host must work without it.
        // LanguageRegistry is core's, so asking costs nothing and compiles with the jar absent.
        announceLanguageTier();
        MachineExampleClient1201.registerClient();
    }

    /**
     * Says which tier of the language stack this deployment has, without naming it.
     *
     * <p>{@code LanguageRegistry} is {@code core}'s enginless tier, so this compiles and runs with the
     * language mod absent — which is the whole point since J8 made it a separate jar. An empty
     * contributor list IS the absent case; the language mod announces its own arrival.</p>
     *
     * <p>Worth a line either way: every tier opens a file perfectly and the configurations are
     * indistinguishable on screen, so nothing else separates "this pack ships no grammars" from "a
     * contributor failed to load".</p>
     */
    private static void announceLanguageTier() {
        List<String> contributors = LanguageRegistry.contributors();
        if (contributors.isEmpty()) {
            CrystalGuiCore.LOGGER.info("[cgui-1201] no language stack installed -- source files colour "
                    + "from core's built-in lexers and are not analysed. Install crystalgui_language for "
                    + "grammars, analysis and scripting.");
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-1201] language contributors: {}", contributors);
        }
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    public static void serverStarting(MinecraftServer server) {
        WorkspaceHost1201.setServer(server);
        WorkspaceHost1201.register();
    }

    /**
     * The server is genuinely up. Late enough that a mod which failed to load has already taken the
     * process down, which is why the smoke check runs here rather than at {@link #serverStarting}.
     */
    public static void serverStarted(MinecraftServer server) {
        if (ServerSmoke1201.enabled()) ServerSmoke1201.run(server);
    }

    public static void serverStopping() {
        Connections1201.closeAll("server stopping");
        WorkspaceHost1201.setServer(null);
    }

    public static void serverTick() {
        Connections1201.onServerTick();
        WorkspaceHost1201.tick(SERVER_TICK_SECONDS);
        run(serverTickHooks);
    }

    /**
     * Content rides the platform's tick rather than subscribing a loader event of its own -- otherwise
     * a mod's tick is wired three times and only one copy ever gets debugged.
     */
    public static void onServerTick(Runnable hook) {
        serverTickHooks.add(hook);
    }

    public static void onClientTick(Runnable hook) {
        clientTickHooks.add(hook);
    }

    private static final List<Runnable> serverTickHooks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> clientTickHooks = new CopyOnWriteArrayList<>();

    /** One hook's failure must not stop the others, or a demo takes the platform down with it. */
    private static void run(List<Runnable> hooks) {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("[cgui-1201] tick hook failed", failed);
            }
        }
    }

    public static void playerJoined(@Nullable ServerPlayer player) {
        if (player != null) Connections1201.onPlayerJoin(player);
    }

    public static void playerLeft(@Nullable ServerPlayer player) {
        if (player != null) Connections1201.onPlayerLeave(player);
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    public static void clientTick() {
        CgUiAutoTest1201.tick();
        ClientProbe1201.tick();
        CgUiKeybinds1201.tick();
        Connections1201.onClientTick();
        run(clientTickHooks);
    }

    public static void clientConnected() {
        Connections1201.onClientConnected();
    }

    public static void clientDisconnected() {
        Connections1201.onClientDisconnected();
    }

    // ── Pinned windows. ScreenOverlay in core/ makes every decision; these only carry it. ────────

    public static void paintOverlay() {
        CgUiHud1201.paintOverScreen();
    }

    /** The HUD arm, from a hook that fires ONCE a frame -- not once per vanilla overlay element. */
    public static void paintHud() {
        CgUiHud1201.paintHud();
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
