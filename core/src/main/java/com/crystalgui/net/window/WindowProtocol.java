package com.crystalgui.net.window;

import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;

/**
 * Wires the window lifecycle onto every connection — <b>the one call that turns this on</b>.
 *
 * <p>Called once, at mod init, beside {@code CgUiWorkspaceHost.register()} and before connections are
 * opened. After that a mod never thinks about connections again: it calls
 * {@link ServerWindows#of(ProtocolConnection)}{@code .open(window)} when it has a window to show, and
 * {@link ClientWindows#register} once to say what it does about a window type locally.</p>
 *
 * <h3>Which side gets which host is decided by the peer</h3>
 *
 * <p>{@code connection.peer()} is the platform's handle for who is on the other end, and it is
 * {@code null} exactly on a client — where there is one peer and it does not need naming. So the same
 * contributor installs the server host on a server connection and the client host on a client one, and
 * a single-player process ends up with one of each on two different connections, which is what it
 * genuinely has. {@code CgUiWorkspaceHost.bindWorkspace} reads the same field for the same reason.</p>
 *
 * <h3>The generic seam, and why an unchecked cast is sound here</h3>
 *
 * <p>{@code Protocols.Contributor.bind} is generic over the encoded representation, and the host layer
 * is written against {@code Object} — because making {@link ServerWindow} generic would put a type
 * parameter in every mod's class declaration and every handler signature to serve a case no wire in
 * this engine has.</p>
 *
 * <p>The cast is safe for a reason worth stating rather than assuming: <b>every {@code StateMap} the
 * host layer builds takes its {@code DynamicOps} from {@code connection.ops()}</b>, never from a
 * hardcoded {@code PlainOps.INSTANCE}. Erasure means a {@code ProtocolConnection<JsonElement>} viewed
 * as {@code ProtocolConnection<Object>} still hands back its own ops, so values are encoded in the
 * representation the codec on the way out expects. It is the same cast {@code CgUiWorkspaceHost}
 * already makes, and the discipline it depends on is the one {@code ServerUiSession} already
 * follows.</p>
 */
public final class WindowProtocol {

    private static boolean registered;

    private WindowProtocol() {
    }

    /** Contributes the window lifecycle to every connection opened after this. Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Protocols.contribute("ui", new Protocols.Contributor() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> void bind(ProtocolConnection<T> connection) {
                ProtocolConnection<Object> wire = (ProtocolConnection<Object>) connection;
                if (connection.peer() != null) ServerWindows.install(wire);
                else ClientWindows.install(wire);
            }
        });
    }

    /** Whether {@link #register()} has run. Diagnostics, and what a test asserts before opening. */
    public static synchronized boolean isRegistered() {
        return registered;
    }

    /**
     * Forgets the registration so a later {@link #register()} runs again.
     *
     * <p><b>Tests only</b>, and it exists because {@code Protocols.resetForTesting()} drops every
     * contributor while this class's own flag would still say it had contributed — two records of one
     * fact, disagreeing, which is the shape that makes a suite pass or fail on test order.</p>
     */
    public static synchronized void resetForTesting() {
        registered = false;
    }
}
