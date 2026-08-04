package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.control.InfoControl;
import com.crystalgui.ui.elements.config.control.VectorControl;
import com.crystalgui.ui.elements.graph.NodePort;
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

    /**
     * <b>An opened foldout stays open when the selection changes.</b>
     *
     * <p>A foldout is view state — how you are looking at the thing, not what the thing is — so it sits
     * on the same side of the line as scroll position and selection, and has to survive a rebuild. Before
     * this it did not: you opened {@code About} to read a node's type, clicked the next node, and it had
     * shut itself again.</p>
     *
     * <p>The state is remembered by <b>title</b>, because the group object itself is destroyed on every
     * rebuild — there is no identity to key on that outlives the thing being remembered.</p>
     */
    @Test
    public void anOpenedGroupStaysOpenAcrossSelections() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode time = add("cg:Input/Basic/time", 200f, 0f);

        graph.getSelection().selectOnly(colour);
        ConfiguratorGroup about = groupTitled("About");
        assertNotNull("the fixture needs an About group", about);
        assertTrue("About starts collapsed", about.isCollapsed());

        about.setCollapsed(false);
        graph.getSelection().selectOnly(time);

        ConfiguratorGroup rebuilt = groupTitled("About");
        assertNotNull(rebuilt);
        assertNotSame("the group really is a new object", about, rebuilt);
        assertFalse("but it must remember it was open", rebuilt.isCollapsed());
    }

    /** And a closed one stays closed — the memory is of the user's answer, not of "always open". */
    @Test
    public void aClosedGroupStaysClosedAcrossSelections() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode time = add("cg:Input/Basic/time", 200f, 0f);

        graph.getSelection().selectOnly(colour);
        groupTitled("About").setCollapsed(false);
        graph.getSelection().selectOnly(time);
        groupTitled("About").setCollapsed(true);
        graph.getSelection().selectOnly(colour);

        assertTrue("it must stay shut", groupTitled("About").isCollapsed());
    }

    private ConfiguratorGroup groupTitled(String title) {
        for (UIElement child : inspector.getChildren()) {
            if (child instanceof ConfiguratorGroup group && title.equals(group.title())) return group;
        }
        return null;
    }

    /**
     * <b>A fact is not a field.</b>
     *
     * <p>The About rows and the connected-port rows were disabled {@code TEXT} controls, which failed
     * twice over: they drew the full sunken input chrome, and {@code setEnabled(false)} on the wrapper
     * never reached the {@code TextField} inside — so a node's id and its resolved port types really were
     * editable. Typing into one changed nothing, which is the worst of both: it invites an edit and then
     * discards it.</p>
     *
     * <p>Asserts {@code consumesTextInput()}, because that is the property that actually matters — a
     * control which does not consume text has no caret and no way to receive a keystroke, whatever it
     * looks like.</p>
     */
    @Test
    public void factsAreNotEditable() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        graph.getSelection().selectOnly(colour);

        ConfigControl typeRow = inspector.control("Type");
        assertNotNull("the About group must state the node's type", typeRow);
        assertTrue("a fact must be an InfoControl, not a text box", typeRow instanceof InfoControl);
        assertFalse("and it must not take typing", consumesText(typeRow));
    }

    /** A connected port names its source, and that is a fact too — not something to type over. */
    @Test
    public void aConnectedPortNamesItsSourceAsAFact() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode multiply = add("cg:Math/Basic/multiply", 240f, 0f);
        graph.connect(colour.getOutputPorts().get(0), multiply.getInputPorts().get(0));
        graph.getSelection().selectOnly(multiply);

        ConfigControl connected = inspector.control(multiply.getInputPorts().get(0).getPortId());
        assertNotNull("the row must still be there", connected);
        assertTrue(connected instanceof InfoControl);
        assertFalse(consumesText(connected));
        assertTrue("it must say what drives it",
                String.valueOf(connected.getValueObject()).contains("Color"));
    }

    /** But a fact stays writable PROGRAMMATICALLY — the compile stats change on every emit. */
    @Test
    public void aFactCanStillBeRefreshedInPlace() {
        mount();
        graph.getSelection().selectOnly(add("cg:Input/Basic/color", 0f, 0f));
        ConfigControl typeRow = inspector.control("Type");
        typeRow.setValueObject("something else");
        assertEquals("something else", typeRow.getValueObject());
    }

    /** Nothing in the subtree takes text input — a control is only read-only if none of its parts is not. */
    private boolean consumesText(UIElement element) {
        if (element.consumesTextInput()) return true;
        for (UIElement child : element.getChildren()) {
            if (consumesText(child)) return true;
        }
        return false;
    }

    /**
     * <b>A dynamic port's row is the same shape as the node's own editor.</b>
     *
     * <p>{@code dynamic} has no width until the graph gives it one, so the <em>declaration</em> cannot say
     * how many boxes to draw — that is what dynamic means. The inspector built from the declaration and
     * showed one box holding {@code 1} beside a node already drawing {@code X Y}, because the resolved
     * answer lives on the port widget and only the node was asking it.</p>
     *
     * <p>Both now go through {@code ShaderPortArity}, which is the point: two places deciding the same
     * thing from different inputs is a disagreement that surfaces to a user rather than to a compiler.</p>
     */
    @Test
    public void aDynamicPortRowMatchesTheNodesOwnEditor() {
        mount();
        GraphNode vector = add("cg:Input/Basic/vector2", 0f, 0f);
        GraphNode multiply = add("cg:Math/Basic/multiply", 240f, 0f);
        graph.connect(vector.getOutputPorts().get(0), multiply.getInputPorts().get(0));
        ShaderPortArity.resolve(graph);
        window.updateWithoutPainting();

        NodePort b = multiply.getInputPorts().get(1);
        assertEquals("the fixture needs B to have widened to a vec2", 2, b.displayedArity());

        graph.getSelection().selectOnly(multiply);
        ConfigControl row = inspector.control(b.getPortId());
        assertNotNull(row);
        assertTrue("B resolved to 2 components, so its row must be a vector editor",
                row instanceof VectorControl);
        assertEquals("and with the same number of boxes the node draws",
                2, row.descriptor().arity());
    }

    /** A concretely typed port is never second-guessed — only {@code dynamic} is re-shaped. */
    @Test
    public void aConcretelyTypedPortIsLeftAlone() {
        mount();
        GraphNode output = add(ShaderGraphBridge.MASTER_TYPE, 0f, 0f);
        graph.getSelection().selectOnly(output);

        ConfigControl alpha = inspector.control("Alpha");
        assertNotNull("the master's Alpha is a declared float", alpha);
        assertFalse("so it must stay a scalar, whatever is wired elsewhere",
                alpha instanceof VectorControl);
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

    /**
     * A connected port keeps its row — see the class note on why it does not vanish — and the port beside
     * it stays a live editor.
     *
     * <p>What "not editable" means here changed once facts became {@link InfoControl}s: the row is not a
     * disabled field, it is not a field. {@link #aConnectedPortNamesItsSourceAsAFact} asserts that half;
     * this one is about the PAIR, because the failure worth catching is a rule that accidentally applies
     * to every port rather than only the connected one.</p>
     */
    @Test
    public void aConnectedPortKeepsItsRowAndItsNeighbourStaysEditable() {
        mount();
        GraphNode colour = add("cg:Input/Basic/color", 0f, 0f);
        GraphNode multiply = add("cg:Math/Basic/multiply", 240f, 0f);
        graph.connect(colour.getOutputPorts().get(0), multiply.getInputPorts().get(0));

        graph.getSelection().selectOnly(multiply);

        ConfigControl connected = inspector.control(multiply.getInputPorts().get(0).getPortId());
        assertNotNull("the row must still be there — a vanished control looks like one that never was",
                connected);
        assertTrue("and it is a fact, not a field", connected instanceof InfoControl);

        ConfigControl free = inspector.control(multiply.getInputPorts().get(1).getPortId());
        assertNotNull(free);
        assertFalse("the unconnected one is still a real editor", free instanceof InfoControl);
        assertTrue("and still live", free.isEnabled());
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
