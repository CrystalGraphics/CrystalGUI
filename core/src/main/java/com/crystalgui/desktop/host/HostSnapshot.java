package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgraphics.platform.service.CgServers;

import java.nio.file.Path;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * What the game answers the desktop, as the render thread last read it, for a document that may run on another thread.
 * The game's services read its own state, which only its render thread may; {@link #refresh} copies them there, once
 * per host entry, and the document reads the copy.
 */
final class HostSnapshot {

    private final Path installation;
    private volatile Path localWorld;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    @Nullable
    private volatile CgProtocolConnection<Object> connection;
    private volatile Locale locale;

    HostSnapshot() {
        // Never changes under a running game
        this.installation = CgGameDirectory.game();
        refresh();
    }

    /** Reads every changing answer. Render thread. */
    void refresh() {
        localWorld = CgServers.running() ? CgGameDirectory.world() : null;
        surfaceWidth = CgHostWindow.width();
        surfaceHeight = CgHostWindow.height();
        connection = CgNetwork.client();
        locale = CgI18n.locale();
    }

    /** This installation's directory, not the {@code crystalgui/} inside it: the engine owns every segment below. */
    Path installationDirectory() {
        return installation;
    }

    /** The world's save directory while this client is also the server, else null. */
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

    /** The live connection, or null with none. */
    @Nullable
    CgProtocolConnection<Object> connection() {
        return connection;
    }

    Locale locale() {
        return locale;
    }
}
