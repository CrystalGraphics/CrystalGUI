package com.crystalgui.core.signal;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all signal types. Manages a list of {@link Connection}
 * objects and provides {@link #disconnectAll()} to bulk-disconnect.
 */
public abstract class SignalBase {

    private final List<Connection> connections = new ArrayList<>();

    /** Register a connection so it can be bulk-disconnected later. */
    protected void addConnection(Connection conn) {
        connections.add(conn);
    }

    /** Disconnect all registered connections and clear the list. */
    public void disconnectAll() {
        for (int i = 0; i < connections.size(); i++) {
            connections.get(i).disconnect();
        }
        connections.clear();
    }
}
