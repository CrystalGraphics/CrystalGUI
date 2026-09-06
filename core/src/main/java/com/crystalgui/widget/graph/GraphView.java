package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeymapScope;
import com.crystalgui.widget.graph.node.NodeCreationMenu;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.widget.surface.edit.Clipboard;
import com.crystalgui.widget.surface.edit.Edits;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.service.Input;
import org.joml.Vector2f;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.surface.SurfaceEditor;
import com.crystalgui.widget.surface.select.SurfaceSelection;
import com.crystalgui.widget.surface.SurfacePolicy;
import com.crystalgui.widget.surface.mode.Marquee;
import com.crystalgui.widget.surface.mode.MoveGesture;
import com.crystalgui.widget.canvas.WorldRect;
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
 * <p><b>The tag trap applies here.</b> {@code GraphView extends SurfaceEditor} but reports the tag
 * {@code graphview}, and a {@code canvasview} rule matches none of it — a widget's cascade identity is
 * its tag, never its Java supertype. Anything the canvas needs from a stylesheet must name
 * {@code graphview} too; the viewport's structural styling is written from Java at DEFAULT origin
 * precisely so this class inherits it for real.</p>
 */
public class GraphView extends SurfaceEditor implements GraphContext {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, GraphView reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code graphview} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("graphview");

    /** This graph, for a command that acts on one — replaces {@code GraphCommands.graphFor}'s walk. */
    // `.new` UNTIL 6.9, following the convention 6.3 set for `menuBar.new`. A DataKey is interned by
    // NAME and its TYPE is what it names, so the old engine's copy and this one cannot share a key --
    // `create` throws `already declared as ..., not ...` the moment both classes initialise, which is
    // any test that touches both. The OLD name is the one that must not move: `ContextKeys.find`
    // resolves a key by name out of a `when` expression, so renaming the shipped one would silently
    // break every command declaration naming it.
    public static final DataKey<GraphView> GRAPH_VIEW =
            DataKey.create("graphView.new", GraphView.class);

    /**
     * Every graph gets its commands, with nothing installing them.
     *
     * <p>The engine calls this once for this class — see {@link UIElement#registerCommands}. Before, a
     * host had to call {@code GraphCommands.install(window)} and a graph dropped into a scene that
     * forgot had no Delete, no Select All and no framing, silently.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        GraphCommands.register();
        // Undo comes with any graph, because a graph is an UndoScope -- the stack is resolved from
        // focus, so this needs no element and binds to nothing.
        UndoCommands.register();
    }

    /**
     * The graph's own chords, on the graph.
     *
     * <p>Per instance, and it has to be: {@code F}, {@code A} and {@code Space} are <b>bare letters</b>,
     * so they may only be live while focus is inside a graph. Declaring them on the commands would make
     * them application-wide and cost every text field in the application three letters.</p>
     *
     * <p>A node declares its keymap by ANSWERING for one ({@link KeymapScope}) rather than through an
     * engine hook, so the binding happens once here and {@link #keymapOrNull()} hands it to the resolver.
     * The commit that lost this on the old engine lost it silently: every graph command existed, was
     * enabled, showed in the palette, and answered no key at all.</p>
     */
    private final Keymap keymap = defaultKeymap();

    private static Keymap defaultKeymap() {
        Keymap keymap = new Keymap();
        GraphCommands.bindDefaults(keymap);
        return keymap;
    }

    @Override
    public Keymap keymapOrNull() {
        return keymap;
    }

    /**
     * What this graph knows: itself, its selected nodes, and its undo history.
     *
     * <p>{@code super.getData(key)} last, so the generic {@code ELEMENT} answer stays reachable — the
     * rule every override of this method follows.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == GRAPH_VIEW) return this;
        if (key == UiDataKeys.CLIPBOARD) return clipboard.asActions;
        if (key == UiDataKeys.SELECTION) {
            return new ArrayList<Object>(getSelection().nodes());
        }
        Object undo = undoScopeData(key);
        // NO super: a UIElement is not a DataProvider, and the walk outward through
        // `commandParent()` is what reaches the next one. Answering null is how this one says
        // it has nothing, which is what lets an outer provider be found at all.
        return undo;
    }


    /**
     * REMOVED — kept here as a record of why. This used to floor a wire's on-screen thickness at 1
     * physical-ish logical px so it survived zooming out; {@link #getWireWidth()} computed
     * {@code max(wireBaseWidth, MIN_WIRE_SCREEN_WIDTH / zoom)} against it. That is exactly what made a
     * wire read as the SAME thickness at every zoom level below 1 — the clamp was engineered to counteract
     * the pose's own scaling, so at zoom 0.5 the pre-pose width doubled and the pose halved it right back.
     * Unity's own wires genuinely get thinner zoomed out, same as a border under a CSS
     * {@code transform: scale()} would — a stroke is content, not chrome, and content shrinks with the
     * view. {@link #getWireWidth()} now returns {@link #wireBaseWidth} unclamped and lets the pose do
     * the whole job, the same as every other {@code curve()}/{@code quad()} caller in the engine.
     */

    final List<GraphConnection> connections = new ArrayList<>();
    private final List<GraphConnection> connectionsView = Collections.unmodifiableList(connections);

    /** Every wire on this graph. @see GraphWires */
    private final GraphWires wires = new GraphWires(this);

    /**
     * The data this view projects. <b>The document is the model; the widgets are the projection.</b>
     *
     * <p>Every mutation here writes through to it, so the two can never disagree — which is what makes
     * saving, loading, duplicating and a server-authored graph ordinary rather than special. The view
     * keeps its own {@link GraphConnection} list because the wire layer draws from widget geometry, but
     * that list is derived: {@link #load} rebuilds it from the document and nothing else may.</p>
     */
    @Getter
    GraphDocument document = new GraphDocument();

    private final NodeWireLayer wireLayer;

    /** Every input port's floating default editor. @see GraphPorts */
    private final GraphPorts ports = new GraphPorts(this);

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
    /**
     * The one door every change to this graph goes through — the engine's.
     *
     * <p>The stack under it is the surface's own, resolved from focus by {@code UndoScope}, so a graph
     * in one tab and an editor in another never share a history. A transaction opened here is one undo
     * step however many nodes it moved.</p>
     *
     * <p><b>Only document state goes through it.</b> Pan, zoom, selection and collapse are view state
     * and are mutated directly; Ctrl+Z after wiring up a graph must undo the wire, not the scroll.</p>
     */
    final Edits edits = edits();

    /** The wire under the pointer, or null. Drives the hover thickening — a wire cannot carry {@code
     * :hover} itself, having no element. */
    @Getter
    @Nullable
    private GraphConnection hoveredWire;

    /** Fires after any change to the edge set — connect, disconnect, or a node leaving with wires on it. */
    public final Signal.Action onConnectionsChanged = new Signal.Action();

    @Override
    public Signal.Action connectionsChanged() {
        return onConnectionsChanged;
    }

    /** @see GraphContext#mountOverlay */
    @Override
    public void mountOverlay(UIElement panel) {
        addOverlay(panel);
    }


    /** What a graph means by the engine's questions. @see GraphPolicy */
    @Override
    protected SurfacePolicy createPolicy() {
        return new GraphPolicy(this);
    }

    /** A graph's selection answers two typed questions the engine's does not. @see GraphSelection */
    @Override
    protected SurfaceSelection createSelection(SurfacePolicy policy) {
        return new GraphSelection();
    }

    public GraphView() {
        super(NAME, List.of());
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


        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // Not a real pointer press. Space/Enter on a focused element synthesize a mouse press so
            // Button and friends get keyboard activation for free, and this view has to be focusable for
            // its command keys to resolve at all — so Enter arrived here as a left-click at wherever the
            // cursor happened to be and started a rubber band. It could not be dismissed either: a
            // marquee ends through the real pointer-up path, which a synthesized Up never reaches.
            //
            // A marquee means "the pointer went down HERE", which is not something a key can mean.
            if (event.getDetail() == Input.KEYBOARD_DETAIL) return;
            // A press that reached the graph itself landed on empty canvas: a node claims its own press
            // in the capture phase, and a port claims one before that. So this is the marquee's press —
            // unless a wire is under it, which is the only thing here that is drawn but not an element.
            if (isBackgroundGestureExempt(((UIElement) event.getTarget()))) return;
            // A press inside a NODE is never the canvas's, even when the node did not claim it.
            //
            // GraphNode stops propagation only for presses it turns into a move-drag; a press on its
            // controls it deliberately ignores, so the widget underneath can have it — and that press
            // then arrived here and was read as "empty canvas". This view would take pointer focus and
            // start a marquee WITH POINTER CAPTURE, which is fatal to any control in a node: the capture
            // swallows the release, and the focus change closes whatever popover the press just opened.
            // A Dropdown in a node looked completely dead because of it.
            //
            // Asking about the target rather than relying on every node to stop propagation is the
            // robust half: a widget that legitimately wants a press to pass through should not have to
            // know that letting it through starts a rubber band three levels up.
            if (isInsideNode(((UIElement) event.getTarget()))) return;
            if (beginMarqueeOrPickWire(event.getPosition().x(), event.getPosition().y())) {
                event.stopPropagation();
            }
        }, false, true);

        // A wire is painted, not laid out, so nothing in the hit-test tree knows where it is and it can
        // never receive a :hover of its own — the same trade that makes pickWire exist for presses. The
        // pointer position has to be re-tested against the curves each time it moves.
        this.events.getGroup(MouseEvent.Move.class).attachListener((el, event) -> {
            GraphConnection was = hoveredWire;
            hoveredWire = isBackgroundGestureExempt(((UIElement) event.getTarget()))
                    ? null
                    : wireAt(event.getPosition().x(), event.getPosition().y());
            // Nothing to invalidate — the layer repaints every frame and reads this directly. Kept as a
            // field rather than recomputed in paint so the pick runs once per move, not once per frame.
            if (was != hoveredWire) markTreeDirty();
        }, false, true);
    }

    /** Whether {@code target} is this graph's own node, or anything inside one — or a floating
     * default-value editor, which is neither: it is a plane child of its own, sitting beside its node
     * rather than inside it (see {@link NodePort#getDefaultEditor()}). Without the second check, a press
     * on one fell through as "empty canvas" and started a marquee under whatever the user was trying to
     * type into — the exact conflict {@code NodePort} used to guard against back when this editor lived
     * inside the port. */
    private boolean isInsideNode(@Nullable UIElement target) {
        for (UIElement element = target; element != null && element != this; element = element.parentElement()) {
            if (element instanceof GraphNode) return true;
            if (element.hasClass(NodePort.EDITOR_CLASS)) return true;
        }
        return false;
    }

    /** @see GraphWires#at */
    @Nullable
    public GraphConnection wireAt(float rawX, float rawY) {
        return wires.at(rawX, rawY);
    }

    /** On the rubber band, so a theme owns its look. */
    public static final String MARQUEE_CLASS = "__marquee__";

    /** The rubber-band element, for a theme or a test. */
    public UIElement marqueeElement() {
        return marquee().element();
    }

    public boolean isMarqueeActive() {
        return marquee().isActive();
    }

    /** The layer that draws the wires. Exposed for a theme or a test to reach; it owns no state a
     * caller should be setting. */
    public NodeWireLayer wireLayer() {
        return wireLayer;
    }

    // ── Port default editors ──────────────────────────────────

    /**
     * Repositions the mounted port editors, every frame. @see GraphPorts#reposition
     *
     * <p>Always ticking, regardless of {@link #setCullingEnabled}: a floating editor still has to track
     * its port even in a huge graph where node culling is doing real work, so this cannot piggyback on
     * {@link CanvasView#tickFrame}'s own early-out.</p>
     */
    public boolean tickFrame(float deltaSeconds) {
        super.tickFrame(deltaSeconds);
        ports.reposition();
        return true;
    }

    /**
     * Geometry that can only be settled once layout has run.
     *
     * <p>{@code onLayoutChanged()} on the old engine; there is no such override here, because layout
     * is ONE pass with no feedback into it. A post-layout hook may move a box and read a box and may
     * not add one — a structural change would need a second pass, and there is not one.</p>
     */
    private void onLayoutSettled() {
        // super's own ensureTicking() only registers while culling is enabled — this view needs to tick
        // unconditionally, for the reason tickFrame's javadoc gives. registerTicker is HashSet-backed, so
        // calling it again every layout pass is idempotent rather than wasteful.
        UIDocument window = document();
        if (window != null) document().animation().every(this, this::tickFrame);
    }

    /** Called by {@link GraphNode#addPort}. @see GraphPorts#watch */
    void watchPort(NodePort port) {
        ports.watch(port);
    }

    /** Called by {@link GraphNode#paintDecoration}. @see GraphPorts#paintStub */
    void paintPortEditorStub(CgUiPaintContext ctx, NodePort port, UIElement space) {
        ports.paintStub(ctx, port, space);
    }

    /** @see GraphPorts#editorFor */
    @Nullable
    PortDefaultEditor portEditorFor(NodePort port) {
        return ports.editorFor(port);
    }

    // ── The document seam ───────────────────────────────────────────────────

    /** The projection between the document and the widgets showing it. @see GraphDocumentSync */
    private final GraphDocumentSync documents = new GraphDocumentSync(this);

    /**
     * Adds a node, binding it to the document.
     *
     * <p>A widget arriving without a {@code nodeId} — anything built by hand rather than from a
     * {@link NodeData} — gets one, along with {@link NodeData} <b>derived from its own ports</b>. There
     * is no such thing as a node on this canvas the document does not know about, which is the property
     * every later feature depends on.</p>
     */
    @Override
    public GraphView addNode(UIElement node, float worldX, float worldY) {
        if (node instanceof GraphNode graphNode) {
            documents.addGraphNode(graphNode, worldX, worldY);
            return this;
        }
        super.addNode(node, worldX, worldY);
        return this;
    }

    @Override
    public GraphView moveNode(UIElement node, float worldX, float worldY) {
        super.moveNode(node, worldX, worldY);
        if (node instanceof GraphNode graphNode) documents.noteMoved(graphNode, worldX, worldY);
        else documents.markSynced();
        return this;
    }

    /**
     * The {@code typeId} given to a node built as a widget rather than from a document.
     *
     * <p>Honest rather than empty: it says the node was authored in the UI and has no library type
     * behind it, which is exactly what a loader needs to know to rebuild it as a placeholder.</p>
     */
    public static final String WIDGET_AUTHORED_TYPE = "crystalgui:widget";

    /** {@code super.addNode}, so the sync can place a widget without re-entering this class's override. */
    void addNodeDirect(GraphNode widget, float worldX, float worldY) {
        super.addNode(widget, worldX, worldY);
    }

    /** {@code super.moveNode}, likewise. */
    void moveNodeDirect(GraphNode widget, float worldX, float worldY) {
        super.moveNode(widget, worldX, worldY);
    }

    /** @see GraphPorts#watchAll */
    void watchPortsOf(GraphNode node) {
        ports.watchAll(node);
    }

    /** @see GraphPorts#forget */
    void forgetPortsOf(GraphNode node) {
        for (NodePort port : node.getPorts()) ports.forget(port);
    }

    /** @see GraphWires#refreshCounts */
    void refreshWireCounts(NodePort... changed) {
        wires.refreshCounts(changed);
    }

    /** @see GraphDocumentSync#markSynced */
    void markSynced() {
        documents.markSynced();
    }

    /** @see GraphDocumentSync#attachNode */
    void attachNode(GraphNode widget, NodeData data) {
        documents.attachNode(widget, data);
    }

    /** @see GraphDocumentSync#detachNode */
    void detachNode(GraphNode widget) {
        documents.detachNode(widget);
    }

    /** Called by {@link GraphNode#addPort}. @see GraphDocumentSync#syncPorts */
    void syncPorts(GraphNode widget) {
        documents.syncPorts(widget);
    }

    /** @see GraphDocumentSync#linkWidgets */
    void linkWidgets(EdgeData edge) {
        documents.linkWidgets(edge);
    }

    /** @see GraphDocumentSync#addNodeData */
    void addNodeData(NodeData data) {
        documents.addNodeData(data);
    }

    /** @see GraphDocumentSync#restoreEdge */
    void restoreEdge(EdgeData edge) {
        documents.restoreEdge(edge);
    }

    /** @see GraphDocumentSync#removeEdge */
    void removeEdgeFromDocument(EdgeData edge) {
        documents.removeEdge(edge);
    }

    /** The widget projecting {@code nodeId}, or null. */
    @Nullable
    public GraphNode widgetFor(String nodeId) {
        return documents.widgetFor(nodeId);
    }

    /** The port a {@link PortRef} names, or null if the node or the port is not on screen. */
    @Nullable
    public NodePort portFor(PortRef ref) {
        return documents.portFor(ref);
    }

    /** The {@link PortRef} naming a live port, or null before its node has been added. */
    @Nullable
    public static PortRef refFor(NodePort port) {
        GraphNode owner = port.node();
        if (owner == null || owner.getNodeId() == null) return null;
        return new PortRef(owner.getNodeId(), port.getPortId());
    }

    /** Replaces everything this view is showing. @see GraphDocumentSync#load */
    public GraphView load(GraphDocument source) {
        documents.load(source);
        return this;
    }

    /** Applies the document's pending changes to the widgets in place, and clears them.
     * @see GraphDocumentSync#applyPending
     * @return how many individual changes were applied */
    public int syncFromDocument() {
        return documents.applyPending();
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
        String id = node.getNodeId();
        NodeData data = id == null ? null : document.node(id);
        if (data == null) {
            // Never bound — nothing for the DOCUMENT to forget, but its ports may still have floating
            // default editors mounted on the plane; see detachNode's own note on why removeChild alone
            // never reaches them.
            for (NodePort port : node.getPorts()) ports.forget(port);
            content().remove(node);
            getSelection().prune(this);
            return this;
        }
        edits.begin("delete node");
        try {
            for (NodePort port : node.getPorts()) disconnectAll(port);
            edits.apply(new GraphEdits.DeleteNode(this, node, data));
        } finally {
            edits.end();
        }
        getSelection().prune(this);
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
        List<GraphNode> doomedNodes = getSelection().nodes();
        GraphConnection doomedWire = getSelection().wire();
        if (doomedNodes.isEmpty() && doomedWire == null) return 0;

        edits.begin("delete");
        try {
            if (doomedWire != null) disconnect(doomedWire);
            for (GraphNode node : doomedNodes) removeNode(node);
        } finally {
            edits.end();
        }
        getSelection().clear();
        return doomedNodes.size() + (doomedWire == null ? 0 : 1);
    }

    // ── Copy / paste / duplicate ────────────────────────────────────────────

    /** What copying and pasting mean in a graph. @see GraphClipboard */
    private final GraphClipboard clipboard = new GraphClipboard(this);

    /** @see GraphClipboard#copySelection */
    @Nullable
    public GraphDocument copySelection() {
        return clipboard.copySelection();
    }

    /** @see GraphClipboard#paste */
    public List<GraphNode> paste(@Nullable GraphDocument clip, float offsetX, float offsetY) {
        return clipboard.paste(clip, offsetX, offsetY);
    }

    /** @see GraphClipboard#pasteAt */
    public List<GraphNode> pasteAt(@Nullable GraphDocument clip, float worldX, float worldY) {
        return clipboard.pasteAt(clip, worldX, worldY);
    }

    /** @see GraphClipboard#duplicateSelection */
    public List<GraphNode> duplicateSelection(float offsetX, float offsetY) {
        return clipboard.duplicateSelection(offsetX, offsetY);
    }

    /** The engine's clipboard seam onto this graph. @see GraphClipboard */
    public Clipboard<GraphDocument> clipboard() {
        return clipboard.asClipboard;
    }

    /** Every node currently on the plane, in insertion order. */
    public List<GraphNode> nodes() {
        List<GraphNode> found = new ArrayList<>();
        for (UIElement child : content().children()) {
            if (child instanceof GraphNode node) found.add(node);
        }
        return found;
    }

    // ── Stacking ────────────────────────────────────────────────────────────

    /** Monotonic, so the most recently raised node always outranks every node raised before it. */
    private int raiseCounter;

    /**
     * Brings {@code node} to the front, permanently.
     *
     * <p><b>Raising is interaction history, not selection state.</b> The first attempt keyed stacking off
     * {@code :checked} in the theme, which put a selected node on top and then dropped it back the moment
     * something else was selected — so a node you had deliberately brought forward sank behind a
     * neighbour again as soon as you clicked away. Every editor treats "the last node you touched" as
     * the top one and leaves it there.</p>
     *
     * <p>An ever-increasing {@code z-index} rather than reordering the children: the engine already sorts
     * and hit-tests by it, so paint order and click order stay the same answer, and nothing about the
     * tree moves — which matters because the node is under the pointer at the exact moment it is raised.
     * Written at IMPORTANT because it is runtime state, the same as a scrollbar thumb's position.</p>
     */
    public GraphView raise(GraphNode node) {
        final int next = ++raiseCounter;
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.zIndex(next));
        return this;
    }

    // ── Selection ───────────────────────────────────────────────────────────

    /**
     * What is selected. A model rather than a flag per node, so a marquee, a delete command and an
     * inspector all read one answer — see {@link GraphSelection}, including why selection is not
     * undoable.
     *
     * <p>The set itself is the surface's; this is the typed read of it.</p>
     */
    public GraphSelection getSelection() {
        return (GraphSelection) selection();
    }

    /**
     * The press rule every graph editor uses, and the one a naive implementation gets wrong.
     *
     * <p>Clicking one of five selected nodes in order to drag all five is the most common gesture there
     * is. "A press selects only that node" breaks it — the other four deselect and the drag moves one.
     * So on <b>press</b> a node that is already selected leaves the selection alone; only an unselected
     * one replaces it. Shift always toggles.</p>
     */
    public GraphView selectNode(GraphNode node, boolean additive) {
        if (additive) getSelection().toggle(node);
        else if (!getSelection().contains(node)) getSelection().selectOnly(node);
        return this;
    }

    public GraphView clearSelection() {
        getSelection().clear();
        return this;
    }

    public List<GraphNode> selectedNodes() {
        return getSelection().nodes();
    }

    /** Every node on the plane, selected. */
    public GraphView selectAll() {
        getSelection().replaceWith(nodes());
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

    /** @see GraphWires#canConnect */
    public boolean canConnect(@Nullable NodePort a, @Nullable NodePort b) {
        return wires.canConnect(a, b);
    }

    /** @see GraphWires#connect */
    @Nullable
    public GraphConnection connect(NodePort a, NodePort b) {
        return wires.connect(a, b);
    }

    /** @see GraphWires#disconnect */
    public boolean disconnect(GraphConnection connection) {
        return wires.disconnect(connection);
    }

    /** @see GraphWires#disconnectAll */
    public int disconnectAll(NodePort port) {
        return wires.disconnectAll(port);
    }

    /** @see GraphWires#connectionsOf */
    public List<GraphConnection> connectionsOf(NodePort port) {
        return wires.connectionsOf(port);
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
        UIDocument window = document();
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
        window.focus().requestPointerFocus(this);

        Vector2f world = screenToWorld(rawX, rawY);
        GraphConnection hit = wires.pick(world.x(), world.y());
        if (hit != null) {
            getSelection().selectOnly(hit);
            return true;
        }

        marquee().begin(rawX, rawY, isShiftHeld(), isAltHeld());
        return true;
    }

    @Nullable
    private Marquee marquee;

    @Nullable
    private MoveGesture moveGesture;

    /** The band. Built on first use, because it adds an overlay and a constructor is not the place. */
    Marquee marquee() {
        if (marquee == null) marquee = new Marquee(surface(), picking(), selection());
        return marquee;
    }

    /** Dragging what is selected, as one undo step. @see MoveGesture */
    public MoveGesture moveGesture() {
        if (moveGesture == null) moveGesture = new MoveGesture(surface(), policy(GraphPolicy.class), edits);
        return moveGesture;
    }

    /** @see GraphWires#pick */
    @Nullable
    public GraphConnection pickWire(float worldX, float worldY) {
        return wires.pick(worldX, worldY);
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
        List<GraphNode> selected = getSelection().nodes();
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
        Box cache = box();
        float viewW = cache.width(), viewH = cache.height();
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

    // ── The wire being dragged ──────────────────────────────

    void beginPendingWire(NodePort from) {
        wireLayer.beginPending(from);
    }

    void updatePendingWire(float planeX, float planeY) {
        wireLayer.updatePending(planeX, planeY);
    }

    /**
     * Ends the wire drag. When it landed on nothing and a library is set, this is where the contextual
     * create-node menu opens.
     *
     * <p>The plane-to-world conversion happens HERE rather than in the library: the drag reports plane
     * space (the port's own), and only the wire layer knows that offset. Two features meet at the hub
     * instead of one naming the other.</p>
     *
     * @param planeX where the wire was dropped, in the plane's own space
     */
    void endPendingWire(NodePort from, float planeX, float planeY, boolean connected) {
        wireLayer.endPending();
        if (connected) return;
        Box layerBox = wireLayer.box();
        float ox = layerBox == null ? 0f : layerBox.x();
        float oy = layerBox == null ? 0f : layerBox.y();
        library.offerFor(from, planeX - ox, planeY - oy);
    }

    // -- The node library ----------------------------------------------------

    /** Where new nodes come from. @see GraphNodeLibrary */
    private final GraphNodeLibrary library = new GraphNodeLibrary(this);

    /** @see GraphNodeLibrary#set */
    public GraphView setNodeLibrary(NodeTypeRegistry types, NodeWidgetFactory factory,
                                    TypeCompatibility rule) {
        library.set(types, factory, rule);
        return this;
    }

    /** The type library this graph creates nodes from, or null if none was set. */
    @Nullable
    public NodeTypeRegistry getNodeLibrary() {
        return library.types();
    }

    /** The factory turning a type into a widget, or null if none was set. */
    @Nullable
    public NodeWidgetFactory getNodeFactory() {
        return library.factory();
    }

    /** The create-node menu, once a library has been set. */
    @Nullable
    public NodeCreationMenu creationMenu() {
        return library.menu();
    }

    /** Opens the menu unfiltered, at a world position -- what Space does. @see GraphNodeLibrary#openAt */
    public GraphView openCreationMenu(float worldX, float worldY) {
        library.openAt(worldX, worldY);
        return this;
    }

    // ── Wire geometry ───────────────────────────────────────────────────────

    /** @see GraphWires#setBaseWidth */
    public GraphView setWireBaseWidth(float width) {
        wires.setBaseWidth(width);
        return this;
    }

    /** @see GraphWires#width */
    public float getWireWidth() {
        return wires.width();
    }

    /** @see GraphWires#feather */
    public float getWireFeather() {
        return wires.feather();
    }

    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            onLayoutSettled();
            return true;
        });
    }

}
