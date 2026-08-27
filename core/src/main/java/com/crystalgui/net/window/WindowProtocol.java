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
 * <h3>Which side gets which host is said at the call site</h3>
 *
 * <p>{@code Protocols.server} binds {@link ServerWindows} where the connection has a peer and
 * {@code Protocols.client} binds {@link ClientWindows} where it has none — two method references, with
 * the side in the method name rather than in a {@code peer() == null} guard. A single-player process
 * ends up with one host of each on two different connections, which is what it genuinely has.</p>
 *
 * <p>The host layer is written against {@code ProtocolConnection<Object>} — making {@link ServerWindow}
 * generic would put a type parameter in every mod's class declaration to serve a case no wire in this
 * engine has — and the one unchecked cast that view needs lives in {@code Protocols.open}, sound by
 * the ops discipline documented there: every {@code StateMap} takes its {@code DynamicOps} from
 * {@code connection.ops()}, never from a hardcoded instance.</p>
 */
public final class WindowProtocol {

    private static boolean registered;

    private WindowProtocol() {
    }

    /** Contributes the window lifecycle to every connection opened after this. Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Protocols.server("ui", ServerWindows::install);
        Protocols.client("ui", ClientWindows::install);
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
