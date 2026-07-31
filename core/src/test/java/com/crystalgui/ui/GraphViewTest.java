package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
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

    /**
     * <b>A long port name widens the node; it never wraps.</b>
     *
     * <p>Unity's rule — {@code "Sampling Coordinates(3)"} sits on one line and the node is simply wider.
     * Two separate things had to be wrong for it to wrap, and each looked harmless alone: the label
     * declared {@code text-overflow: ellipsis} with no {@code white-space}, and ellipsis only ever applies
     * to text that cannot wrap, so it silently never fired; and every box between the label and the node
     * carried a zero flex basis, which contributes nothing to a container's max-content size, so the node
     * could not have grown even once the text stopped wrapping.</p>
     *
     * <p>The row is a fixed 16px, so a wrapped label drew outside its own port row and stopped lining up
     * with the dot that names it — which is what made it obvious on screen.</p>
     */
    @Test
    public void aLongPortNameWidensTheNodeInsteadOfWrapping() {
        GraphNode shortNames = node("Short", 0f, 0f);
        shortNames.addInput(VEC3, "In");
        shortNames.addOutput(VEC3, "Out");

        GraphNode longNames = node("Perlin noise 3D", 0f, 140f);
        NodePort longPort = longNames.addInput(VEC3, "Sampling Coordinates");
        longNames.addOutput(FLOAT, "Value");
        frame();
        frame();
        frame();

        float narrow = shortNames.getRuntimeCache().getWidth();
        float wide = longNames.getRuntimeCache().getWidth();

        // Once the inline port editors arrived the floors came down (graphnode 96px, nodeport 43px), and
        // a two-port node is now sized by its COLUMNS rather than pinned to the floor — so this asserts a
        // band, not an exact value. The floor is the lower bound it may not go under; the upper bound is
        // what keeps "short" meaningfully narrower than the long-name case below.
        assertTrue("short names stay narrow: " + narrow, narrow >= 96f && narrow <= 115f);
        assertTrue("a long port name must widen the node, not wrap inside it: " + wide, wide > narrow);
        assertTrue("but never past the ceiling: " + wide, wide <= 320f);

        // The label stays on ONE line. Its row is 16px tall, so a second line is both taller than the row
        // and taller than the font — measuring the label is what catches it, since a wrapped label is
        // still present, still correct, and still the right width.
        UIElement label = longPort.querySelector("." + NodePort.LABEL_CLASS);
        assertNotNull(label);
        assertTrue("the port label wrapped to a second line: " + label.getRuntimeCache().getHeight(),
                label.getRuntimeCache().getHeight() <= 16f);
    }

    /**
     * <b>The input column hugs its content, the output column takes the slack, and the two always meet.</b>
     *
     * <p>Three layouts were tried and the first two are each visibly wrong. Two halves gives a node with
     * no inputs an empty input panel — a rect drawn for something that does not exist — and pins the
     * output panel at 50% however short {@code "Out"} is. Two huggers leaves a <em>hole</em> between the
     * panels on any node whose ports are narrower than {@code min-width}, with the node's own background
     * showing through the middle. Growing only the output column fixes all three at once.</p>
     */
    @Test
    public void portColumnsSizeToTheirContentAndVanishWhenEmpty() {
        GraphNode outputOnly = node("Position", 0f, 0f);
        outputOnly.addOutput(VEC3, "Out");

        GraphNode both = node("Perlin noise 3D", 0f, 140f);
        both.addInput(VEC3, "Sampling Coordinates");
        both.addOutput(FLOAT, "Value");
        frame();
        frame();
        frame();

        UIElement emptyInputs = outputOnly.querySelector("." + GraphNode.INPUTS_CLASS);
        assertNotNull(emptyInputs);
        assertEquals("a node with no inputs must not paint an input panel",
                0f, emptyInputs.getRuntimeCache().getWidth(), 0.5f);

        // The output column tracks its label rather than taking half the node.
        UIElement wideInputs = both.querySelector("." + GraphNode.INPUTS_CLASS);
        UIElement narrowOutputs = both.querySelector("." + GraphNode.OUTPUTS_CLASS);
        float nodeWidth = both.getRuntimeCache().getWidth();
        assertTrue("'Sampling Coordinates' is much longer than 'Value', so the columns cannot be equal",
                wideInputs.getRuntimeCache().getWidth() > narrowOutputs.getRuntimeCache().getWidth());
        assertTrue("and neither is simply half the node: " + narrowOutputs.getRuntimeCache().getWidth()
                        + " of " + nodeWidth,
                Math.abs(narrowOutputs.getRuntimeCache().getWidth() - nodeWidth * 0.5f) > 1f);

        // Exactly one 1px seam between them — no more (two content-hugging columns left a hole with the
        // node's own background showing through) and no less (they have to be visibly separated).
        assertEquals("the columns must be separated by the 1px seam and nothing wider",
                wideInputs.getRuntimeCache().getX() + wideInputs.getRuntimeCache().getWidth() + 1f,
                narrowOutputs.getRuntimeCache().getX(), 0.5f);

        // And on a node with no inputs the output panel spans the whole band, so it reads as one
        // uniform surface rather than a stripe pinned to the right.
        UIElement loneOutputs = outputOnly.querySelector("." + GraphNode.OUTPUTS_CLASS);
        assertTrue("an inputless node's band is all output panel: " + loneOutputs.getRuntimeCache().getWidth(),
                loneOutputs.getRuntimeCache().getWidth() > outputOnly.getRuntimeCache().getWidth() * 0.9f);
    }

    /**
     * <b>A one-letter port name still gets a panel worth looking at.</b>
     *
     * <p>Unity's {@code A(3)}/{@code B(3)} column holds roughly 40–50% of the node rather than
     * shrink-wrapping two characters. The floor sits on the port <em>row</em>: on the column it would
     * also apply to a node with no inputs, and on the label it would tell {@code UIText} an ancestor had
     * sized it, which permanently disables the grow-to-fit above.</p>
     */
    @Test
    public void shortPortNamesStillGetASubstantialInputPanel() {
        GraphNode add = node("Add", 0f, 0f);
        add.addInput(VEC3, "A");
        add.addInput(VEC3, "B");
        add.addOutput(VEC3, "Out");
        frame();
        frame();
        frame();

        float nodeWidth = add.getRuntimeCache().getWidth();
        float inputsWidth = add.querySelector("." + GraphNode.INPUTS_CLASS).getRuntimeCache().getWidth();
        float share = inputsWidth / nodeWidth;

        assertTrue("two characters must not shrink-wrap to a sliver; share was " + share,
                share >= 0.35f && share <= 0.6f);
    }

    /**
     * <b>Enter must not start a rubber band.</b>
     *
     * <p>Space/Enter on a focused element synthesize a {@code MouseEvent.Down}/{@code Up} so that
     * {@code Button} and {@code Checkbox} get keyboard activation with no keyboard code of their own.
     * {@code GraphView} has to be focusable for its command keys to resolve, so it received that press
     * as a left-click at wherever the cursor happened to be — and started a marquee that could not be
     * ended, because a marquee is released through the real pointer-up path which the synthesized Up
     * never reaches. It stayed on screen through the key release and every frame after.</p>
     *
     * <p>The signal is the DOM's: a keyboard-synthesized click carries {@code detail == 0}, and a real
     * press can never be 0 because the first one is 1.</p>
     */
    @Test
    public void enterOnTheFocusedGraphDoesNotStartAMarquee() {
        node("A", 20f, 20f);
        window.getInputHandler().requestFocus(graph);
        frame();
        assertSame(graph, window.getInputHandler().getFocusedElement());

        pressKey(CgKeyCodes.KEY_RETURN, true);
        frame();
        assertFalse("Enter is not a pointer press", graph.isMarqueeActive());

        pressKey(CgKeyCodes.KEY_RETURN, false);
        frame();
        assertFalse("and nothing is left behind on release", graph.isMarqueeActive());

        // Space has press-and-hold semantics and takes the same path, so it must be covered too.
        pressKey(CgKeyCodes.KEY_SPACE, true);
        frame();
        assertFalse("nor is Space", graph.isMarqueeActive());
        pressKey(CgKeyCodes.KEY_SPACE, false);
        frame();
        assertFalse(graph.isMarqueeActive());

        // A real press still does start one — the guard must not have disabled the feature.
        press(physicalCenterOf(graph));
        frame();
        assertTrue("a genuine pointer press still begins a marquee", graph.isMarqueeActive());
    }

    /**
     * <b>Hovering a wire marks it; selecting one is a different state again.</b>
     *
     * <p>A wire is painted rather than laid out, so it has no element and can never carry {@code :hover}
     * or {@code :checked} — both states have to be tracked by re-testing the pointer against the curves.
     * Hover thickens only, selection thickens <em>and</em> recolours to the accent, because "you would
     * hit this one" and "this one is the subject of your next command" must stay tellable apart.</p>
     */
    @Test
    public void aWireCanBeHoveredIndependentlyOfBeingSelected() {
        GraphNode from = node("From", 0f, 0f);
        GraphNode to = node("To", 260f, 0f);
        NodePort out = from.addOutput(VEC3, "Out");
        NodePort in = to.addInput(VEC3, "In");
        graph.connect(out, in);
        frame();
        frame();

        assertNull("nothing is hovered to begin with", graph.getHoveredWire());

        // Driven through the real pointer path rather than by computing world coordinates, which is both
        // closer to the thing being tested and avoids a space mix-up: dotCenter() is PLANE space, not
        // world, and pickWire converts by adding the layer's own origin.
        //
        // The midpoint of the two dots is exactly on the curve: for a cubic whose control points are the
        // endpoints offset horizontally, B(0.5) = (P0 + 3P1 + 3P2 + P3)/8 collapses to (P0 + P3)/2 — the
        // horizontal pulls cancel.
        Vector2f dotA = physicalCenterOf(out.querySelector("." + NodePort.DOT_CLASS));
        Vector2f dotB = physicalCenterOf(in.querySelector("." + NodePort.DOT_CLASS));
        mouseTo(new Vector2f((dotA.x() + dotB.x()) * 0.5f, (dotA.y() + dotB.y()) * 0.5f));
        frame();

        assertNotNull("moving over a wire must mark it hovered", graph.getHoveredWire());
        assertNull("and hovering is not selecting", graph.getSelection().wire());

        // Far below both nodes, where no wire runs.
        mouseTo(new Vector2f(dotA.x(), dotA.y() + 200f));
        frame();
        assertNull("and it clears when the pointer leaves", graph.getHoveredWire());
    }

    /**
     * <b>Every point along a wire is pickable, not just the ones near a sample.</b>
     *
     * <p>Picking measures the pointer against a polyline sampled off the cubic. Measuring to the sample
     * <em>points</em> makes the tolerance depend on their spacing: on a long wire the 24 samples sit tens
     * of units apart, so a point exactly on the curve halfway between two of them is further from the
     * nearest sample than the tolerance allows. That produces evenly-spaced dead spots — the wire is
     * plainly under the cursor and cannot be hit — which reads as random flakiness.</p>
     *
     * <p>A single-point test cannot see this; it passes or fails on where that one point happened to
     * fall. Sweeping the whole length is the only version that catches it, and the wire is made long on
     * purpose, because the gap between samples is what scales with length.</p>
     */
    @Test
    public void everyPointAlongAWireIsPickable() {
        GraphNode from = node("From", 0f, 0f);
        GraphNode to = node("To", 900f, 260f);
        NodePort out = from.addOutput(VEC3, "Out");
        NodePort in = to.addInput(VEC3, "In");
        graph.connect(out, in);
        frame();
        frame();

        // The drawn curve, reconstructed exactly as NodeWireLayer builds it: horizontal tangents pulled
        // half the horizontal separation. Plane space, which is what pickWire takes once the layer's own
        // origin is removed.
        Vector2f a = out.dotCenter(), b = in.dotCenter();
        float ox = graph.wireLayer().getRuntimeCache().getX();
        float oy = graph.wireLayer().getRuntimeCache().getY();
        float pull = Math.max(24f, Math.abs(b.x() - a.x()) * 0.5f);

        int misses = 0;
        for (int i = 0; i <= 200; i++) {
            float t = i / 200f, u = 1f - t;
            float x = u * u * u * a.x() + 3f * u * u * t * (a.x() + pull)
                    + 3f * u * t * t * (b.x() - pull) + t * t * t * b.x();
            float y = u * u * u * a.y() + 3f * u * u * t * a.y()
                    + 3f * u * t * t * b.y() + t * t * t * b.y();
            if (graph.wireLayer().pickWire(x - ox, y - oy) == null) misses++;
        }

        assertEquals("points sitting exactly on the drawn curve must all pick it", 0, misses);
    }

    /**
     * <b>Hover and selection are additive, and the combined rule really matches.</b>
     *
     * <p>Unity draws three distinct states: a muted 1px ring on hover, the 2px accent on selection, and a
     * 3px accent when both. The risk worth a test is not the widths but the <em>selector</em> —
     * {@code graphnode:hover} and {@code graphnode:checked} both weigh 11, so which wins between them is
     * decided by source order rather than specificity, and a compound {@code :checked:hover} that failed
     * to match would silently leave a hovered selection looking exactly like an unhovered one.</p>
     */
    @Test
    public void hoverAndSelectionStackIntoThreeDistinctRings() {
        GraphNode node = node("N", 20f, 20f);
        node.addOutput(VEC3, "Out");
        frame();
        frame();

        float plain = outlineWidthOf(node);

        graph.getSelection().selectOnly(node);
        frame();
        float selected = outlineWidthOf(node);

        mouseTo(physicalCenterOf(node));
        // TWO frames, and the second is not padding. Hover is resolved in endFrame(), so the element is
        // not marked hovered until this frame is over, and the selector re-match that reads it happens in
        // the NEXT frame's calculateStyle. Reading after one frame measures the state before the hover —
        // which showed up here as the combined rule appearing not to match at all.
        frame();
        frame();
        float selectedAndHovered = outlineWidthOf(node);

        graph.getSelection().clear();
        frame();
        frame();
        float hoveredOnly = outlineWidthOf(node);

        assertEquals("an idle node has no ring", 0f, plain, 0.01f);
        assertEquals("hover alone is the thin one", 1f, hoveredOnly, 0.01f);
        assertEquals("selection alone is the accent", 2f, selected, 0.01f);
        assertEquals("and the two ADD rather than one replacing the other",
                3f, selectedAndHovered, 0.01f);
    }

    private static float outlineWidthOf(UIElement element) {
        var value = element.getStyle().getComputed(
                com.crystalgui.style.property.StylePropertyRegistry.OUTLINE_WIDTH);
        return value == null ? 0f : value.resolve(0f);
    }

    /**
     * <b>A press on a control inside a node belongs to the control, not to the canvas.</b>
     *
     * <p>{@code GraphNode} stops propagation only for presses it turns into a move-drag; a press on its
     * controls it deliberately ignores so the widget underneath can have it. That press then reached
     * {@code GraphView}, which read it as empty canvas and started a marquee <em>with pointer capture</em>
     * while taking pointer focus — the capture swallows the release and the focus change closes whatever
     * popover the press just opened, so a {@code Dropdown} in a node was completely dead.</p>
     */
    @Test
    public void pressingAControlInsideANodeDoesNotStartAMarquee() {
        GraphNode node = node("N", 20f, 20f);
        node.addOutput(VEC3, "Out");
        UIElement control = new UIElement().layout(l -> l.width(40).height(12));
        node.addControl("Space", control);
        frame();
        frame();

        press(physicalCenterOf(control));
        frame();

        assertFalse("the canvas must not rubber-band from inside a node", graph.isMarqueeActive());
        assertNotSame("nor steal focus from what was pressed",
                graph, window.getInputHandler().getFocusedElement());

        release(physicalCenterOf(control));
        frame();
        assertFalse(graph.isMarqueeActive());
    }

    /**
     * <b>Clicking a second node selects it ALONE.</b>
     *
     * <p>Driven through {@code consumeMouseEvent}, because the model-level call is already covered and
     * was not where this broke: reported from the harness as every clicked node staying lit, so the
     * selection only ever grew.</p>
     */
    @Test
    public void clickingASecondNodeDeselectsTheFirst() {
        GraphNode first = node("First", 20f, 20f);
        first.addOutput(VEC3, "Out");
        GraphNode second = node("Second", 20f, 160f);
        second.addOutput(VEC3, "Out");
        frame();
        frame();

        press(physicalCenterOf(first));
        release(physicalCenterOf(first));
        frame();
        assertTrue(graph.getSelection().contains(first));

        press(physicalCenterOf(second));
        release(physicalCenterOf(second));
        frame();

        assertTrue("the one just clicked is selected", graph.getSelection().contains(second));
        assertFalse("and the previous one is NOT", graph.getSelection().contains(first));
        assertEquals(1, graph.selectedNodes().size());

        // The model dropping it is only half the claim. The ring is a `graphnode:checked` rule, and a
        // pseudo-class is only re-evaluated when something invalidates the match — so a correct model
        // with a stale cascade leaves the deselected node visibly ringed, which is indistinguishable on
        // screen from "it is still selected". Reported from the harness as exactly that, so the computed
        // value is what has to be asserted.
        frame();
        assertEquals("the deselected node must lose its ring, not just its model entry",
                0f, outlineWidthOf(first), 0.01f);
        assertTrue("and the newly selected one must have gained one", outlineWidthOf(second) > 0f);
    }

    private void pressKey(int keyCode, boolean down) {
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, down, false, 0L));
    }

    private static TaffyDisplay displayOf(UIElement element) {
        return element.getStyle().taffyBridge.style.display;
    }
}
