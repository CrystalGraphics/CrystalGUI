package com.crystalgui.mc.legacy.client;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.mc.legacy.Game;
import com.crystalgui.mc.legacy.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;

/**
 * How Forge 1.8–1.12.2 answers the questions only a platform can. Nothing here decides anything.
 *
 * @see HostServices
 */
final class HostLegacy implements HostServices {

    /** Which desktop's record this is — per installation, not per world. @see HostServices#desktopId */
    private static final String DESKTOP_ID = "client";

    @Override
    public Path installationDirectory() {
        // THE GAME DIRECTORY, not crystalgui/ inside it -- the engine adds that segment, along with
        // workspace-config/, cache/ and projects/ below it.
        File gameDir = ClientGame.gameDir();
        return (gameDir == null ? new File(".") : gameDir).toPath();
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
        MinecraftServer server = Game.server();
        if (server == null || server.isDedicatedServer()) return null;
        File directory = Game.overworldDirectory();
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

    /** The game's language setting, {@code "ja_JP"} before 1.11, {@code "ja_jp"} from it. */
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

    /** Legacy Forge frames the desktop on the client thread itself. */
    @Override
    @Nullable
    public HostThread.Binding clientThread() {
        return null;
    }

    @Override
    public HostThread.Binding serverThread() {
        return SERVER_THREAD;
    }

    /** The integrated server's own task queue, {@code IThreadListener} since 1.8. */
    private static final HostThread.Binding SERVER_THREAD = new HostThread.Binding() {
        @Override
        public void execute(Runnable work) {
            IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
            if (server != null) server.addScheduledTask(work);
        }

        @Override
        public boolean isCurrent() {
            IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
            return server != null && server.isCallingFromMinecraftThread();
        }

        @Override
        public boolean isAvailable() {
            return Minecraft.getMinecraft().getIntegratedServer() != null;
        }
    };
}
