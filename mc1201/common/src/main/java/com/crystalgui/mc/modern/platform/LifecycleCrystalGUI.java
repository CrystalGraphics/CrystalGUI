package com.crystalgui.mc.modern.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.cursor.CursorService;
import com.crystalgui.mc.modern.platform.service.GlfwCursorService;

import com.crystalgui.mc.modern.client.CgUiHud;
import com.crystalgui.mc.modern.example.MachineExample;
import com.crystalgui.mc.modern.example.MachineExampleClient;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.client.CgUiAutoTest;
import com.crystalgui.mc.modern.client.ClientProbe;
import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.mc.modern.net.ServerSmoke;
import com.crystalgui.mc.modern.net.WorkspaceHost;
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
public final class LifecycleCrystalGUI {

    private LifecycleCrystalGUI() {}

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
        Connections.register();
        MachineExample.registerCommon();
    }

    /**
     * Client init, once. Everything client-side that has to be registered is registered from here, so a
     * loader never enumerates it -- including the example's key, which is why
     * {@link CgUiKeybinds#all()} must be read AFTER this runs.
     */
    public static void bootstrapClient() {
        // THE POINTER IS THIS PLATFORM'S TO DRESS, and it belongs to the process rather than to any one
        // screen -- so a cursor resolves the same whether the desktop has ever been opened or not. GLFW
        // has the whole standard set, so this one is a mapping table; the engine resolves a keyword and asks.
        CgPlatform.provide(CursorService.SERVICE, new GlfwCursorService());
        MachineExampleClient.registerClient();
    }

    /**
     * Says which tier of the language stack this deployment has, without naming it.
     *
     * <p>{@code LanguageRegistry} is {@code core}'s engineless tier, so this compiles and runs with the
     * language mod absent — which is the whole point since J8 made it a separate jar. An empty
     * contributor list IS the absent case; the language mod announces its own arrival.</p>
     *
     * <p>Worth a line either way: every tier opens a file perfectly and the configurations are
     * indistinguishable on screen, so nothing else separates "this pack ships no grammars" from "a
     * contributor failed to load".</p>
     *
     * <p><b>ON A TICK, NOT AT BOOTSTRAP, AND THAT IS NOT TIDINESS.</b> Every read of
     * {@code LanguageRegistry} bootstraps it, and bootstrapping constructs the engines — each of which
     * captures whether a {@code ScriptService} is registered <em>at that moment</em> and keeps the
     * answer for the life of the process. The language mod is ordered AFTER this one, so a read from
     * this method's original home in {@code bootstrapClient} ran first, found no service, and turned
     * the live tier off permanently: every script reported {@code net.minecraft.client.Minecraft}
     * unresolvable while the byte source behind it was perfectly healthy. A tick is after every mod's
     * setup, which is the only ordering that is true on all three loaders.</p>
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
        WorkspaceHost.setServer(server);
        WorkspaceHost.register();
    }

    /**
     * The server is genuinely up. Late enough that a mod which failed to load has already taken the
     * process down, which is why the smoke check runs here rather than at {@link #serverStarting}.
     */
    public static void serverStarted(MinecraftServer server) {
        if (ServerSmoke.enabled()) ServerSmoke.run(server);
    }

    public static void serverStopping() {
        Connections.closeAll("server stopping");
        WorkspaceHost.setServer(null);
    }

    public static void serverTick() {
        Connections.onServerTick();
        WorkspaceHost.tick(SERVER_TICK_SECONDS);
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

    /** @see #announceLanguageTier */
    private static boolean languageTierAnnounced;

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
        if (player != null) Connections.onPlayerJoin(player);
    }

    public static void playerLeft(@Nullable ServerPlayer player) {
        if (player != null) Connections.onPlayerLeave(player);
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    public static void clientTick() {
        if (!languageTierAnnounced) {
            languageTierAnnounced = true;
            announceLanguageTier();
        }
        CgUiAutoTest.tick();
        ClientProbe.tick();
        CgUiKeybinds.tick();
        Connections.onClientTick();
        run(clientTickHooks);
    }

    public static void clientConnected() {
        Connections.onClientConnected();
    }

    public static void clientDisconnected() {
        Connections.onClientDisconnected();
    }

    // ── Pinned windows. ScreenOverlay in core/ makes every decision; these only carry it. ────────

    public static void paintOverlay() {
        CgUiHud.paintOverScreen();
    }

    /** The HUD arm, from a hook that fires ONCE a frame -- not once per vanilla overlay element. */
    public static void paintHud() {
        CgUiHud.paintHud();
    }

    /** @return whether the desktop consumed it and the foreign screen must not see it */
    public static boolean offerMouse(int button, boolean pressed, float wheel) {
        return CgUiHud.offerMouse(button, pressed, wheel);
    }

    /** @return whether the desktop consumed it */
    public static boolean offerKey(int glfwKey, char typed, boolean pressed) {
        return CgUiHud.offerKey(glfwKey, typed, pressed);
    }
}
