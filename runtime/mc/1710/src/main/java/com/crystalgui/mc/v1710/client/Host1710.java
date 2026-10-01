package com.crystalgui.mc.v1710.client;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.mc.v1710.net.CgUiWorkspaceHost;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.mc.v1710.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * How MC 1.7.10 answers the questions only a platform can. Nothing here decides anything.
 *
 * @see HostServices
 */
final class Host1710 implements HostServices {

    /** Which desktop's record this is — per installation, not per world. @see HostServices#desktopId */
    private static final String DESKTOP_ID = "client";

    @Override
    public Path installationDirectory() {
        Minecraft mc = Minecraft.getMinecraft();
        // THE GAME DIRECTORY, not crystalgui/ inside it -- the engine adds that segment, along with
        // workspace-config/, cache/ and projects/ below it.
        return (mc == null || mc.mcDataDir == null ? new File(".") : mc.mcDataDir).toPath();
    }

    /**
     * The save directory in single-player, null on someone else's server.
     *
     * <p>An integrated server is one this client is running, so its world is on this disk. A client
     * connected outward has no {@code MinecraftServer} at all, which is the null.</p>
     */
    @Override
    @Nullable
    public Path localWorldDirectory() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.isDedicatedServer()) return null;
        WorldServer[] worlds = server.worldServers;
        if (worlds == null || worlds.length == 0 || worlds[0] == null) return null;
        File directory = worlds[0].getSaveHandler().getWorldDirectory();
        return directory == null ? null : directory.toPath();
    }

    @Override
    public float uiScale() {
        return HostServices.DEFAULT_UI_SCALE;
    }

    /** Raw device pixels — never {@code GuiScreen.width}, which is already divided by MC's GUI scale. */
    @Override
    public int surfaceWidth() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? 0 : mc.displayWidth;
    }

    /** @see #surfaceWidth() */
    @Override
    public int surfaceHeight() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? 0 : mc.displayHeight;
    }

    @Override
    public String desktopId() {
        return DESKTOP_ID;
    }

    @Override
    @Nullable
    public ProtocolConnection<Object> connection() {
        return CgUiConnections.client();
    }

    /** The game's language setting, {@code "ja_JP"} on 1.7.10. */
    @Override
    public Locale locale() {
        Minecraft mc = Minecraft.getMinecraft();
        return HostServices.gameLocale(
                mc == null || mc.gameSettings == null ? null : mc.gameSettings.language);
    }

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof CgUiScreen) ((CgUiScreen) screen).keyLeftByDesktop(key);
        // keyTyped is a press, with its character beside its key; a release has no handler to reach.
        else if (screen != null && key.pressed()) ScreenKeys.type(screen, key.character(), key.key());
    }

    /** 1.7.10 frames the desktop on the client thread itself. */
    @Override
    @Nullable
    public HostThread.Binding clientThread() {
        return null;
    }

    /** 1.7.10's server has no task queue of its own: {@link CgUiWorkspaceHost} drains this one on its tick. */
    @Override
    public HostThread.Binding serverThread() {
        return SERVER_THREAD;
    }

    private static final HostThread.Binding SERVER_THREAD = new HostThread.Binding() {
        @Override
        public void execute(Runnable work) {
            CgUiWorkspaceHost.SERVER_TASKS.execute(work);
        }

        @Override
        public boolean isCurrent() {
            return CgUiWorkspaceHost.SERVER_TASKS.isCurrent();
        }

        @Override
        public boolean isAvailable() {
            return Minecraft.getMinecraft().getIntegratedServer() != null;
        }
    };
}
