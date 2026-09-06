package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

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

    /** The width handed to {@code ctx.curve().width(...)}, in pre-pose units. Unclamped — the pose
     * (which already includes the plane's own zoom) is what makes this thicker zoomed in and thinner
     * zoomed out, same as {@link #DEFAULT_WIDTH}'s own note about matching a real border's
     * behaviour under scale. See {@link #feather()} for why the WIDTH staying unclamped does not
     * reintroduce the sub-pixel dropout an earlier version of this class floored it against. */
    float width() {
        return baseWidth;
    }

    /** {@code stroke_coverage}'s edge ramp at zoom 1 — see {@code CgVectorRenderer.Curve#feather} and
     * {@code stroke.glsl}. Same value {@link NodeWireLayer#WIRE_FEATHER} already used before this
     * needed to vary with zoom at all. */
    private static final float BASE_WIRE_FEATHER = 0.5f;

    /** The feather actually handed to {@code ctx.curve().feather(...)}: {@link #BASE_WIRE_FEATHER}
     * divided by zoom, so the ANTIALIASING RAMP stays a constant width on screen (in device-ish pixels)
     * regardless of zoom — the opposite of {@link #width()}, which is deliberately left to shrink
     * with zoom unclamped.
     *
     * <h3>Why the width shrinking and the feather NOT shrinking is correct, not a contradiction</h3>
     * <p>{@code stroke_coverage} computes {@code signedDist = dist - halfWidth} and returns {@code 1 -
     * smoothstep(-ramp/2, ramp/2, signedDist)}. When the ramp was left to shrink alongside the width (an
     * earlier version), a curve zoomed out below a device pixel wide made the WHOLE transition band
     * narrower than the space between two sampled pixel centres — every sample fell on one side of that
     * band or the other, evaluating to a hard 0 or 1 rather than a fraction, which is a per-pixel
     * dropout: the exact "missing pixels" a side-by-side against Unity's smooth thin line caught.
     * Flooring the ramp against zoom instead keeps it at least ~1 real screen pixel wide always, so
     * EVERY sample near the centreline lands inside a genuinely smooth gradient and gets a fractional,
     * antialiased coverage value — never a coin flip.</p>
     *
     * <p>Once the ramp is a real screen pixel and the width keeps shrinking past it, {@code halfWidth}
     * eventually sits INSIDE the ramp's own span at the centreline itself, so peak coverage there drops
     * below 1.0 too — the stroke reads as thinner AND fainter, not because anything multiplies its
     * colour's alpha (an earlier version tried exactly that, which is what desaturated a colour wire
     * toward the dark canvas behind it into ash-grey rather than a dim but still-hued line — the fade has
     * to happen in the SAME coverage computation the ramp already drives, or the colour and the
     * shrinking disagree about what's happening). This is the ordinary analytic-SDF answer to sub-pixel
     * line antialiasing, and needs no MSAA framebuffer to get right — the curve renderer already draws
     * every pixel from an exact distance field; it only needed the ramp width to stop shrinking past the
     * point where the pixel grid can resolve it.</p>
     */
    float feather() {
        float zoom = Math.max(1e-4f, view.getZoom());
        return BASE_WIRE_FEATHER / zoom;
    }
}
