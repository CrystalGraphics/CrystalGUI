package com.crystalgui.ui.elements.graph;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.joml.Vector2f;
import com.crystalgui.ui.elements.canvas.CanvasView;
import com.crystalgui.ui.elements.canvas.WorldRect;
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
public class GraphView extends CanvasView implements UndoScope {

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

    /**
     * This document's history.
     *
     * <p>Owned here because the edges are owned here: a command has one seam to call into rather than
     * needing to walk the tree putting ports back. Implementing {@link UndoScope} is what lets
     * {@code edit.undo} find it — the nearest scope outward from whatever has focus, so a graph in one
     * tab and an editor in another never share a history.</p>
     *
     * <p><b>Only document state goes through it.</b> Pan, zoom, selection and collapse are view state
     * and are mutated directly; Ctrl+Z after wiring up a graph must undo the wire, not the scroll.</p>
     */
    private final UndoStack undoStack = new UndoStack();

    /** @see UndoScope */
    @Override
    public UndoStack undoStack() {
        return undoStack;
    }

    @Getter
    private float wireBaseWidth = DEFAULT_WIRE_WIDTH;

    /** Fires after any change to the edge set — connect, disconnect, or a node leaving with wires on it. */
    public final Signal.Action onConnectionsChanged = new Signal.Action();

    /** The rubber band. A child of the VIEWPORT, not the plane — see {@link #marqueeElement()}. */
    private final UIElement marquee = new UIElement();

    private boolean marqueeActive;
    private float marqueeStartX, marqueeStartY;
    /** The selection to fall back on while a Shift/Alt marquee is in flight, so dragging the band
     * bigger and smaller adds and removes rather than accumulating. */
    private List<GraphNode> marqueeBaseline = List.of();

    public GraphView() {
        wireLayer = new NodeWireLayer(this, connections);
        // First, so it paints under every node: equal z-index siblings paint in insertion order.
        addNode(wireLayer, 0f, 0f);
        // A painter is not a node. Culling tests an element's box, and this one's box says nothing
        // about where its wires are — left cullable, it would vanish the moment the view left world
        // origin, taking every wire with it. It culls per wire instead, where the endpoints are known.
        setCullExempt(wireLayer, true);

        // The graph must be able to HOLD focus, or none of its keys work.
        //
        // requestFocus refuses anything whose policy is NONE, which is the default — so the canvas took
        // no focus, every graph command resolved no GraphView from the focused element, and Delete,
        // Ctrl+A and Escape disabled themselves while the widget looked entirely alive. Pressing a node
        // happened to work because a node is CLICK-focusable, which made the failure look like "some
        // keys work and some do not".
        //
        // CLICK rather than FOCUSABLE: a canvas is not a tab stop. You reach it by pressing it, the way
        // you reach one in every editor.
        setFocusPolicy(FocusPolicy.CLICK);

        marquee.addClass(MARQUEE_CLASS);
        // In the VIEWPORT, not the plane: a band drawn inside the transform would scale with the zoom,
        // so its 1px border would be four physical pixels at 4x and invisible at 0.25x. Every editor
        // draws the rubber band in screen space over a world-space test, and so does this.
        marquee.setHitTest(false);
        StyleGroup.defaultPipeline(marquee.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).display(TaffyDisplay.NONE));
        addInternalChild(marquee);

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // A press that reached the graph itself landed on empty canvas: a node claims its own press
            // in the capture phase, and a port claims one before that. So this is the marquee's press —
            // unless a wire is under it, which is the only thing here that is drawn but not an element.
            if (beginMarqueeOrPickWire(event.getPosition().x(), event.getPosition().y())) {
                event.stopPropagation();
            }
        }, false, true);
    }

    /** On the rubber band, so a theme owns its look. */
    public static final String MARQUEE_CLASS = "__marquee__";

    /** The rubber-band element, for a theme or a test. */
    public UIElement marqueeElement() {
        return marquee;
    }

    public boolean isMarqueeActive() {
        return marqueeActive;
    }

    /** The layer that draws the wires. Exposed for a theme or a test to reach; it owns no state a
     * caller should be setting. */
    public NodeWireLayer wireLayer() {
        return wireLayer;
    }

    // ── Nodes ───────────────────────────────────────────────────────────────

    /**
     * Removes a node <b>and every wire attached to it</b>, as one undoable step.
     *
     * <p>Removing it as a plain element would leave edges pointing at ports that are no longer in the
     * tree, which paints wires to nowhere. Wrapping the wires and the node in one transaction is what
     * makes the undo whole: unwound in reverse, the node comes back first and its wires reconnect to
     * ports that exist again.</p>
     */
    public GraphView removeNode(GraphNode node) {
        WorldRect at = worldBoundsOf(node);
        undoStack.beginTransaction("delete node");
        try {
            for (NodePort port : node.getPorts()) disconnectAll(port);
            undoStack.execute(new AddNodeEdit(this, node, at.x(), at.y(), false));
        } finally {
            undoStack.endTransaction();
        }
        selection.prune(this);
        return this;
    }

    /**
     * Deletes everything selected, as <b>one</b> undo step.
     *
     * <p>One transaction rather than one per node for the reason the whole transaction mechanism
     * exists: a user who selected six nodes and pressed Delete did one thing, and six presses of Ctrl+Z
     * to get back is not undo, it is arithmetic.</p>
     *
     * @return how many nodes and wires went
     */
    public int deleteSelection() {
        List<GraphNode> doomedNodes = selection.nodes();
        GraphConnection doomedWire = selection.wire();
        if (doomedNodes.isEmpty() && doomedWire == null) return 0;

        undoStack.beginTransaction("delete");
        try {
            if (doomedWire != null) disconnect(doomedWire);
            for (GraphNode node : doomedNodes) removeNode(node);
        } finally {
            undoStack.endTransaction();
        }
        selection.clear();
        return doomedNodes.size() + (doomedWire == null ? 0 : 1);
    }

    /** Adding or removing a node, as data: the widget and where it sat. */
    private record AddNodeEdit(GraphView view, GraphNode node, float worldX, float worldY,
                               boolean adding) implements Edit {
        @Override public void apply() {
            if (adding) view.addNode(node, worldX, worldY);
            else view.content().removeChild(node);
        }
        @Override public void undo() {
            if (adding) view.content().removeChild(node);
            else view.addNode(node, worldX, worldY);
        }
        @Override public String label() { return adding ? "add node" : "delete node"; }
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
     * What is selected. A model rather than a flag per node, so a marquee, a delete command and an
     * inspector all read one answer — see {@link GraphSelection}, including why selection is not
     * undoable.
     */
    @Getter
    private final GraphSelection selection = new GraphSelection();

    /**
     * The press rule every graph editor uses, and the one a naive implementation gets wrong.
     *
     * <p>Clicking one of five selected nodes in order to drag all five is the most common gesture there
     * is. "A press selects only that node" breaks it — the other four deselect and the drag moves one.
     * So on <b>press</b> a node that is already selected leaves the selection alone; only an unselected
     * one replaces it. Shift always toggles.</p>
     */
    public GraphView selectNode(GraphNode node, boolean additive) {
        if (additive) selection.toggle(node);
        else if (!selection.contains(node)) selection.selectOnly(node);
        return this;
    }

    public GraphView clearSelection() {
        selection.clear();
        return this;
    }

    public List<GraphNode> selectedNodes() {
        return selection.nodes();
    }

    /** Every node on the plane, selected. */
    public GraphView selectAll() {
        selection.replaceWith(nodes());
        return this;
    }

    /** The nodes whose world rect touches {@code region} — the marquee's question.
     *
     * <p><b>Touched, not enclosed.</b> No vendor documents which they use, so it is a decision: at any
     * zoom where a node is larger than the viewport, an enclose-only rule makes it unselectable by
     * marquee at all. CAD's direction-dependent convention (drag right for enclose, left for cross) is
     * powerful, unguessable, and belongs to a domain where precision beats discoverability.</p> */
    public List<GraphNode> nodesTouching(WorldRect region) {
        List<GraphNode> found = new ArrayList<>();
        for (GraphNode node : nodes()) {
            if (region.intersects(worldBoundsOf(node))) found.add(node);
        }
        return found;
    }

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

        GraphConnection connection = new GraphConnection(output, input);
        GraphConnection existing = firstConnectionTo(input);
        if (existing == null) {
            undoStack.execute(new ConnectEdit(this, connection, true));
            return connection;
        }
        // The replace is ONE undo step, and that is the whole reason transactions exist: a user who
        // rewires an input did one thing, and a Ctrl+Z that put the old wire back while leaving the new
        // one would leave the input holding two edges — a state the model forbids.
        undoStack.beginTransaction("reconnect");
        try {
            undoStack.execute(new ConnectEdit(this, existing, false));
            undoStack.execute(new ConnectEdit(this, connection, true));
        } finally {
            undoStack.endTransaction();
        }
        return connection;
    }

    /**
     * Adding or removing one edge.
     *
     * <p>Data, not a closure: the two ports and a direction. That is what makes it invertible without
     * remembering anything, and what would let it be sent to a server if 6.2.5 wants that later — a
     * captured lambda could be neither.</p>
     */
    private record ConnectEdit(GraphView view, GraphConnection connection, boolean adding) implements Edit {
        @Override public void apply() {
            if (adding) view.addEdge(connection);
            else view.removeEdge(connection);
        }
        @Override public void undo() {
            if (adding) view.removeEdge(connection);
            else view.addEdge(connection);
        }
        @Override public String label() { return adding ? "connect" : "disconnect"; }
    }

    /** The raw mutation both directions of {@link ConnectEdit} share. */
    private void addEdge(GraphConnection connection) {
        if (connections.contains(connection)) return;
        connections.add(connection);
        refreshCounts(connection.from(), connection.to());
        onConnectionsChanged.emit();
    }

    private void removeEdge(GraphConnection connection) {
        if (!connections.remove(connection)) return;
        refreshCounts(connection.from(), connection.to());
        onConnectionsChanged.emit();
    }

    public boolean disconnect(GraphConnection connection) {
        if (!connections.contains(connection)) return false;
        undoStack.execute(new ConnectEdit(this, connection, false));
        return true;
    }

    /** Drops every edge touching {@code port}. */
    public int disconnectAll(NodePort port) {
        List<GraphConnection> doomed = new ArrayList<>();
        for (GraphConnection connection : connections) {
            if (connection.touches(port)) doomed.add(connection);
        }
        if (doomed.isEmpty()) return 0;
        // One step: pulling a node's wires is one action, and undoing it half way would be a graph the
        // user never saw.
        undoStack.beginTransaction("disconnect all");
        try {
            for (GraphConnection connection : doomed) undoStack.execute(new ConnectEdit(this, connection, false));
        } finally {
            undoStack.endTransaction();
        }
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

    /**
     * Records a completed node move as one undo step.
     *
     * <p>Recorded at the <b>end</b> of the drag, not per frame: the position is written continuously
     * while the pointer moves, and a history of four hundred one-pixel steps is not a history. So the
     * move happens directly and the stack is told afterwards with {@link UndoStack#push} — which is
     * exactly the case that method exists for, and the reason it exists alongside {@code execute}.</p>
     *
     * <p>A move that ended where it started records nothing. A Ctrl+Z that appears to do nothing is
     * worse than one press too few.</p>
     */
    public void recordMove(GraphNode node, float fromX, float fromY, float toX, float toY) {
        if (fromX == toX && fromY == toY) return;
        undoStack.push(new MoveNodeEdit(this, node, fromX, fromY, toX, toY));
    }

    /**
     * Records a completed multi-node move as one undo step.
     *
     * <p>One transaction rather than one edit per node, because the user performed one drag. The
     * per-node edits inside it are still individually correct, which is what lets a future
     * align-or-distribute command reuse them.</p>
     */
    public void recordMoves(List<GraphNode> moved, List<float[]> origins, float dx, float dy) {
        if (moved.isEmpty() || (dx == 0f && dy == 0f)) return;
        if (moved.size() == 1) {
            float[] origin = origins.get(0);
            recordMove(moved.get(0), origin[0], origin[1], origin[0] + dx, origin[1] + dy);
            return;
        }
        undoStack.beginTransaction("move " + moved.size() + " nodes");
        try {
            for (int i = 0; i < moved.size(); i++) {
                float[] origin = origins.get(i);
                undoStack.push(new MoveNodeEdit(this, moved.get(i),
                        origin[0], origin[1], origin[0] + dx, origin[1] + dy));
            }
        } finally {
            undoStack.endTransaction();
        }
    }

    /** Data, not a closure: two positions and the node. Invertible by swapping them. */
    private record MoveNodeEdit(GraphView view, GraphNode node,
                                float fromX, float fromY, float toX, float toY) implements Edit {
        @Override public void apply() { view.moveNode(node, toX, toY); }
        @Override public void undo() { view.moveNode(node, fromX, fromY); }
        @Override public String label() { return "move"; }
    }

    // ── Marquee ─────────────────────────────────────────────────────────────

    /**
     * Starts a rubber-band selection, or selects a wire if one is under the press.
     *
     * <p>The wire check comes first and is the reason the layer can stay {@code hitTest(false)}: a wire
     * is painted, not laid out, so nothing in the hit-test tree knows where it is. Asking the layer
     * directly keeps that true rather than inventing an element per edge — which is the trade 6.2.3
     * recorded.</p>
     *
     * @return whether the press was claimed
     */
    private boolean beginMarqueeOrPickWire(float rawX, float rawY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return false;

        // Pressing the canvas focuses it, exactly as pressing a node does.
        //
        // Every graph command resolves the nearest GraphView from the FOCUSED element, so without this
        // a click on empty canvas or on a wire leaves focus wherever it was — and Delete, Ctrl+A and
        // Escape are all silently disabled while the graph looks and feels active. Selecting a wire and
        // pressing Delete did nothing at all, which reads as a broken command rather than as a focus
        // problem.
        //
        // requestPOINTERFocus, not requestFocus: the latter is PROGRAMMATIC, which is a focus source
        // `:focus-visible` deliberately rings — so every click on the canvas drew a focus ring around the
        // entire viewport. You already know where your pointer is; that is the whole carve-out
        // `:focus-visible` exists for, and the click path takes it too.
        window.getInputHandler().requestPointerFocus(this);

        Vector2f world = screenToWorld(rawX, rawY);
        GraphConnection hit = wireLayer.pickWire(world.x(), world.y());
        if (hit != null) {
            selection.selectOnly(hit);
            return true;
        }

        boolean additive = isShiftHeld();
        boolean subtractive = isAltHeld();
        if (!additive && !subtractive) selection.clear();
        marqueeBaseline = selection.nodes();

        Vector2f local = screenToLocal(rawX, rawY);
        marqueeStartX = local.x();
        marqueeStartY = local.y();
        marqueeActive = true;

        window.getInputHandler().getDragController().startDrag(this, rawX, rawY,
                new UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        updateMarquee(mx, my, additive, subtractive);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        endMarquee();
                    }

                    @Override
                    public void onDragCancel() {
                        // Escape mid-band puts back what was selected before it started, rather than
                        // leaving whatever the half-drawn rectangle happened to be over.
                        selection.replaceWith(marqueeBaseline);
                        endMarquee();
                    }
                });
        return true;
    }

    private void updateMarquee(float localX, float localY, boolean additive, boolean subtractive) {
        float x = Math.min(marqueeStartX, localX), y = Math.min(marqueeStartY, localY);
        float w = Math.abs(localX - marqueeStartX), h = Math.abs(localY - marqueeStartY);

        var cache = getRuntimeCache();
        // left/top are relative to this element's own box, since the band is absolutely positioned
        // inside it — while the drag reports coordinates in the space getX() lives in.
        final float left = x - cache.getX(), top = y - cache.getY();
        StyleGroup.importantPipeline(marquee.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX).left(left).top(top).width(w).height(h));

        Vector2f from = viewportToWorld(x, y);
        Vector2f to = viewportToWorld(x + w, y + h);
        List<GraphNode> inside = nodesTouching(WorldRect.of(from.x(), from.y(), to.x(), to.y()));

        // Recomputed from the baseline every frame rather than accumulated, so shrinking the band takes
        // nodes back out. An accumulating marquee only ever grows, which feels broken the first time you
        // overshoot.
        if (subtractive) {
            List<GraphNode> kept = new ArrayList<>(marqueeBaseline);
            kept.removeAll(inside);
            selection.replaceWith(kept);
        } else if (additive) {
            List<GraphNode> combined = new ArrayList<>(marqueeBaseline);
            for (GraphNode node : inside) if (!combined.contains(node)) combined.add(node);
            selection.replaceWith(combined);
        } else {
            selection.replaceWith(inside);
        }
    }

    private void endMarquee() {
        marqueeActive = false;
        StyleGroup.importantPipeline(marquee.getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
    }

    /** World point -> the wire under it, or null. Delegates to the layer, which is the only thing that
     * knows where a wire was drawn. */
    @Nullable
    public GraphConnection pickWire(float worldX, float worldY) {
        return wireLayer.pickWire(worldX, worldY);
    }

    private static boolean isShiftHeld() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasShift(input.getCurrentModifiers());
    }

    private static boolean isAltHeld() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasAlt(input.getCurrentModifiers());
    }

    // ── Framing ─────────────────────────────────────────────────────────────

    /** Frames the selection, or everything when nothing is selected — Unity binds these to F and A. */
    public GraphView frameSelection(float padding) {
        List<GraphNode> selected = selection.nodes();
        if (selected.isEmpty()) {
            fitToContent(padding);
            return this;
        }
        WorldRect union = null;
        for (GraphNode node : selected) {
            WorldRect rect = worldBoundsOf(node);
            union = union == null ? rect : union.union(rect);
        }
        return frameRect(union, padding);
    }

    /** Fits the view to an arbitrary world rect. {@code fitToContent} is this over everything. */
    public GraphView frameRect(WorldRect rect, float padding) {
        if (rect == null) return this;
        var cache = getRuntimeCache();
        float viewW = cache.getWidth(), viewH = cache.getHeight();
        if (viewW <= 0f || viewH <= 0f) return this;
        WorldRect padded = rect.expand(padding);
        // Never magnifies past 1:1. Framing means "make this fit", and for one small node in a large
        // viewport the literal fit is an eight-times blow-up that fills the screen with a single box —
        // which is what it did, and is useless: the point of framing a selection is to see it in
        // context, not to inspect its pixels.
        float fit = Math.min(viewW / Math.max(1e-4f, padded.width()), viewH / Math.max(1e-4f, padded.height()));
        setZoom(Math.min(1f, fit));
        centerOnWorld(padded.centerX(), padded.centerY());
        return this;
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
