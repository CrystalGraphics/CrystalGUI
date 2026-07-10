package com.crystalgui.core.signal;

/**
 * Handle for a single signal-listener connection.
 *
 * <p>Lambda-compatible: only {@link #disconnect()} is abstract.</p>
 *
 * <p><b>Thread safety</b>: Connections are single-thread-only, matching the UI thread model.</p>
 */
@FunctionalInterface
public interface Connection {

    /** Disconnects this listener from its signal. Idempotent. */
    void disconnect();

    /** Returns true if this connection is still live. */
    default boolean isConnected() {
        return true; // overridden by concrete implementations
    }
}
