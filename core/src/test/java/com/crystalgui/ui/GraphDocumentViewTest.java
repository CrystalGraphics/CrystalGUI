package com.crystalgui.ui;

import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortRef;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.graph.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import com.crystalgui.ui.elements.graph.PortType;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.2.5 — the view as a projection of a {@link GraphDocument}.
 *
 * <h3>What is actually being asserted</h3>
 * <p>The model's own rules are proven headlessly in {@code GraphDocumentTest}; 6.2.3 and 6.2.4 already
 * cover the widgets. What is new here is the <b>seam</b>: that every mutation made through the view
 * lands in the document, that a document can be opened into a view, and — the one that decides whether
 * the migration is real — that <b>an id survives delete-then-undo</b>, because every edge referencing
 * that node is restored by the same transaction and would otherwise point at nothing.</p>
 */
public class GraphDocumentViewTest extends UiTestBase {

    private static final PortType VEC3 = new BasicPortType("vec3", 3);

    private UIWindow window;
    private GraphView graph;

    @Before
    public void setUp() {
        graph = new GraphView();
        graph.layout(l -> l.width(400).height(320));

        UIElement root = new UIElement().layout(l -> l.width(440).height(360));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(800, 800);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
    }

    private GraphNode node(String title, float x, float y) {
        GraphNode node = new GraphNode(title);
        graph.addNode(node, x, y);
        return node;
    }

    // ── The write-through ───────────────────────────────────────────────────

    /**
     * <b>A node added as a widget is in the document too.</b>
     *
     * <p>6.2.3's API lets a caller build a {@code GraphNode} by hand, and that has to keep working — so
     * the view derives {@link NodeData} from the widget's own ports rather than refusing it. The
     * property that matters is the absence of an exception: there is no node on this canvas the
     * document does not know about, which is what save, duplicate and "send me your graph" all rest on.</p>
     */
    @Test
    public void aHandBuiltNodeStillLandsInTheDocument() {
        GraphNode widget = node("Position", 40f, 60f);
        NodePort out = widget.addOutput(VEC3, "Out");
        frame();

        assertNotNull("the widget must be bound", widget.getNodeId());
        NodeData data = graph.getDocument().node(widget.getNodeId());
        assertNotNull("and the document must hold it", data);
        assertEquals(40f, data.x(), 0.01f);
        assertEquals(60f, data.y(), 0.01f);
        assertEquals("a widget-authored node says so rather than claiming a library type",
                GraphView.WIDGET_AUTHORED_TYPE, data.typeId());
        assertSame("and the view can get back to the widget", widget, graph.widgetFor(widget.getNodeId()));
        assertNotNull("the port keeps its document id, not its drawn label", out.getPortId());
        assertEquals("Out", out.getPortId());
    }

    /** A move writes through: position is document data, because a reload has to give it back. */
    @Test
    public void movingANodeUpdatesTheDocument() {
        GraphNode widget = node("N", 10f, 10f);
        frame();

        graph.moveNode(widget, 120f, 200f);
        NodeData data = graph.getDocument().node(widget.getNodeId());

        assertEquals(120f, data.x(), 0.01f);
        assertEquals(200f, data.y(), 0.01f);
    }

    /** Connecting through the view produces a document edge, keyed on ids rather than widgets. */
    @Test
    public void connectingThroughTheViewWritesAnEdge() {
        GraphNode from = node("From", 0f, 0f);
        GraphNode to = node("To", 200f, 0f);
        NodePort out = from.addOutput(VEC3, "Out");
        NodePort in = to.addInput(VEC3, "In");
        frame();

        graph.connect(out, in);

        List<EdgeData> edges = graph.getDocument().edges();
        assertEquals(1, edges.size());
        assertEquals(new PortRef(from.getNodeId(), "Out"), edges.get(0).from());
        assertEquals(new PortRef(to.getNodeId(), "In"), edges.get(0).to());
    }

    // ── The one that decides whether the migration is real ──────────────────

    /**
     * <b>Delete a wired node, undo, and the id comes back unchanged.</b>
     *
     * <p>This is the property the whole migration turns on. The delete is one transaction: the wires go
     * first, then the node. Undo unwinds it in reverse, so the node is restored and <em>then</em> its
     * edges are — and every one of those edges names the node by id. A fresh id on the way back would
     * leave each edge pointing at a node that does not exist, and the wires would silently not return
     * while the node did.</p>
     */
    @Test
    public void deletingAndUndoingRestoresTheSameIdAndTheWires() {
        GraphNode source = node("Source", 0f, 0f);
        GraphNode target = node("Target", 200f, 0f);
        NodePort out = source.addOutput(VEC3, "Out");
        NodePort in = target.addInput(VEC3, "In");
        frame();
        graph.connect(out, in);

        String idBefore = target.getNodeId();
        assertEquals(1, graph.getDocument().edges().size());

        graph.removeNode(target);
        frame();
        assertNull("the node is gone from the document", graph.getDocument().node(idBefore));
        assertTrue("and its edge with it", graph.getDocument().edges().isEmpty());

        graph.undoStack().undo();
        frame();

        assertNotNull("the node is back", graph.getDocument().node(idBefore));
        assertEquals("with the SAME id — a fresh one would orphan every edge naming it",
                idBefore, target.getNodeId());
        assertEquals("and the wire came back with it", 1, graph.getDocument().edges().size());
        assertEquals(new PortRef(idBefore, "In"), graph.getDocument().edges().get(0).to());
        assertEquals("the view agrees", 1, graph.getConnections().size());
    }

    /** Redo after that undo must not mint a second copy either. */
    @Test
    public void redoDoesNotDuplicateTheNode() {
        GraphNode widget = node("N", 0f, 0f);
        frame();
        String id = widget.getNodeId();

        graph.removeNode(widget);
        graph.undoStack().undo();
        graph.undoStack().redo();
        frame();

        assertNull(graph.getDocument().node(id));
        assertEquals("nothing left behind on the canvas either", 0, graph.nodes().size());
    }

    // ── Opening a document ──────────────────────────────────────────────────

    /**
     * <b>A document opens into a view, wires included.</b>
     *
     * <p>The server-authored case, and the only place a wholesale rebuild is correct — there is no
     * interaction in flight. Every incremental change goes through the ordinary mutators instead,
     * because rebuilding detaches the element under the pointer.</p>
     */
    @Test
    public void aDocumentLoadsIntoTheView() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position")
                .out("Out", "vec3"));
        library.register(NodeType.of("shader.Add").label("Add")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        GraphDocument doc = new GraphDocument();
        NodeData position = doc.addNode(library.get("shader.Position").create(30f, 40f));
        NodeData add = doc.addNode(library.get("shader.Add").create(300f, 40f));
        doc.link(position, "Out", add, "A");

        graph.load(doc);
        frame();
        frame();

        assertEquals(2, graph.nodes().size());
        assertEquals("the edge came across", 1, graph.getConnections().size());
        assertNotNull(graph.widgetFor(position.id()));
        assertEquals("Position", graph.widgetFor(position.id()).getTitle());

        NodePort loadedOut = graph.portFor(new PortRef(position.id(), "Out"));
        assertNotNull("ports are addressable by document id after a load", loadedOut);
        assertTrue("and the connection count is live", loadedOut.isConnected());
    }

    /**
     * <b>The factory binds what it builds, so a reload keeps a node's identity.</b>
     *
     * <p>Found in the harness: every node came back titled {@code crystalgui:widget} with its controls
     * gone. The factory built the widgets correctly but never told them which {@code NodeData} they came
     * from, so the view could only file them as "authored as a widget" — and a reload then rebuilt them
     * through the placeholder path, titled by that pseudo-type.</p>
     *
     * <p>The binding belongs in the factory rather than at each call site precisely because a registered
     * builder is a consumer's lambda that usually ignores the data and just builds its widget. Every one
     * that forgot would produce this.</p>
     */
    @Test
    public void aFactoryBuiltNodeKeepsItsTypeAndTitleAcrossAReload() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        NodeData data = library.get("shader.Position").create(15f, 25f);
        GraphNode widget = graph.getNodeFactory().create(library.get("shader.Position"), data);
        graph.addNode(widget, 15f, 25f);
        frame();

        assertEquals("the factory bound it to the library type, not to the widget pseudo-type",
                "shader.Position", graph.getDocument().node(widget.getNodeId()).typeId());

        var encoded = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, graph.getDocument());
        graph.load(GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, encoded));
        frame();

        assertEquals(1, graph.nodes().size());
        assertEquals("and so it comes back named", "Position", graph.nodes().get(0).getTitle());
    }

    /**
     * <b>Even a hand-built node keeps its name.</b>
     *
     * <p>It has no library type to take a label from, so it stores its own. Its controls and preview
     * still cannot return — those are Java the document never saw — which is the honest limit of
     * building a node as a widget instead of registering a type for it.</p>
     */
    @Test
    public void aWidgetAuthoredNodeReloadsUnderItsOwnTitle() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);
        GraphNode widget = node("Hand Built", 0f, 0f);
        widget.addOutput(VEC3, "Out");
        frame();

        // A port declared AFTER the node joined the view must still reach the document — that is the
        // order 6.2.3's own examples use, and deriving the ports once at add time missed it.
        assertEquals("the document learned about the late port",
                1, graph.getDocument().node(widget.getNodeId()).ports().size());

        graph.load(GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE,
                GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, graph.getDocument())));
        frame();

        assertEquals("Hand Built", graph.nodes().get(0).getTitle());
        assertEquals("and its ports, which the document did store",
                1, graph.nodes().get(0).getOutputPorts().size());
    }

    /**
     * <b>A node whose type is not in the library still opens.</b>
     *
     * <p>The reason the document stores ports per node rather than looking them up from the type: a
     * missing plugin must give a grey box that round-trips, not an emptied graph. Deliberately unlike
     * {@code ElementRegistry}, which throws on an unknown tag — that is right for a UI description,
     * where both sides should be running identical code, and wrong for a file someone opened.</p>
     */
    @Test
    public void anUnknownNodeTypeStillOpens() {
        NodeTypeRegistry empty = new NodeTypeRegistry();
        graph.setNodeLibrary(empty, NodeWidgetFactory.of(empty).build(), (f, t) -> true);

        GraphDocument doc = new GraphDocument();
        doc.addNode(doc.newNode("some.mod.Missing").at(10f, 10f).in("In", "vec3").build());

        graph.load(doc);
        frame();

        assertEquals("the node is on screen rather than eaten", 1, graph.nodes().size());
        assertEquals("named by its type id, which is what tells a user what they lack",
                "some.mod.Missing", graph.nodes().get(0).getTitle());
    }

    /**
     * <b>Loading keeps the view's document instance, so anything bound to it stays bound.</b>
     *
     * <p>This is the property per-file editors rest on, and it was the one line that made them
     * impossible: {@code load} used to end in {@code this.document = source}. A host wires its panels to
     * {@code getDocument()} once, at construction — {@code ShaderGraphEditor} hands the same instance to
     * its Main Preview, its Blackboard and its own change listener — so a swap left every one of them
     * driving an <b>orphan</b>. Both halves keep working individually, which is why nothing would have
     * reported it: the board lists a graph that is not on screen and writes edits nobody can see.</p>
     */
    @Test
    public void loadingKeepsTheDocumentAnythingElseIsBoundTo() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        // What a panel does at construction: take the document once and listen to it.
        GraphDocument bound = graph.getDocument();
        int[] notified = {0};
        bound.onChanged.connect(() -> notified[0]++);

        GraphDocument file = new GraphDocument();
        file.addNode(library.get("shader.Position").create(7f, 8f));
        graph.load(file);
        frame();

        assertSame("the view must still be showing the instance the panel holds",
                bound, graph.getDocument());
        assertEquals("which therefore has the loaded graph in it", 1, bound.nodeCount());
        assertTrue("and the panel was told, or it would still be drawing the old graph",
                notified[0] > 0);
    }

    /** Everything a file carries comes back — positions, node settings, properties and graph settings. */
    @Test
    public void loadingRestoresPositionsPropertiesAndSettings() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        GraphDocument file = new GraphDocument();
        NodeData node = file.addNode(library.get("shader.Position").create(120f, 34f));
        file.addProperty(com.crystalgui.graph.GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        file.settings().setRaw(com.crystalgui.core.settings.SettingsLayer.DOCUMENT, "graph.probe", "on");

        graph.load(file);
        frame();

        GraphDocument live = graph.getDocument();
        assertEquals("the node landed where the file said", 120f, live.node(node.id()).x(), 0.01f);
        assertEquals("the Blackboard's properties came across", 1, live.propertyCount());
        assertEquals("Tint", live.properties().get(0).name());
        assertEquals("and the graph's own settings", "on",
                live.settings().raw("graph.probe"));
    }

    /**
     * <b>A load is not an edit — the undo stack is empty afterwards.</b>
     *
     * <p>Ctrl+Z straight after opening a file must not begin unpicking it a node at a time. Leaving the
     * <em>previous</em> file's history would be worse still: those entries describe a graph that is no
     * longer in this document, so undoing one edits nodes that never existed in it.</p>
     */
    @Test
    public void loadingIsNotUndoable() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        // Some real history first, so "empty" cannot pass by never having had anything.
        graph.addNode(graph.getNodeFactory().create(library.get("shader.Position"),
                library.get("shader.Position").create(0f, 0f)), 0f, 0f);
        graph.removeNode(graph.nodes().get(0));
        frame();
        assertTrue("this fixture needs history to clear", graph.undoStack().undoDepth() > 0);

        GraphDocument file = new GraphDocument();
        file.addNode(library.get("shader.Position").create(5f, 5f));
        graph.load(file);
        frame();

        assertEquals("opening a file is the starting state, not an action",
                0, graph.undoStack().undoDepth());
    }

    /** A file whose graph is empty still tells the panels, or they keep drawing the previous one. */
    @Test
    public void loadingAnEmptyGraphStillNotifies() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        GraphDocument first = new GraphDocument();
        first.addProperty(com.crystalgui.graph.GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        graph.load(first);
        frame();

        int[] notified = {0};
        graph.getDocument().onChanged.connect(() -> notified[0]++);

        // NOTHING in it. GraphDocument.clear() empties the property list after its last removeNode, and
        // restoreEdge never emits at all -- so without load's own emit this is silent and the Blackboard
        // goes on listing Tint.
        graph.load(new GraphDocument());
        frame();

        assertEquals("the properties went with the old file", 0, graph.getDocument().propertyCount());
        assertTrue("and something said so", notified[0] > 0);
    }

    // ── The other direction: document → view ────────────────────────────────

    /**
     * <b>A change made to the document reaches the screen, and untouched nodes keep their widget.</b>
     *
     * <p>This is the path a server, a paste or a command takes — it mutates the document, and the view
     * drains the changeset. The second assertion is the one with teeth: the node nobody touched must be
     * the <em>same instance</em> afterwards. Rebuilding would be far simpler and is exactly what a
     * changeset exists to avoid, because it detaches the element under the pointer — a drag's source
     * goes stale on its first update and every later frame feeds it garbage.</p>
     */
    @Test
    public void aDocumentChangeAppliesInPlaceWithoutRebuildingUntouchedNodes() {
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").out("Out", "vec3"));
        library.register(NodeType.of("shader.Add").label("Add").in("A", "vec3").out("Out", "vec3"));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), (f, t) -> true);

        GraphDocument doc = new GraphDocument();
        NodeData position = doc.addNode(library.get("shader.Position").create(0f, 0f));
        graph.load(doc);
        frame();
        GraphNode untouched = graph.widgetFor(position.id());
        assertNotNull(untouched);

        // What a server would do: mutate the document, not the view.
        //
        // THE VIEW'S document, which is not `doc`. `load` copies contents in rather than adopting the
        // object, so that a host's panels — bound to getDocument() at construction — cannot be left
        // driving an orphan. The consequence lands here: `doc` is a spent template once it has been
        // loaded, and editing it reaches nothing.
        GraphDocument live = graph.getDocument();
        NodeData add = live.addNode(library.get("shader.Add").create(220f, 30f));
        live.link(position, "Out", add, "A");
        live.moveNode(position.id(), 12f, 34f);

        int applied = graph.syncFromDocument();
        frame();

        assertTrue("something was applied", applied > 0);
        assertEquals("the new node appeared", 2, graph.nodes().size());
        assertNotNull(graph.widgetFor(add.id()));
        assertEquals("and the new edge", 1, graph.getConnections().size());
        assertSame("the node nobody touched must NOT have been rebuilt",
                untouched, graph.widgetFor(position.id()));
        assertEquals("the moved node followed", 12f,
                graph.getDocument().node(position.id()).x(), 0.01f);
        assertTrue("and the changeset was drained", graph.getDocument().changeset().isEmpty());
    }

    /** A removal through the document takes the widget with it. */
    @Test
    public void removingFromTheDocumentRemovesTheWidget() {
        GraphNode widget = node("N", 0f, 0f);
        frame();
        String id = widget.getNodeId();

        graph.getDocument().removeNode(id);
        graph.syncFromDocument();
        frame();

        assertNull(graph.widgetFor(id));
        assertEquals(0, graph.nodes().size());
    }

    /** Draining after a change the view made itself is a no-op — the two directions must not fight. */
    @Test
    public void syncingAfterTheViewsOwnChangeIsIdempotent() {
        GraphNode from = node("From", 0f, 0f);
        GraphNode to = node("To", 200f, 0f);
        graph.connect(from.addOutput(VEC3, "Out"), to.addInput(VEC3, "In"));
        frame();

        int nodesBefore = graph.nodes().size();
        int wiresBefore = graph.getConnections().size();
        graph.syncFromDocument();
        graph.syncFromDocument();
        frame();

        assertEquals(nodesBefore, graph.nodes().size());
        assertEquals(wiresBefore, graph.getConnections().size());
        assertSame("and it did not rebuild what was already there", from, graph.widgetFor(from.getNodeId()));
    }

    /**
     * <b>What the view built can be saved and reopened.</b>
     *
     * <p>The end-to-end claim of 6.2.5: a graph drawn with the mouse is a document, and a document is
     * bytes. Round-tripped through {@code PlainOps} rather than JSON, because that is the server path.</p>
     */
    @Test
    public void aGraphBuiltInTheViewRoundTripsThroughBytes() {
        GraphNode from = node("From", 0f, 0f);
        GraphNode to = node("To", 150f, 90f);
        graph.connect(from.addOutput(VEC3, "Out"), to.addInput(VEC3, "In"));
        frame();

        var encoded = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, graph.getDocument());
        GraphDocument reloaded = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, encoded);

        assertEquals(2, reloaded.nodeCount());
        assertEquals(1, reloaded.edges().size());
        assertEquals("ids are stored, so the edge still names the same node",
                from.getNodeId(), reloaded.edges().get(0).from().nodeId());
    }
}
