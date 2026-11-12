package com.crystalgui.screen;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.platform.service.CgServers;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The game's answers the desktop needs, read once a host frame on the render thread and read from any thread after:
 * where the installation and the local world are, the surface's size, the client's connection and the player's
 * language. The document never asks the game itself.
 */
final class GameState {

    private final Path installation;
    @Nullable
    private volatile Path localWorld;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    @Nullable
    private volatile CgProtocolConnection<Object> connection;
    private volatile Locale locale;

    GameState() {
        // Never changes under a running game
        this.installation = CgGameDirectory.game();
        read();
    }

    /** Takes this frame's answers. Render thread. */
    void read() {
        localWorld = CgServers.running() ? CgGameDirectory.world() : null;
        // As the host passed it into this frame: the engine's own record of the window.
        surfaceWidth = CgGraphicsLifecycle.getCurrentWidth();
        surfaceHeight = CgGraphicsLifecycle.getCurrentHeight();
        connection = CgNetwork.client();
        locale = CgI18n.locale();
    }

    Path installationDirectory() {
        return installation;
    }

    /** The single-player world's directory, or null on a remote server and on the title screen. */
    @Nullable
    Path localWorldDirectory() {
        return localWorld;
    }

    int surfaceWidth() {
        return surfaceWidth;
    }

    int surfaceHeight() {
        return surfaceHeight;
    }

    /** The client's connection to a server, or null when it has none. */
    @Nullable
    CgProtocolConnection<Object> connection() {
        return connection;
    }

    Locale locale() {
        return locale;
    }
}
