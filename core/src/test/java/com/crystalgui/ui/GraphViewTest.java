package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.graph.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphConnection;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.canvas.WorldRect;
import com.crystalgui.ui.elements.graph.PortType;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.2.3 — nodes, ports and wires.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Not "a connection can be made" — that is the easy half and it is visible in the harness. What these
 * pin are the four rules taken from Unity's documentation that the reference screenshots could not show,
 * because each is a behaviour rather than an appearance: <b>one edge per input but many per output</b>
 * (so an occupied input is <em>replaced</em>, not refused), <b>the inline editor appears exactly while
 * an input is unconnected</b>, <b>collapsing hides unconnected ports rather than only the body</b>, and
 * type compatibility deciding what may connect at all.</p>
 *
 * <p>Plus the one thing a screenshot would show wrongly if it were broken: the whole gesture, driven
 * through {@code consumeMouseEvent}, because a connection that can only be made by calling
 * {@code connect()} is not a graph editor.</p>
 */
public class GraphViewTest extends UiTestBase {

    private static final PortType FLOAT = new BasicPortType("float", 1);
    private static final PortType VEC3 = new BasicPortType("vec3", 3);

    private UIWindow window;
    private GraphView graph;

    @Before
    public void setUp() {
        graph = new GraphView();
        graph.layout(l -> l.width(360).height(300));

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(2f);
        // The graph's structure is unusable without the user-agent sheet — two port columns would stack,
        // and the dot has no intrinsic size, so there would be nothing to aim at. Themed too, because
        // the port palette is what NodePort.typeColor() reads back.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(800, 800);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private GraphNode node(String title, float x, float y) {
        GraphNode node = new GraphNode(title);
        graph.addNode(node, x, y);
        return node;
    }

    /** Physical pointer position of an element's centre, through the engine's own matrix — so it stays
     * correct under uiScale and the plane's pan/zoom without the test redoing that maths. */
    private Vector2f physicalCenterOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f,
                cache.getY() + cache.getHeight() * 0.5f);
    }

    private void mouseTo(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(Math.round(p.x()), Math.round(p.y()), 0, 0, -1, false, 0f, -1L));
    }

    private void press(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void release(Vector2f p) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(p.x()), Math.round(p.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }

    /** The whole gesture: press a port, drag past the activation threshold, release over another. */
    private void dragConnect(NodePort from, NodePort to) {
        Vector2f start = physicalCenterOf(from);
        Vector2f end = physicalCenterOf(to);
        press(start);
        frame();
        mouseTo(new Vector2f(start).lerp(end, 0.5f)); // past the 4px threshold, and mid-flight
        frame();
        mouseTo(end);
        frame();
        release(end);
        frame();
    }

    // ── The gesture ─────────────────────────────────────────────────────────

    /**
     * <b>A connection made the way a user makes one.</b>
     *
     * <p>Driven through {@code consumeMouseEvent} rather than by calling {@code connect()}, because
     * everything between the two — the payload drag, the per-frame acceptance re-read, the drop landing
     * on the port under the pointer — is where this can be broken while every unit of it passes.</p>
     */
    @Test
    public void draggingFromAnOutputToAnInputConnectsThem() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        dragConnect(out, in);

        assertEquals(1, graph.getConnections().size());
        GraphConnection edge = graph.getConnections().get(0);
        assertSame(out, edge.from());
        assertSame(in, edge.to());
        assertTrue(out.isConnected());
        assertTrue(in.isConnected());
    }

    /** And the same gesture in reverse — dragging out of an input onto an output — is the same edge.
     * A user does not think in terms of which end they started from. */
    @Test
    public void theDragWorksInEitherDirection() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        dragConnect(in, out);

        assertEquals(1, graph.getConnections().size());
        assertSame(out, graph.getConnections().get(0).from());
    }

    /** A drop on empty canvas connects nothing and leaves no half-made edge behind. */
    @Test
    public void aDropOnNothingLeavesNoEdge() {
        GraphNode a = node("A", 20f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        frame();

        Vector2f start = physicalCenterOf(out);
        press(start);
        frame();
        mouseTo(new Vector2f(start.x() + 120f, start.y() + 200f));
        frame();
        release(new Vector2f(start.x() + 120f, start.y() + 200f));
        frame();

        assertTrue(graph.getConnections().isEmpty());
        assertFalse(out.isConnected());
    }

    // ── Moving and selecting ────────────────────────────────────────────────

    /**
     * <b>A node follows the pointer exactly, in world units, at any zoom.</b>
     *
     * <p>The delta a {@code DragListener} reports has already been through the inverse transform, so
     * the world movement is the pointer movement divided by the zoom with no arithmetic here — and
     * that is worth pinning at two zoom levels, because a missing division looks perfect at 1x.</p>
     */
    @Test
    public void draggingANodeMovesItInWorldUnits() {
        GraphNode a = node("A", 40f, 40f);
        a.addOutput(VEC3, "Out");
        frame();

        Vector2f grab = physicalCenterOf(a.titleBar());
        press(grab);
        frame();
        mouseTo(new Vector2f(grab.x() + 60f, grab.y() + 40f)); // 60x40 physical at uiScale 2 -> 30x20 logical
        frame();
        release(new Vector2f(grab.x() + 60f, grab.y() + 40f));
        frame();

        WorldRect moved = graph.worldBoundsOf(a);
        assertEquals(70f, moved.x(), 0.6f);
        assertEquals(60f, moved.y(), 0.6f);
        assertFalse(a.isMoving());
    }

    @Test
    public void aNodeTracksThePointerAtZoomToo() {
        GraphNode a = node("A", 40f, 40f);
        a.addOutput(VEC3, "Out");
        graph.setZoom(2f);
        frame();

        Vector2f grab = physicalCenterOf(a.titleBar());
        press(grab);
        frame();
        // 60 physical / uiScale 2 = 30 logical, / zoom 2 = 15 world.
        mouseTo(new Vector2f(grab.x() + 60f, grab.y()));
        frame();
        release(new Vector2f(grab.x() + 60f, grab.y()));
        frame();

        assertEquals(55f, graph.worldBoundsOf(a).x(), 0.6f);
    }

    /**
     * A press on a control does not drag the node.
     *
     * <p>Ports stop the event themselves, so they were never the risk. A {@code Dropdown} opens a
     * popover and lets the press carry on, so without this carve-out nudging the mouse while choosing
     * an option drags the node out from under the open menu.</p>
     */
    @Test
    public void aDragThatStartsOnAControlDoesNotMoveTheNode() {
        GraphNode a = node("A", 40f, 40f);
        UIElement control = new UIElement().layout(l -> l.width(40).height(10));
        a.addControl("Space", control);
        frame();

        Vector2f grab = physicalCenterOf(control);
        press(grab);
        frame();
        mouseTo(new Vector2f(grab.x() + 60f, grab.y() + 40f));
        frame();

        assertFalse(a.isMoving());
        assertEquals(40f, graph.worldBoundsOf(a).x(), 0.6f);
    }

    /** Pressing a node selects it and drops the previous selection; Shift adds instead. */
    @Test
    public void pressingANodeSelectsItAndFocusesIt() {
        GraphNode a = node("A", 40f, 40f);
        GraphNode b = node("B", 240f, 40f);
        a.addOutput(VEC3, "Out");
        b.addOutput(VEC3, "Out");
        frame();

        press(physicalCenterOf(a.titleBar()));
        frame();
        assertTrue(a.isSelected());
        assertTrue("a selected node is the focused one — Delete and the inspector both key off it",
                a.isFocused());
        release(physicalCenterOf(a.titleBar()));
        frame();

        press(physicalCenterOf(b.titleBar()));
        frame();
        assertTrue(b.isSelected());
        assertFalse("a plain press replaces the selection", a.isSelected());
        assertEquals(1, graph.selectedNodes().size());
    }

    /** A node with nothing to preview is the height of its ports — the slot is attached on first ask. */
    @Test
    public void thePreviewSlotIsNotThereUntilItIsAskedFor() {
        GraphNode bare = node("A", 40f, 40f);
        bare.addOutput(VEC3, "Out");
        GraphNode previewed = node("B", 240f, 40f);
        previewed.addOutput(VEC3, "Out");
        previewed.preview();
        frame();

        assertFalse(bare.hasPreview());
        assertTrue(previewed.hasPreview());
        assertTrue("an empty preview slot would make every node the same tall box",
                graph.worldBoundsOf(bare).height() < graph.worldBoundsOf(previewed).height());
    }

    // ── The four rules from Unity's docs ────────────────────────────────────

    /**
     * <b>One edge per input, many per output — so an occupied input is replaced.</b>
     *
     * <p>The rule that is easiest to get backwards: refusing the second connection looks like correct
     * validation and makes rewiring a node take two deliberate gestures instead of one. The displaced
     * edge must also be gone, not merely hidden — 6.2.4 turns this into one undoable command, and it
     * can only do that because there is a single code path that removes an edge.</p>
     */
    @Test
    public void connectingToAnOccupiedInputReplacesTheEdge() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 20f, 140f);
        GraphNode target = node("T", 220f, 20f);
        NodePort outA = a.addOutput(VEC3, "Out");
        NodePort outB = b.addOutput(VEC3, "Out");
        NodePort in = target.addInput(VEC3, "A");
        frame();

        graph.connect(outA, in);
        graph.connect(outB, in);

        assertEquals("the input holds exactly one edge", 1, graph.getConnections().size());
        assertSame(outB, graph.getConnections().get(0).from());
        assertFalse("the displaced source is no longer connected", outA.isConnected());
        assertTrue(outB.isConnected());
    }

    @Test
    public void anOutputFeedsManyInputs() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        GraphNode c = node("C", 220f, 140f);
        NodePort out = a.addOutput(VEC3, "Out");
        frame();

        graph.connect(out, b.addInput(VEC3, "A"));
        graph.connect(out, c.addInput(VEC3, "A"));

        assertEquals(2, graph.getConnections().size());
        assertEquals(2, out.getConnectionCount());
    }

    /**
     * <b>The inline editor is visible exactly while the input is unconnected.</b>
     *
     * <p>Unity's `X 0.9` field, and the reason a graph is usable before it is finished. Driven by
     * {@code nodeport:blank} rather than by Java, which is why this asserts a <em>computed</em>
     * display value — the pseudo-class has to survive a real connection, and a stale style match is
     * the likely failure.</p>
     */
    @Test
    public void theInlineEditorHidesOnceTheInputIsConnected() {
        // Implementing PortType directly rather than extending BasicPortType, which is a record and
        // therefore final — and that is the intended shape: a type with an editor is not a plain id.
        PortType editable = new PortType() {
            @Override public String id() { return "float"; }
            @Override public int arity() { return 1; }
            @Override public UIElement createInlineEditor() { return new UIElement(); }
        };
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(editable, "Out");
        NodePort in = b.addInput(editable, "A");
        frame();

        UIElement editor = in.getInlineEditor();
        assertNotNull("an input port must offer its type's editor", editor);
        assertNotEquals("visible while unconnected", TaffyDisplay.NONE, displayOf(editor));

        graph.connect(out, in);
        frame();
        assertEquals("hidden once a wire supplies the value", TaffyDisplay.NONE, displayOf(editor));

        graph.disconnect(graph.getConnections().get(0));
        frame();
        assertNotEquals("and back when the wire goes", TaffyDisplay.NONE, displayOf(editor));
    }

    /**
     * <b>Collapsing hides unconnected ports, not just the body.</b>
     *
     * <p>Unity's rule, and the one that makes the feature worth having: a collapsed node in a busy graph
     * should be a title bar plus the wires that actually attach to it. Hiding only the body leaves a
     * node nearly as tall as before, which is the version that feels broken.</p>
     */
    @Test
    public void collapsingHidesUnconnectedPortsButKeepsWiredOnes() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort wired = b.addInput(VEC3, "A");
        NodePort bare = b.addInput(VEC3, "B");
        UIElement preview = b.preview(); // asked for before the frame — preview() attaches the slot lazily
        graph.connect(out, wired);
        frame();

        b.setCollapsed(true);
        frame();

        assertEquals("an unconnected port goes", TaffyDisplay.NONE, displayOf(bare));
        assertNotEquals("a wired one stays, or its wire would end in mid-air",
                TaffyDisplay.NONE, displayOf(wired));
        assertEquals(TaffyDisplay.NONE, displayOf(preview));

        b.setCollapsed(false);
        frame();
        assertNotEquals(TaffyDisplay.NONE, displayOf(bare));
    }

    // ── What may connect ────────────────────────────────────────────────────

    @Test
    public void incompatibleTypesAreRefused() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(FLOAT, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        assertFalse(graph.canConnect(out, in));
        assertNull(graph.connect(out, in));
        assertTrue(graph.getConnections().isEmpty());
    }

    @Test
    public void aPortCannotConnectToItsOwnNodeOrItsOwnDirection() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort ownInput = a.addInput(VEC3, "In");
        NodePort otherOut = b.addOutput(VEC3, "Out");
        frame();

        assertFalse("a node feeding itself is a cycle by construction", graph.canConnect(out, ownInput));
        assertFalse("two outputs is not a wire", graph.canConnect(out, otherOut));
        assertFalse(graph.canConnect(out, out));
    }

    @Test
    public void theSameEdgeIsNotAddedTwice() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        frame();

        assertNotNull(graph.connect(out, in));
        assertNull("re-connecting the same pair is a no-op, not a second edge", graph.connect(out, in));
        assertEquals(1, graph.getConnections().size());
    }

    /** A node leaving takes its wires with it — otherwise the layer paints edges to ports that are no
     * longer in the tree, whose layout is stale, i.e. wires to nowhere. */
    @Test
    public void removingANodeDropsItsWires() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        NodePort out = a.addOutput(VEC3, "Out");
        NodePort in = b.addInput(VEC3, "A");
        graph.connect(out, in);
        frame();

        graph.removeNode(b);
        frame();

        assertTrue(graph.getConnections().isEmpty());
        assertFalse(out.isConnected());
    }

    // ── The wire layer ──────────────────────────────────────────────────────

    /**
     * <b>The wire layer is exempt from culling, and that is not an optimisation detail.</b>
     *
     * <p>Culling asks an element's box where it is. The layer's box says nothing about where its wires
     * are, so left cullable it vanishes the moment the view leaves world origin — taking every wire with
     * it while every node stays visible. This exact failure has already been shipped once, in the
     * gallery, which is why it has a test rather than a comment.</p>
     */
    @Test
    public void theWireLayerSurvivesPanningAwayFromTheOrigin() {
        GraphNode a = node("A", 20f, 20f);
        a.addOutput(VEC3, "Out");
        frame();

        graph.setPan(-4000f, -4000f);
        frame();

        assertTrue(graph.isCullExempt(graph.wireLayer()));
        assertFalse("the layer must never be culled", graph.isCulled(graph.wireLayer()));
    }

    /**
     * <b>A wire keeps a visible thickness when zoomed out.</b>
     *
     * <p>Stroke widths scale with the pose — correct, and what makes a wire thicken as you zoom in like
     * a border does. The same rule takes a 2px wire to a fifth of a pixel at 0.2x, which is a graph that
     * looks empty. Clamped against the canvas's own zoom rather than in the shader, so the stroke maths
     * stays linear for every other consumer of {@code curve()}.</p>
     */
    @Test
    public void wireWidthIsClampedSoItSurvivesZoomingOut() {
        graph.setZoom(1f);
        float atOne = graph.getWireWidth();

        graph.setZoom(0.2f);
        float zoomedOut = graph.getWireWidth();

        assertTrue("the clamp must widen the stroke, not narrow it", zoomedOut > atOne);
        assertTrue("and it must survive the pose scaling it back down",
                zoomedOut * 0.2f >= 1f);

        graph.setZoom(4f);
        assertEquals("zoomed IN the base width stands, or wires would thin out",
                atOne, graph.getWireWidth(), 1e-4f);
    }

    private static TaffyDisplay displayOf(UIElement element) {
        return element.getStyle().taffyBridge.style.display;
    }
}
