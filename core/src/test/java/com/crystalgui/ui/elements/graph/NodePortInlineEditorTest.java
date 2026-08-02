package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.shader.ShaderColorFieldWidget;
import com.crystalgui.graph.shader.ShaderVectorFieldWidget;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.config.control.ColorControl;
import com.crystalgui.ui.elements.config.control.NumberControl;
import com.crystalgui.ui.elements.config.control.VectorControl;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * <b>P6.1.8 step 8 / P6.3.9, end to end: an unconnected input's default-value editor.</b>
 *
 * <p>Unity draws this OUTSIDE the node — a small floating field beside the port, joined by a short stub
 * — not as a row inside the node's own box. {@code NodePort} only holds the reference
 * ({@link NodePort#getDefaultEditor()}); {@link GraphView} is what discovers it, mounts it onto
 * {@link GraphView#content()} while the port is blank, repositions it against the port's live
 * {@link NodePort#dotCenter()} every tick, and takes it back off the instant a wire lands. What this
 * exercises is the actual widget path: {@link NodeFieldBinder#attach} building a REAL
 * {@link ConfigControl} through {@link NodeFieldWidgets} and {@link GraphView} placing it for real, in a
 * REAL {@link GraphView} built the way the editor actually builds one
 * ({@link NodeWidgetFactory#placeholder}), not a hand-assembled stand-in.</p>
 */
public class NodePortInlineEditorTest extends UiTestBase {

    private UIWindow window;
    private GraphView view;

    private void openWindow() {
        view = new GraphView();
        view.layout(l -> l.width(600).height(400));
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        root.addChild(view);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(800, 600);
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
    }

    /** Ticks style, layout AND the view — {@code GraphView.tickFrame} is what discovers a newly-bound
     * default editor and mounts/repositions it, so a single {@code updateWithoutPainting} is not enough
     * the very first time (discovery itself lags one tick, same as {@code ShaderGraphPreviews}). */
    private void frame() {
        window.updateWithoutPainting();
        window.updateWithoutPainting();
    }

    /** A node with one port field (a scalar default on an unconnected input) and one body field. */
    private static NodeType numberPortType() {
        return NodeType.of("t:number-port").label("Node")
                .in("Value", "float", NodeField.number("Value", "Value", "0.5").onPort("Value"))
                .out("Out", "float")
                .field(NodeField.enumOf("Space", "Space", "Object", "World"))
                .build();
    }

    private GraphNode buildNode(NodeType type, GraphDocument document) {
        NodeData data = document.addNode(type.create(0f, 0f));
        GraphNode node = NodeWidgetFactory.placeholder(type, data,
                NodeWidgetFactory.PortTypeRegistryLookup.DEFAULT);
        node.bindToDocument(data.id(), data.typeId());
        view.addNode(node, 0f, 0f);
        return node;
    }

    /**
     * The whole point of the mechanism: the port field's control becomes the port's
     * {@link NodePort#getDefaultEditor()}, and the body field's lands in the node's controls row —
     * never crossed. Neither assertion here needs the view to have ticked; the binding is synchronous.
     */
    @Test
    public void aPortFieldBecomesThePortsEditorAndABodyFieldGoesInTheBody() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);

        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NodePort port = node.portNamed("Value");
        assertNotNull(port);
        assertNotNull("the port field's control must be the port's default editor", port.getDefaultEditor());
        assertTrue("it must be a real ConfigControl, not a bare widget",
                port.getDefaultEditor() instanceof NumberControl);

        UIElement controls = node.querySelector("." + GraphNode.CONTROLS_CLASS);
        assertNotNull(controls);
        assertTrue("the port field's control must NOT have leaked into the node's controls row",
                controls.querySelectorAll(".__config-control__").stream()
                        .noneMatch(c -> c == port.getDefaultEditor()));
        assertFalse("and the node must actually HAVE a body control for Space",
                controls.querySelectorAll(".__config-control__").isEmpty());
    }

    /**
     * Mount state is what shows the editor at all — verified here against a REAL control, mounted onto
     * a REAL {@link GraphView} plane, not the placeholder {@code UIElement} other tests use.
     *
     * <p>Checked via {@code getAttachedWindow()}, not {@code getParent()}: {@code GraphView} wraps the
     * control in a label+dot row (Unity's {@code X 0 •}) and mounts/unmounts THAT, so the control's own
     * {@code getParent()} is the row and stays non-null even while the row itself is off the plane — a
     * detached subtree keeps its own internal structure. {@code getAttachedWindow()} is cleared and
     * restored recursively by {@code removeChild}/{@code addChild}, which is what actually answers "is
     * this live right now".</p>
     */
    @Test
    public void theEditorIsMountedOnlyWhileThePortIsUnconnected() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        NodePort port = node.portNamed("Value");
        UIElement editor = port.getDefaultEditor();
        assertNotNull("blank: the editor must actually be on the plane, not merely present",
                editor.getAttachedWindow());

        port.setConnectionCount(1);
        frame();
        assertNull("connected: taken off the plane entirely", editor.getAttachedWindow());

        port.setConnectionCount(0);
        frame();
        assertNotNull("disconnected again: it must come back", editor.getAttachedWindow());
    }

    /**
     * <b>The editor must land beside its OWN node, not off in the corner of the plane.</b>
     *
     * <p>Placed at world (0, 0) — as every other test in this file does, for simplicity — the bug this
     * pins is invisible: a coordinate that has been offset by the plane's own on-screen origin lands in
     * the same place as one that has not, because the origin being wrongly added is itself zero-ish at
     * world (0, 0). Only a node parked away from the origin exposes it, which is exactly what a real
     * graph looks like and exactly what a screenshot caught that this suite did not.</p>
     */
    @Test
    public void theEditorLandsBesideItsOwnNodeEvenFarFromWorldOrigin() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        NodeData data = document.addNode(type.create(400f, 260f));
        GraphNode node = NodeWidgetFactory.placeholder(type, data,
                NodeWidgetFactory.PortTypeRegistryLookup.DEFAULT);
        node.bindToDocument(data.id(), data.typeId());
        view.addNode(node, 400f, 260f);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement editor = node.portNamed("Value").getDefaultEditor();
        var nodeBounds = view.worldBoundsOf(node);
        var editorBounds = view.worldBoundsOf(editor);

        // A floor and a ceiling, not an exact pixel: the editor sits a small gap to the LEFT of the
        // node and roughly level with its port row, never hundreds of world units away in any
        // direction — which is what "the plane's own origin got added a second time" looks like.
        assertTrue("editor must be near the node horizontally: dx=" + (nodeBounds.x() - editorBounds.x()),
                Math.abs(nodeBounds.x() - editorBounds.x()) < 200f);
        assertTrue("editor must be near the node vertically: dy=" + (nodeBounds.y() - editorBounds.y()),
                Math.abs(nodeBounds.y() - editorBounds.y()) < 200f);
    }

    /**
     * A deleted node must take its port's floating editor with it — the failure mode
     * {@code GraphView.forgetPortEditor} exists for: the editor is not the node's descendant, so
     * removing the node alone cannot reach it.
     */
    @Test
    public void removingTheNodeRemovesItsFloatingEditorToo() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement editor = node.portNamed("Value").getDefaultEditor();
        assertNotNull(editor.getAttachedWindow());

        view.removeNode(node);
        frame();
        assertNull("the floating editor must not survive its node", editor.getAttachedWindow());
    }

    /**
     * <b>Unity's {@code X [0] •}, structurally.</b> {@link PortDefaultEditor} wraps the axis label and
     * the bare control in a rounded box — the control's own {@code getParent()}, since
     * {@code NodePort.getDefaultEditor()} still returns the bare {@link NumberControl} — and mounts a
     * SEPARATE dot directly on {@link GraphView#content()}, never as a descendant of the box. See
     * {@code PortDefaultEditor}'s own class javadoc for why: independent positioning is what lets the dot
     * visually overlap the box without a flex-layout coupling that would move the whole box instead the
     * moment the dot's offset changes.
     */
    @Test
    public void theEditorBoxAndItsDotAreSeparateElements() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        NodePort port = node.portNamed("Value");
        UIElement control = port.getDefaultEditor();
        UIElement box = control.getParent();
        assertNotNull("the control must be parented into the editor's own box", box);
        assertTrue("the control's parent must carry EDITOR_CLASS", box.hasClass(NodePort.EDITOR_CLASS));
        assertSame("the box is mounted straight onto the plane", view.content(), box.getParent());

        boolean hasLabel = false;
        for (UIElement child : box.getChildren()) {
            if (child instanceof com.crystalgui.ui.elements.UIText text
                    && text.hasClass(NodePort.EDITOR_LABEL_CLASS)) {
                assertEquals("the axis label is the port's own id", "Value", text.getText());
                hasLabel = true;
            }
        }
        assertTrue("box must carry the axis label", hasLabel);

        PortDefaultEditor editor = view.portEditorFor(port);
        assertNotNull("an editor must be tracked for this port", editor);
        assertTrue("it must be mounted while the port is blank", editor.isMounted());
        UIElement dot = editor.dot();
        assertTrue(dot.hasClass(NodePort.EDITOR_DOT_CLASS));
        assertSame("the dot is ALSO mounted straight onto the plane, never inside the box",
                view.content(), dot.getParent());
        assertNotSame("box and dot are two distinct elements", box, dot);
        boolean hasRing = dot.getChildren().stream()
                .anyMatch(c -> c.hasClass(NodePort.EDITOR_DOT_RING_CLASS));
        assertTrue("the dot must hold its grey ring layer", hasRing);
        boolean hasCore = dot.getChildren().stream()
                .filter(c -> c.hasClass(NodePort.EDITOR_DOT_RING_CLASS))
                .flatMap(ring -> ring.getChildren().stream())
                .anyMatch(c -> c.hasClass(NodePort.EDITOR_DOT_CORE_CLASS));
        assertTrue("the ring must hold its coloured core layer", hasCore);
    }

    /** Typing into the port's control writes back through the SAME undo-aware path a body field uses —
     * {@code NodeFieldBinder.write}, not a shortcut that skips {@code SetNodeFieldEdit}. */
    @Test
    public void editingThePortControlWritesToTheDocument() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NumberControl control = (NumberControl) node.portNamed("Value").getDefaultEditor();
        control.field().setText("2.5");

        assertEquals("2.5", document.node(node.getNodeId()).properties().get("Value"));
    }

    /**
     * A vec2 port default is real VECTOR (two cells), and a vec4 default is COLOR (a swatch) — both
     * per {@code ShaderGraphBridge.widgetKindFor}. Neither is a bare textfield any more, and both must
     * fit once actually mounted and laid out on the plane, not merely constructed.
     */
    @Test
    public void aVectorAndAColorPortEditorBothFitOnceMounted() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:composite-ports").label("Node")
                .in("UV", "vec2", new NodeField("UV", "UV", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.000, 0.000)", null))
                .in("Tint", "vec4", NodeField.color("Tint", "Tint", "vec4(1.000, 1.000, 1.000, 1.000)"))
                .out("Out", "vec4")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement uvEditor = node.portNamed("UV").getDefaultEditor();
        UIElement tintEditor = node.portNamed("Tint").getDefaultEditor();
        assertTrue(uvEditor instanceof VectorControl);
        assertTrue(tintEditor instanceof ColorControl);

        // A floor, not an exact match — VECTOR/COLOR sizing here is intentionally looser than the kit
        // height enforced elsewhere (see graph.css's own note on this being a third, tighter scale).
        // What must never happen is either collapsing to zero, which is what "the CSS never reached the
        // new element" looks like.
        assertTrue("a vector port editor must have real height", height(uvEditor) > 0f);
        assertTrue("a colour port editor must have real height", height(tintEditor) > 0f);
        assertTrue("a vector port editor must have real width", uvEditor.getRuntimeCache().getWidth() > 0f);
        assertTrue("a colour port editor must have real width", tintEditor.getRuntimeCache().getWidth() > 0f);
    }

    private static float height(UIElement e) {
        return e.getRuntimeCache().getHeight();
    }
}
