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
 * <b>P6.1.8 step 8, end to end: an unconnected input's inline editor.</b>
 *
 * <p>The slot itself ({@code NodePort.setInlineEditor}, {@code nodeport:blank}, the click-vs-drag
 * routing in {@code beginConnectionDrag}) predates this step and is untouched by it. What was never
 * exercised by any test — not even {@code NodeFieldTest}, which stops at the document layer — is the
 * actual widget path: {@code NodeFieldBinder.attach} building a REAL {@link ConfigControl} through
 * {@link NodeFieldWidgets} and landing it on a REAL {@link NodePort}, in a REAL {@link GraphNode} built
 * the way the editor actually builds one ({@link NodeWidgetFactory#placeholder}), not a hand-assembled
 * stand-in.</p>
 */
public class NodePortInlineEditorTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;

    private void openWindow() {
        root = new UIElement();
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(800, 600);
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
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
        root.addChild(node);
        return node;
    }

    /**
     * The whole point of the mechanism: the port field's control lands ON THE PORT, and the body
     * field's lands in the node's controls row — never crossed.
     */
    @Test
    public void aPortFieldGoesOnItsPortAndABodyFieldGoesInTheBody() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);

        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NodePort port = node.portNamed("Value");
        assertNotNull(port);
        assertNotNull("the port field's control must be the port's inline editor", port.getInlineEditor());
        assertTrue("it must be a real ConfigControl, not a bare widget",
                port.getInlineEditor() instanceof NumberControl);

        UIElement controls = node.querySelector("." + GraphNode.CONTROLS_CLASS);
        assertNotNull(controls);
        assertTrue("the port field's control must NOT have leaked into the node's controls row",
                controls.querySelectorAll(".__config-control__").stream()
                        .noneMatch(c -> c == port.getInlineEditor()));
        assertFalse("and the node must actually HAVE a body control for Space",
                controls.querySelectorAll(".__config-control__").isEmpty());
    }

    /** {@code nodeport:blank} is what shows the editor at all — verified here against a REAL control,
     * not the placeholder {@code UIElement} other tests use. */
    @Test
    public void theEditorShowsOnlyWhileThePortIsUnconnected() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NodePort port = node.portNamed("Value");
        UIElement editor = port.getInlineEditor();
        assertTrue("blank: the editor must actually be visible, not merely present",
                editor.getRuntimeCache().getHeight() > 0f);

        port.setConnectionCount(1);
        window.updateWithoutPainting();
        assertEquals("connected: display:none collapses it to zero height",
                0f, editor.getRuntimeCache().getHeight(), 0.01f);

        port.setConnectionCount(0);
        window.updateWithoutPainting();
        assertTrue("disconnected again: it must come back",
                editor.getRuntimeCache().getHeight() > 0f);
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

        NumberControl control = (NumberControl) node.portNamed("Value").getInlineEditor();
        control.field().setText("2.5");

        assertEquals("2.5", document.node(node.getNodeId()).properties().get("Value"));
    }

    /**
     * A vec2 port default is real VECTOR (two cells), and a vec4 default is COLOR (a swatch) — both
     * per {@code ShaderGraphBridge.widgetKindFor}. Neither is a bare textfield any more, and neither
     * had ANY test before this file: the port editor's CSS was written for the shape the widget had
     * before P6.1.8, and only measuring it proves the new shape still fits the row.
     */
    @Test
    public void aVectorAndAColorPortEditorBothFitInThePortRow() {
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
        window.updateWithoutPainting();

        UIElement uvEditor = node.portNamed("UV").getInlineEditor();
        UIElement tintEditor = node.portNamed("Tint").getInlineEditor();
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

        // The node itself must still fit its ports rather than clipping them — the row is a floor
        // (`nodeport { height: var(--graph-port-h) }`), never a cap.
        assertTrue("the UV row must not be shorter than its editor",
                height(node.portNamed("UV")) >= height(uvEditor) - 0.5f);
        assertTrue("the Tint row must not be shorter than its editor",
                height(node.portNamed("Tint")) >= height(tintEditor) - 0.5f);
    }

    private static float height(UIElement e) {
        return e.getRuntimeCache().getHeight();
    }
}
