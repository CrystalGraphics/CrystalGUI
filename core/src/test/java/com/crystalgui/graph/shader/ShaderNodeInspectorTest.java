package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.13 — the Node Settings tab is bound to the selection, and its rows write through.
 *
 * <h3>What these are really pinning</h3>
 * <p>Not that a panel can be built — that it is the <b>same binding</b> as the node's own editors. The
 * inspector deliberately has no writer of its own, so the tests that matter are the ones asserting that
 * a change made here reaches the document and that the two hosts of a field stay in step.</p>
 */
public class ShaderNodeInspectorTest extends UiTestBase {

    private final CgShaderNodeRegistry shaderNodes = CgShaderNodeRegistry.builtins();

    private GraphView graph;
    private NodeTypeRegistry library;
    private ShaderNodeInspector inspector;
    private UIWindow window;
    private int recompiles;

    private void mount() {
        graph = new GraphView();
        library = ShaderGraphBridge.asNodeLibrary(shaderNodes);
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);

        inspector = new ShaderNodeInspector(graph, library, () -> recompiles++);

        UIElement root = new UIElement().layout(l -> l.width(900).height(600));
        root.addChild(graph);
        root.addChild(inspector);
        window = new UIWindow(Ui.of(root));
        window.init(900, 600);
        window.updateWithoutPainting();
    }

    private GraphNode add(String typeId, float x, float y) {
        NodeType type = library.get(typeId);
        assertNotNull("this fixture needs " + typeId, type);
        GraphNode node = graph.getNodeFactory().create(type, type.create(x, y));
        graph.addNode(node, x, y);
        return node;
    }

    /** Every header the panel has put on screen, so a test can say what state it is showing. */
    private String header() {
        for (ConfigControl control : inspector.controls().values()) {
            if (control.descriptor() != null
                    && control.descriptor().kind() == com.crystalgui.ui.elements.config.ConfigDescriptor.Kind.HEADER) {
                return control.descriptor().label();
            }
        }
        return "";
    }

    // ── The five states ─────────────────────────────────────────────────────

    /** An empty panel reads as broken, so nothing-selected is a stated state rather than no rows. */
    @Test
    public void nothingSelectedSaysSo() {
        mount();
        assertEquals(ShaderNodeInspector.EMPTY_MESSAGE, header());
    }

    @Test
    public void selectingANodeShowsItsLabel() {
        mount();
        GraphNode multiply = add("cg:Math/Basic/multiply", 0f, 0f);
        graph.getSelection().selectOnly(multiply);
        assertEquals(library.get("cg:Math/Basic/multiply").label(), header());
    }

    @Test
    public void deselectingReturnsToTheEmptyState() {
        mount();
        graph.getSelection().selectOnly(add("cg:Math/Basic/multiply", 0f, 0f));
        graph.getSelection().clear();
        assertEquals(ShaderNodeInspector.EMPTY_MESSAGE, header());
    }

    @Test
    public void severalNodesReportTheCount() {
        mount();
        GraphNode a = add("cg:Math/Basic/multiply", 0f, 0f);
        GraphNode b = add("cg:Math/Basic/multiply", 200f, 0f);
        graph.getSelection().replaceWith(List.of(a, b));
        assertEquals("2 nodes selected", header());
    }

    // ── The rebuild rule ────────────────────────────────────────────────────

    /**
     * <b>Re-asserting the same selection must not rebuild.</b>
     *
     * <p>{@code GraphSelection} emits for operations that leave the selection identical — a press on an
     * already-selected node re-asserts it, which is the gesture that starts a multi-node drag. A rebuild
     * there replaces every control in the panel, and the standing rule is that a widget must never
     * rebuild what it is being dragged on. Identity of the controls is the only way to see it.</p>
     */
    @Test
    public void reAssertingTheSameSelectionDoesNotRebuild() {
        mount();
        GraphNode node = add("cg:Input/Basic/color", 0f, 0f);
        graph.getSelection().selectOnly(node);

        ConfigControl before = inspector.controls().values().iterator().next();
        graph.selectNode(node, false);          // the press path, on an already-selected node
        graph.getSelection().selectOnly(node);  // and the blunt one

        ConfigControl after = inspector.controls().values().iterator().next();
        assertSame("the panel must be the same controls, not equivalent ones", before, after);
    }

    /**
     * <b>A rebuild REPLACES the panel; it must never append to it.</b>
     *
     * <p>The bug this exists for: {@code clearAllChildren()} deliberately skips internal children, and a
     * {@code Configurator} marks itself internal — so the clear removed nothing and every selection
     * stacked another copy of the panel below the last. On screen it read as the inspector keeping a
     * history of everything ever selected, headers and all.</p>
     *
     * <p>Asserts the row COUNT after cycling selections, because every individual row was correct
     * throughout — there was simply an unbounded number of them.</p>
     */
    @Test
    public void rebuildingReplacesTheRowsRatherThanAppending() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode time = add("cg:Input/Basic/time", 200f, 0f);

        graph.getSelection().selectOnly(colour);
        int forOneNode = inspector.getChildren().size();
        assertTrue("the fixture needs the panel to actually build rows", forOneNode > 0);

        for (int cycle = 0; cycle < 4; cycle++) {
            graph.getSelection().selectOnly(time);
            graph.getSelection().clear();
            graph.getSelection().selectOnly(colour);
        }

        assertEquals("the panel grew instead of being replaced",
                forOneNode, inspector.getChildren().size());
    }

    /**
     * Deselecting must REPLACE what was there, not append the empty state under it.
     *
     * <p>Asserts the child count, and that is the whole point of the test. The obvious assertion — count
     * the headers in {@code controls()} — <b>passes even with the bug present</b>, because
     * {@code clearRows} really did clear the control index; it was only the child elements that survived.
     * So the index always described the latest build while the screen showed every build ever made, and
     * every value-level assertion agreed with the index.</p>
     */
    @Test
    public void theEmptyStateReplacesWhatWasThere() {
        mount();
        graph.getSelection().selectOnly(add("cg:Input/Basic/color", 0f, 0f));
        int forOneNode = inspector.getChildren().size();

        graph.getSelection().clear();
        assertEquals(ShaderNodeInspector.EMPTY_MESSAGE, header());
        assertTrue("the empty state must be smaller than a node's panel, not added to it",
                inspector.getChildren().size() < forOneNode);
    }

    /** A genuinely different selection does rebuild. */
    @Test
    public void aDifferentSelectionRebuilds() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode time = add("cg:Input/Basic/time", 200f, 0f);

        graph.getSelection().selectOnly(colour);
        String first = header();
        graph.getSelection().selectOnly(time);
        assertNotEquals(first, header());
    }

    // ── Binding ─────────────────────────────────────────────────────────────

    /**
     * <b>A row writes through to the document, and the change is undoable.</b>
     *
     * <p>The inspector has no writer of its own — this is {@code NodeFieldBinder}'s path, reached from a
     * third host. If it were ever given one, this is the test that would notice.</p>
     */
    @Test
    public void editingARowWritesToTheDocumentAndUndoes() {
        mount();
        GraphNode node = add("cg:Input/Basic/color", 0f, 0f);
        graph.getSelection().selectOnly(node);

        ConfigControl control = firstEditable();
        assertNotNull("the colour node must expose an editable field", control);

        String before = storedValueFor(node, control);
        control.setValueObject(newValueFor(control));
        control.changed.emit(control.getValueObject());

        String after = storedValueFor(node, control);
        assertNotEquals("the row must reach the document", before, after);
        assertTrue("and the host must be told to recompile", recompiles > 0);

        graph.undoStack().undo();
        assertEquals("and Ctrl+Z must reach it", before, storedValueFor(node, control));
    }

    /** A connected port keeps its row, disabled — see the class note on why it does not vanish. */
    @Test
    public void aConnectedPortKeepsItsRowDisabled() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode multiply = add("cg:Math/Basic/multiply", 240f, 0f);
        graph.connect(colour.getOutputPorts().get(0), multiply.getInputPorts().get(0));

        graph.getSelection().selectOnly(multiply);

        ConfigControl connected = inspector.control(multiply.getInputPorts().get(0).getPortId());
        assertNotNull("the row must still be there — a vanished control looks like one that never was",
                connected);
        assertFalse("but it must not be editable, because the literal is unused", connected.isEnabled());

        ConfigControl free = inspector.control(multiply.getInputPorts().get(1).getPortId());
        assertNotNull(free);
        assertTrue("the unconnected one stays live", free.isEnabled());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ConfigControl firstEditable() {
        for (ConfigControl control : inspector.controls().values()) {
            if (control.isEnabled() && control.descriptor() != null
                    && control.descriptor().kind()
                        != com.crystalgui.ui.elements.config.ConfigDescriptor.Kind.HEADER) {
                return control;
            }
        }
        return null;
    }

    private String storedValueFor(GraphNode node, ConfigControl control) {
        var data = graph.getDocument().node(node.getNodeId());
        String key = keyOf(control);
        return data == null || key == null ? null : data.properties().get(key);
    }

    private String keyOf(ConfigControl control) {
        for (var entry : inspector.controls().entrySet()) {
            if (entry.getValue() == control) return entry.getKey();
        }
        return null;
    }

    private Object newValueFor(ConfigControl control) {
        Object current = control.getValueObject();
        if (current instanceof Boolean flag) return !flag;
        if (current instanceof Double number) return number + 1d;
        if (current instanceof Integer whole) return whole + 1;
        return "changed";
    }
}
