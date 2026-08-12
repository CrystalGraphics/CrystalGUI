package com.crystalgui.core.signal;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;

/**
 * Handle for a single signal-listener connection.
 *
 * <p>Lambda-compatible: only {@link #disconnect()} is abstract.</p>
 *
 * <h3>A connection is a {@link Disposable}</h3>
 *
 * <p>So a subscription can be <b>owned</b> rather than remembered:</p>
 *
 * <pre>{@code
 * Disposer.register(this, dock.onDidChangeActivePanel.connect(this::follow));
 * }</pre>
 *
 * <p>Nothing then has to disconnect it — closing whatever owns it does, in one place, in the right
 * order. Without this every listener in the engine is hand-disconnected from a matching teardown path,
 * which is the bookkeeping {@link Disposer} exists to remove and precisely what leaks when a panel is
 * closed: the widget goes, its subscription stays, and it keeps being called about a tree it is no
 * longer in.</p>
 *
 * <p>The dependency runs {@code signal → dispose} and must stay that way. Both are {@code core} and
 * neither touches GL, so this costs nothing headlessly; a {@code Disposer} that knew about signals
 * would be the wrong direction.</p>
 *
 * <p><b>Thread safety</b>: Connections are single-thread-only, matching the UI thread model.</p>
 */
@FunctionalInterface
public interface Connection extends Disposable {

    /** Disconnects this listener from its signal. Idempotent. */
    void disconnect();

    /** Disposal <b>is</b> disconnection — see the class note. */
    @Override
    default void dispose() {
        disconnect();
    }

    /** Returns true if this connection is still live. */
    default boolean isConnected() {
        return true; // overridden by concrete implementations
    }

    /**
     * A connection to nothing — what a subscribe method returns when there was nothing to subscribe to.
     *
     * <p>Exists so those methods can keep returning a {@code Connection} rather than null. A caller
     * stores what it is given and disconnects it on teardown; handing back null puts a check at every
     * one of those sites, and the one that gets forgotten throws at teardown, which is the worst
     * possible moment for a surprise.</p>
     */
    Connection DISCONNECTED = new Connection() {
        @Override
        public void disconnect() {
            // Already.
        }

        @Override
        public boolean isConnected() {
            return false;
        }
    };
}