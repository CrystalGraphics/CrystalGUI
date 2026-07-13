package com.crystalgui.core.signal;

/**
 * Represents one signal→slot connection. Calling {@link #disconnect()}
 * removes the listener from the signal's listener list.
 *
 * <p>Once disconnected, subsequent calls to {@link #disconnect()} are no-ops.
 */
public final class Connection {

    private final Runnable onDisconnect;
    private boolean connected = true;

    Connection(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    /** Disconnect this listener from its signal. Idempotent. */
    public void disconnect() {
        if (connected) {
            connected = false;
            onDisconnect.run();
        }
    }

    /** Returns true if this connection is still active. */
    public boolean isConnected() {
        return connected;
    }
}
