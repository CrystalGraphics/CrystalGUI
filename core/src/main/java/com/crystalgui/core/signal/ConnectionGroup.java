package com.crystalgui.core.signal;

import com.crystalgui.core.dispose.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a group of {@link Connection}s for bulk lifecycle cleanup.
 *
 * <p>Typical usage: a widget creates a {@code ConnectionGroup}, adds all its
 * signal connections to it, and calls {@link #disconnectAll()} on detach.</p>
 *
 * <p>It is itself a {@link Disposable}, so the group can be registered on an owner and released with
 * it — VS Code's {@code DisposableStore}. Prefer that over calling {@link #disconnectAll()} from a
 * detach path: a detach path is another thing to remember, and one that only runs if the widget is
 * detached rather than merely dropped.</p>
 *
 * <p><b>Single-thread only.</b></p>
 */
public final class ConnectionGroup implements Disposable {

    private final List<Connection> connections = new ArrayList<>();

    /**
     * Adds a connection to this group.
     *
     * @param connection the connection to track
     * @return the same connection (for chaining)
     */
    public Connection add(Connection connection) {
        if (connection == null) throw new IllegalArgumentException("connection must not be null");
        connections.add(connection);
        return connection;
    }

    /**
     * Disconnects all tracked connections and clears the group.
     * Safe to call multiple times.
     */
    public void disconnectAll() {
        for (int i = 0; i < connections.size(); i++) {
            connections.get(i).disconnect();
        }
        connections.clear();
    }

    /** Disposal <b>is</b> {@link #disconnectAll()} — see the class note. */
    @Override
    public void dispose() {
        disconnectAll();
    }

    /** Returns the number of tracked connections (including already-disconnected ones). */
    public int size() {
        return connections.size();
    }
}