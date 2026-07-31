package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.canvas.CanvasView;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link CanvasView} that knows about nodes and wires: it owns the edge set, the wire layer that
 * paints it, and the rules about what may connect to what.
 *
 * <pre>{@code
 * GraphView graph = new GraphView();
 * GraphNode position = new GraphNode("Position");
 * NodePort out = position.addOutput(VEC3, "Out");
 * graph.addNode(position, 40f, 40f);
 * graph.connect(out, add.addInput(VEC3, "A"));
 * }</pre>
 *
 * <h3>Why the edges live here and not on the ports</h3>
 * <p>A port could hold its own list, and then "which edges exist" would have as many answers as there
 * are ports. One owner means the replace-on-occupied-input rule, the duplicate check and the
 * connection counts are all decided in one place — and it is the place a command (6.2.4) will call
 * into, so undo has a single seam rather than needing to walk the tree putting ports back.</p>
 *
 * <p><b>The tag trap applies here.</b> {@code GraphView extends CanvasView} but reports the tag
 * {@code graphview}, and a {@code canvasview} rule matches none of it — a widget's cascade identity is
 * its tag, never its Java supertype. Anything the canvas needs from a stylesheet must name
 * {@code graphview} too; the viewport's structural styling is written from Java at DEFAULT origin
 * precisely so this class inherits it for real.</p>
 */
public class GraphView extends CanvasView {

    /** Logical px, before zoom. */
    private static final float DEFAULT_WIRE_WIDTH = 2f;

    /**
     * The floor a wire's on-screen thickness will not go below, in <b>physical-ish logical px after
     * zoom</b>.
     *
     * <p>This is the answer to the open question the plan left: {@code CgCurveRenderer} scales stroke
     * widths by the pose, which is correct — a wire should get thicker as you zoom in, exactly like a
     * border. Zoomed <em>out</em> the same rule takes a 2px wire to a fifth of a pixel and the graph
     * looks empty, so the width is clamped here, against the canvas's own zoom, rather than in the
     * shader. Keeping it out of the shader means the stroke maths stays linear and every other consumer
     * of {@code curve()} is unaffected.</p>
     */
    private static final float MIN_WIRE_SCREEN_WIDTH = 1.25f;

    private final List<GraphConnection> connections = new ArrayList<>();
    private final List<GraphConnection> connectionsView = Collections.unmodifiableList(connections);

    private final NodeWireLayer wireLayer;

    @Getter
    private float wireBaseWidth = DEFAULT_WIRE_WIDTH;

    /** Fires after any change to the edge set — connect, disconnect, or a node leaving with wires on it. */
    public final Signal.Action onConnectionsChanged = new Signal.Action();

    public GraphView() {
        wireLayer = new NodeWireLayer(this, connections);
        // First, so it paints under every node: equal z-index siblings paint in insertion order.
        addNode(wireLayer, 0f, 0f);
        // A painter is not a node. Culling tests an element's box, and this one's box says nothing
        // about where its wires are — left cullable, it would vanish the moment the view left world
        // origin, taking every wire with it. It culls per wire instead, where the endpoints are known.
        setCullExempt(wireLayer, true);
    }

    /** The layer that draws the wires. Exposed for a theme or a test to reach; it owns no state a
     * caller should be setting. */
    public NodeWireLayer wireLayer() {
        return wireLayer;
    }

    // ── Nodes ───────────────────────────────────────────────────────────────

    /** Removes a node <b>and every wire attached to it</b>. Removing it as a plain element would leave
     * edges pointing at ports that are no longer in the tree, which paints wires to nowhere. */
    public GraphView removeNode(GraphNode node) {
        for (NodePort port : node.getPorts()) disconnectAll(port);
        content().removeChild(node);
        return this;
    }

    /** Every node currently on the plane, in insertion order. */
    public List<GraphNode> nodes() {
        List<GraphNode> found = new ArrayList<>();
        for (UIElement child : content().getChildren()) {
            if (child instanceof GraphNode node) found.add(node);
        }
        return found;
    }

    // ── Selection ───────────────────────────────────────────────────────────

    /**
     * Selects {@code node}, replacing the current selection unless {@code additive}.
     *
     * <p><b>This is the smallest thing that works, and it is on purpose.</b> 6.2.4 owns the selection
     * <em>model</em> — a set plus a signal that an inspector, a marquee and a delete command all read
     * without any of them owning it. What is here is the click behaviour a user expects immediately
     * (press a node and it is the selected one; Shift adds), implemented over the nodes themselves so
     * that when the model lands it replaces this list rather than fighting a second source of truth.</p>
     */
    public GraphView selectNode(GraphNode node, boolean additive) {
        if (!additive) {
            for (GraphNode other : nodes()) {
                if (other != node) other.setSelected(false);
            }
            node.setSelected(true);
        } else {
            node.setSelected(!node.isSelected());
        }
        onSelectionChanged.emit();
        return this;
    }

    public GraphView clearSelection() {
        boolean any = false;
        for (GraphNode node : nodes()) {
            if (node.isSelected()) {
                node.setSelected(false);
                any = true;
            }
        }
        if (any) onSelectionChanged.emit();
        return this;
    }

    public List<GraphNode> selectedNodes() {
        List<GraphNode> found = new ArrayList<>();
        for (GraphNode node : nodes()) {
            if (node.isSelected()) found.add(node);
        }
        return found;
    }

    /** Fires after any change to which nodes are selected. */
    public final Signal.Action onSelectionChanged = new Signal.Action();

    // ── Connections ─────────────────────────────────────────────────────────

    public List<GraphConnection> getConnections() {
        return connectionsView;
    }

    /**
     * Whether a wire may join these two ports, in either drag order.
     *
     * <p>Re-read every frame by {@code NodePort}'s {@code DragOver} handler rather than latched, so a
     * target that stops being legal mid-drag stops accepting with no state to unwind. The rules:
     * one of each direction, not the same node, the source type accepting the target's, and no
     * duplicate.</p>
     *
     * <p>Note what is <b>not</b> here: an occupied input is still connectable. Unity allows one edge per
     * input and many per output, so dropping onto a taken input is a <em>replace</em>, not a rejection —
     * refusing it would make rewiring a node mean two deliberate gestures instead of one.</p>
     */
    public boolean canConnect(@Nullable NodePort a, @Nullable NodePort b) {
        if (a == null || b == null || a == b) return false;
        if (a.getDirection() == b.getDirection()) return false;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;
        if (output.node() != null && output.node() == input.node()) return false;
        if (!output.getType().isCompatibleWith(input.getType())) return false;
        return findConnection(output, input) == null;
    }

    /**
     * Connects two ports, in either drag order. Returns the new edge, or {@code null} if the pair is
     * not connectable.
     *
     * <p><b>An occupied input is replaced</b>, and the displaced edge goes out through the same
     * {@link #disconnect} every other removal uses. That matters more than it looks: when 6.2.4 makes
     * this a command, the implicit disconnect has to be part of the same undoable step as the connect,
     * and it will be — because there is only one code path that removes an edge.</p>
     */
    @Nullable
    public GraphConnection connect(NodePort a, NodePort b) {
        if (!canConnect(a, b)) return null;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;

        GraphConnection existing = firstConnectionTo(input);
        if (existing != null) disconnect(existing);

        GraphConnection connection = new GraphConnection(output, input);
        connections.add(connection);
        refreshCounts(output, input);
        onConnectionsChanged.emit();
        return connection;
    }

    public boolean disconnect(GraphConnection connection) {
        if (!connections.remove(connection)) return false;
        refreshCounts(connection.from(), connection.to());
        onConnectionsChanged.emit();
        return true;
    }

    /** Drops every edge touching {@code port}. */
    public int disconnectAll(NodePort port) {
        List<GraphConnection> doomed = new ArrayList<>();
        for (GraphConnection connection : connections) {
            if (connection.touches(port)) doomed.add(connection);
        }
        if (doomed.isEmpty()) return 0;
        connections.removeAll(doomed);
        for (GraphConnection connection : doomed) refreshCounts(connection.from(), connection.to());
        onConnectionsChanged.emit();
        return doomed.size();
    }

    /** Edges touching {@code port}, in insertion order. */
    public List<GraphConnection> connectionsOf(NodePort port) {
        List<GraphConnection> found = new ArrayList<>();
        for (GraphConnection connection : connections) {
            if (connection.touches(port)) found.add(connection);
        }
        return found;
    }

    @Nullable
    private GraphConnection findConnection(NodePort output, NodePort input) {
        for (GraphConnection connection : connections) {
            if (connection.from() == output && connection.to() == input) return connection;
        }
        return null;
    }

    @Nullable
    private GraphConnection firstConnectionTo(NodePort input) {
        for (GraphConnection connection : connections) {
            if (connection.to() == input) return connection;
        }
        return null;
    }

    /**
     * Recounts from the edge list rather than incrementing.
     *
     * <p>A counter that is bumped up and down drifts the first time a removal path is added that
     * forgets to decrement — and the symptom is a port that stays visually connected forever, which
     * reads as a paint bug. Recomputing is O(edges) on a change no user makes faster than they can
     * click.</p>
     */
    private void refreshCounts(NodePort... ports) {
        for (NodePort port : ports) {
            int count = 0;
            for (GraphConnection connection : connections) {
                if (connection.touches(port)) count++;
            }
            port.setConnectionCount(count);
        }
    }

    // ── The wire being dragged ──────────────────────────────────────────────

    void beginPendingWire(NodePort from) {
        wireLayer.beginPending(from);
    }

    void updatePendingWire(float planeX, float planeY) {
        wireLayer.updatePending(planeX, planeY);
    }

    void endPendingWire() {
        wireLayer.endPending();
    }

    // ── Wire geometry ───────────────────────────────────────────────────────

    public GraphView setWireBaseWidth(float width) {
        this.wireBaseWidth = Math.max(0.1f, width);
        return this;
    }

    /** The width handed to {@code ctx.curve()}, in pre-pose units — see {@link #MIN_WIRE_SCREEN_WIDTH}. */
    public float getWireWidth() {
        float zoom = Math.max(1e-4f, getZoom());
        return Math.max(wireBaseWidth, MIN_WIRE_SCREEN_WIDTH / zoom);
    }
}
