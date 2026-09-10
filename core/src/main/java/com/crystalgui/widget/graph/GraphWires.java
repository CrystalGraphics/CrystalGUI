package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.graph.EdgeData;
import com.crystalgui.ui.dom.UIDocument;
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
        view.restoreEdge(edge);
        view.onConnectionsChanged.emit();
    }

    /** The raw removal, likewise. */
    void removeEdge(EdgeData edge) {
        NodePort from = view.portFor(edge.from());
        NodePort to = view.portFor(edge.to());
        view.removeEdgeFromDocument(edge);
        view.connections.removeIf(c -> c.from() == from && c.to() == to);
        if (from != null && to != null) refreshCounts(from, to);
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

    /** The wire under a WORLD point, or null. The layer is the only thing that knows where a wire was
     * drawn — it is painted, not laid out, so nothing in the hit-test tree knows it exists. */
    @Nullable
    GraphConnection pick(float worldX, float worldY) {
        return view.wireLayer().pickWire(worldX, worldY);
    }

    /** The wire under a VIEWPORT-space point, or null. @see #pick */
    @Nullable
    GraphConnection at(float rawX, float rawY) {
        Vector2f world = view.screenToWorld(rawX, rawY);
        return pick(world.x(), world.y());
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /**
     * Logical px, before zoom — Unity's wire is a hairline, and this used to be twice it.
     *
     * <p>The error was easy to make and worth recording: the reference screenshots are at 100%, while
     * the harness runs at {@code uiScale} 2, so a "2px" wire drew four physical pixels against Unity's
     * one and a half. A logical width compared against a physical reference is off by exactly the scale
     * factor, and looks merely "a bit heavy" rather than obviously wrong.</p>
     */
    private static final float DEFAULT_WIDTH = 1f;

    private float baseWidth = DEFAULT_WIDTH;

    void setBaseWidth(float width) {
        this.baseWidth = Math.max(0.1f, width);
    }

/** A wire is never thinner than this ON SCREEN, in device pixels of HALF-width.
     *
     * <p>Below it a stroke does not merely get thinner, it gets fainter with it: {@code
     * stroke_coverage} peaks at {@code 0.5 + halfWidth / ramp} on the centreline, so a half-width of
     * 0.05 device px draws at 23% opacity smeared over two pixels rather than as a thin line.
     * Measured on {@code cgui-hairline-probe}. A graph zoomed far out lost its wires to that, which
     * is a different thing from showing them small.</p>
     *
     * <p>THIS IS THE THICKNESS DIAL, and it trades against how solid the wire reads, because the
     * renderer's 1.5px reconstruction filter clips the peak of anything narrower than 0.75:
     * 0.75 gives a 1.5px wire at alpha 1.00, 0.5 a 1px wire at 0.83, 0.35 a 0.7px wire at 0.73.
     * 0.5 is the classic hairline and is where this sits — 0.75 was solid but read heavy against
     * the small nodes of a zoomed-out graph. See {@code CgVectorRenderer.FEATHER_ANTIALIAS}.</p>
     */
    private static final float MIN_DEVICE_HALF_WIDTH = 0.5f;

    /** The width handed to {@code ctx.curve().width(...)}, in pre-pose units, floored so the wire
     * never goes sub-pixel on screen. The pose (which already carries the plane's zoom and the
     * {@code uiScale}) is what makes it thicker zoomed in and thinner zoomed out, matching a real
     * border's behaviour under scale — until it would stop being a line at all; see
     * {@link #MIN_DEVICE_HALF_WIDTH}. */
    float width() {
        UIDocument doc = view.document();
        float deviceScale = doc == null ? 1f : doc.boxes().uiScale();
        float onScreen = Math.max(1e-4f, deviceScale * Math.max(1e-4f, view.getZoom()));
        return Math.max(baseWidth, MIN_DEVICE_HALF_WIDTH / onScreen);
    }
}
