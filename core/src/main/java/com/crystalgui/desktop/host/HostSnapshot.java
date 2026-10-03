package com.crystalgui.desktop.host;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.HostThread;

import java.nio.file.Path;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * The host's answers as the render thread last read them, for a document that may run on another thread. A host's
 * services read the game's own state, which only its render thread may; {@link #refresh} copies them there, once per
 * host entry, and the document reads the copy.
 */
final class HostSnapshot implements HostServices {

    private final HostServices host;
    private volatile Path localWorld;
    private volatile float uiScale;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    @Nullable
    private volatile CgProtocolConnection<Object> connection;
    private volatile Locale locale;

    HostSnapshot(HostServices host) {
        this.host = host;
        refresh();
    }

    /** Reads every changing answer from the host. Render thread. */
    void refresh() {
        localWorld = host.localWorldDirectory();
        uiScale = host.uiScale();
        surfaceWidth = host.surfaceWidth();
        surfaceHeight = host.surfaceHeight();
        connection = host.connection();
        locale = host.locale();
    }

    @Override
    public Path installationDirectory() {
        return host.installationDirectory();
    }

    @Override
    public Path localWorldDirectory() {
        return localWorld;
    }

    @Override
    public float uiScale() {
        return uiScale;
    }

    @Override
    public int surfaceWidth() {
        return surfaceWidth;
    }

    @Override
    public int surfaceHeight() {
        return surfaceHeight;
    }

    @Override
    public String desktopId() {
        return host.desktopId();
    }

    @Override
    public CgProtocolConnection<Object> connection() {
        return connection;
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public void reinjectKey(CgSystemInput.Keyboard.Event key) {
        host.reinjectKey(key);
    }

    @Override
    @Nullable
    public HostThread.Binding clientThread() {
        return host.clientThread();
    }

    @Override
    @Nullable
    public HostThread.Binding serverThread() {
        return host.serverThread();
    }
}
