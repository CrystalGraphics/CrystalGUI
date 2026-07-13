package com.crystalgui.core.signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns multiple {@link Connection} objects and supports bulk disconnect.
 *
 * <p>Use this to group related connections (e.g., all listeners registered
 * by one widget) so they can be cleaned up together.</p>
 */
public final class ConnectionGroup {

    private final List<Connection> connections = new ArrayList<>();

    /** Add one or more connections to this group. */
    public ConnectionGroup add(Connection... conns) {
        for (Connection c : conns) {
            if (c != null) {
                connections.add(c);
            }
        }
        return this;
    }

    /** Disconnect all connections in this group and clear the list. */
    public void disconnectAll() {
        for (int i = 0; i < connections.size(); i++) {
            connections.get(i).disconnect();
        }
        connections.clear();
    }
}
