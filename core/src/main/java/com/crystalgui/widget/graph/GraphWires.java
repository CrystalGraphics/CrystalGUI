package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.PortRef;

/**
 * Every wire on a graph: what may be joined, what joining does, and how one comes apart.
 *
 * <pre>{@code
 * if (wires.canConnect(a, b)) wires.connect(a, b);   // either drag order
 * wires.disconnectAll(port);                         // one undo step
 * }</pre>
 *
 * <p>Every mutation goes through {@link GraphEdits.Connect} or {@link GraphEdits.Disconnect}, so there
 * is exactly one path that adds an edge and one that removes it — which is what lets a replace be a
 * transaction rather than a special case. Reached from a {@link GraphView}, never built elsewhere.</p>
 */
final class GraphWires {

    private final GraphView view;

    GraphWires(GraphView view) {
        this.view = view;
    }

    /**
     * Whether a wire may join these two ports, in either drag order.
     *
     * <p>Re-read every frame by {@code NodePort}'s {@code DragOver} handler rather than latched, so a
     * target that stops being legal mid-drag stops accepting with no state to unwind. The rules: one of
     * each direction, not the same node, the source type accepting the target's, and no duplicate.</p>
     *
     * <p>Note what is <b>not</b> here: an occupied input is still connectable. Unity allows one edge per
     * input and many per output, so dropping onto a taken input is a <em>replace</em>, not a rejection —
     * refusing it would make rewiring a node mean two deliberate gestures instead of one.</p>
     */
    boolean canConnect(@Nullable NodePort a, @Nullable NodePort b) {
        if (a == null || b == null || a == b) return false;
        if (a.getDirection() == b.getDirection()) return false;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;
        if (output.node() != null && output.node() == input.node()) return false;
        if (!output.getType().isCompatibleWith(input.getType())) return false;
        return findConnection(output, input) == null;
    }

    /**
     * Connects two ports, in either drag order. Returns the new edge, or null if the pair is not
     * connectable.
     *
     * <p><b>An occupied input is replaced</b>, and the displaced edge goes out through the same
     * {@link #disconnect} every other removal uses. The replace is ONE undo step: a user who rewires an
     * input did one thing, and a Ctrl+Z that put the old wire back while leaving the new one would leave
     * the input holding two edges — a state the model forbids.</p>
     */
    @Nullable
    GraphConnection connect(NodePort a, NodePort b) {
        if (!canConnect(a, b)) return null;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;

        GraphConnection connection = new GraphConnection(output, input);
        EdgeData edge = edgeDataOf(connection);
        // Unbound ports have no document identity, so there is nothing to record — this is a view built
        // outside a document, which the tests do and a caller may.
        if (edge == null) return null;

        GraphConnection existing = firstConnectionTo(input);
        if (existing == null) {
            view.edits.apply(new GraphEdits.Connect(this, edge));
            return connection;
        }
        EdgeData existingEdge = edgeDataOf(existing);
        view.edits.begin("reconnect");
        try {
            if (existingEdge != null) view.edits.apply(new GraphEdits.Disconnect(this, existingEdge));
            view.edits.apply(new GraphEdits.Connect(this, edge));
        } finally {
            view.edits.end();
        }
        return connection;
    }

    /** The raw add both {@link GraphEdits.Connect} and {@link GraphEdits.Disconnect} share. */
    void addEdge(EdgeData edge) {
        view.document.restoreEdge(edge);
        view.linkWidgets(edge);
        view.markSynced();
        view.onConnectionsChanged.emit();
    }

    /** The raw removal, likewise. */
    void removeEdge(EdgeData edge) {
        view.document.disconnect(edge);
        NodePort from = view.portFor(edge.from());
        NodePort to = view.portFor(edge.to());
        view.connections.removeIf(c -> c.from() == from && c.to() == to);
        if (from != null && to != null) refreshCounts(from, to);
        view.markSynced();
        view.onConnectionsChanged.emit();
    }

    /** The document edge a view-side connection stands for, or null before either end is bound. */
    @Nullable
    private static EdgeData edgeDataOf(GraphConnection connection) {
        PortRef from = GraphView.refFor(connection.from());
        PortRef to = GraphView.refFor(connection.to());
        return from == null || to == null ? null : new EdgeData(from, to);
    }

    boolean disconnect(GraphConnection connection) {
        if (!view.connections.contains(connection)) return false;
        EdgeData edge = edgeDataOf(connection);
        if (edge == null) return false;
        view.edits.apply(new GraphEdits.Disconnect(this, edge));
        return true;
    }

    /** Drops every edge touching {@code port}, as one step: pulling a node's wires is one action, and
     * undoing it half way would be a graph the user never saw. */
    int disconnectAll(NodePort port) {
        List<GraphConnection> doomed = new ArrayList<>();
        for (GraphConnection connection : view.connections) {
            if (connection.touches(port)) doomed.add(connection);
        }
        if (doomed.isEmpty()) return 0;
        view.edits.begin("disconnect all");
        try {
            for (GraphConnection connection : doomed) {
                EdgeData edge = edgeDataOf(connection);
                if (edge != null) view.edits.apply(new GraphEdits.Disconnect(this, edge));
            }
        } finally {
            view.edits.end();
        }
        return doomed.size();
    }

    /** Edges touching {@code port}, in insertion order. */
    List<GraphConnection> connectionsOf(NodePort port) {
        List<GraphConnection> found = new ArrayList<>();
        for (GraphConnection connection : view.connections) {
            if (connection.touches(port)) found.add(connection);
        }
        return found;
    }

    @Nullable
    private GraphConnection findConnection(NodePort output, NodePort input) {
        for (GraphConnection connection : view.connections) {
            if (connection.from() == output && connection.to() == input) return connection;
        }
        return null;
    }

    @Nullable
    private GraphConnection firstConnectionTo(NodePort input) {
        for (GraphConnection connection : view.connections) {
            if (connection.to() == input) return connection;
        }
        return null;
    }

    /**
     * Recounts from the edge list rather than incrementing.
     *
     * <p>A counter that is bumped up and down drifts the first time a removal path is added that forgets
     * to decrement — and the symptom is a port that stays visually connected forever, which reads as a
     * paint bug. Recomputing is O(edges) on a change no user makes faster than they can click.</p>
     */
    void refreshCounts(NodePort... ports) {
        for (NodePort port : ports) {
            int count = 0;
            for (GraphConnection connection : view.connections) {
                if (connection.touches(port)) count++;
            }
            port.setConnectionCount(count);
        }
    }
}
