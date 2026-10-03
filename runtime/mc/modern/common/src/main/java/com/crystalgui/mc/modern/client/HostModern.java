package com.crystalgui.mc.modern.client;

import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.platform.input.CgSystemInput;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.mc.modern.net.WorkspaceHostModern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;

/**
 * How the modern tree answers the questions only a platform can. Nothing here decides anything.
 *
 * @see HostServices
 */
final class HostModern implements HostServices {

    /** Which desktop's record this is — per installation, not per world. @see HostServices#desktopId */
    private static final String DESKTOP_ID = "client";

    @Override
    public Path installationDirectory() {
        Minecraft mc = Minecraft.getInstance();
        // THE GAME DIRECTORY, not crystalgui/ inside it -- the engine adds that segment.
        return (mc == null ? new File(".") : mc.gameDirectory).toPath();
    }

    /**
     * The save directory in single-player, null on someone else's server.
     *
     * <p>{@code getSingleplayerServer()} is the whole test: it answers non-null exactly when this
     * client is also the server, which is when its own disk holds the world.</p>
     */
    @Override
    @Nullable
    public Path localWorldDirectory() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        return server == null ? null : WorkspaceHostModern.worldRoot(server);
    }

    /**
     * Device pixels per logical pixel, and <b>not</b> the player's GUI Scale.
     *
     * <p>It was that, and GUI Scale is the wrong input: it sizes 16px widgets and a bitmap font, so a
     * player who wants a readable inventory gets a desktop scaled to match and the two cannot both be
     * right.</p>
     */
    @Override
    public float uiScale() {
        return HostServices.DEFAULT_UI_SCALE;
    }

    @Override
    public int surfaceWidth() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || Windows.of(mc) == null ? 0 : Windows.of(mc).getWidth();
    }

    @Override
    public int surfaceHeight() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || Windows.of(mc) == null ? 0 : Windows.of(mc).getHeight();
    }

    @Override
    public String desktopId() {
        return DESKTOP_ID;
    }

    @Override
    @Nullable
    public CgProtocolConnection<Object> connection() {
        // Re-asked every frame, so a reconnect is a different object carrying the same workspace and
        // DesktopHost rebinds rather than rebuilds. Null means no server right now: supported.
        return Connections.client();
    }

    /** The game's language setting, {@code "ja_jp"} on 1.20.x. */
    @Override
    public Locale locale() {
        Minecraft mc = Minecraft.getInstance();
        return HostServices.gameLocale(mc == null ? null : mc.options.languageCode);
    }

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc == null ? null : ClientGame.screen(mc);
        if (screen instanceof CgUiScreen) ((CgUiScreen) screen).keyLeftByDesktop(key);
        else if (screen != null) CgUiInput.giveToScreen(screen, key);
    }

    /** Minecraft's own task queue from 1.14.3; before it the client thread is the one the desktop is framed on. */
    @Override
    @Nullable
    public HostThread.Binding clientThread() {
        //? if >=1.14.3 {
        return CLIENT_THREAD;
        //?} else {
        /*return null;
        *///?}
    }

    /** The integrated server's task queue from 1.14.3; before it there is none to reach. */
    @Override
    @Nullable
    public HostThread.Binding serverThread() {
        //? if >=1.14.3 {
        return SERVER_THREAD;
        //?} else {
        /*return null;
        *///?}
    }

    //? if >=1.14.3 {
    private static final HostThread.Binding CLIENT_THREAD = new HostThread.Binding() {
        @Override
        public void execute(Runnable work) {
            Minecraft.getInstance().execute(work);
        }

        @Override
        public boolean isCurrent() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isSameThread();
        }

        @Override
        public boolean isAvailable() {
            return Minecraft.getInstance() != null;
        }
    };

    private static final HostThread.Binding SERVER_THREAD = new HostThread.Binding() {
        @Override
        public void execute(Runnable work) {
            IntegratedServer server = integratedServer();
            if (server != null) server.execute(work);
        }

        @Override
        public boolean isCurrent() {
            IntegratedServer server = integratedServer();
            return server != null && server.isSameThread();
        }

        @Override
        public boolean isAvailable() {
            return integratedServer() != null;
        }
    };

    @Nullable
    private static IntegratedServer integratedServer() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getSingleplayerServer();
    }
    //?}
}
