package com.crystalgui.widget.graph;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.SetNodeFieldEdit;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.8 — the general node-field system.
 *
 * <h3>Why these are headless-shaped even though they sit in {@code test}</h3>
 * <p>Everything asserted here is graphDocument and declaration behaviour, with no widget and no GL. That is
 * the point of the design: an inline editor's <em>semantics</em> — what it stores, what it falls back to,
 * whether it is undoable — must be decidable without drawing anything, or the only way to test an editor
 * is to click it.</p>
 */
public class NodeFieldTest extends UiDocumentTestBase {

    private static NodeType colourNode() {
        return NodeType.of("cg:input/color").label("Color")
                .in("Value", "vec4", NodeField.color("Value", "Value", "vec4(1.0)"))
                .out("Out", "vec4")
                .field(NodeField.enumOf("Space", "Space", "Object", "World"))
                .build();
    }

    // ── Declaration ─────────────────────────────────────────────────────────

    /**
     * <b>A port field and a body field are the same mechanism, placed differently.</b>
     *
     * <p>The unification the whole design rests on: {@code Value} is edited inline on its port, while
     * {@code Space} sits in the node body, and neither needs a bespoke control.</p>
     */
    @Test
    public void portFieldsAndBodyFieldsAreBothDeclaredOnTheType() {
        NodeType type = colourNode();

        assertEquals(2, type.fields().size());
        assertTrue("Value edits its port inline", type.fieldForPort("Value").isPortField());
        assertEquals("Space sits in the body", 1, type.bodyFields().size());
        assertEquals("Space", type.bodyFields().get(0).id());
    }

    /**
     * <b>A port field's id IS the port id.</b>
     *
     * <p>So the value the editor writes and the literal a compiler reads for that port are one stored
     * entry. Two entries would need keeping in step, and would silently disagree the first time only one
     * of them was updated.</p>
     */
    @Test
    public void aPortFieldWritesThePortsOwnValue() {
        NodeField field = NodeField.color("anything", "Value", "vec4(1.0)").onPort("Value");

        assertEquals("Value", field.id());
        assertEquals("Value", field.portId());
    }

    /** A body field sharing a port's id would overwrite that port's literal, so it is refused. */
    @Test
    public void abodyFieldMayNotShadowAPort() {
        var builder = NodeType.of("x").in("Value", "vec4").field(NodeField.text("Value", "Value", ""));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("same id as a port"));
    }

    /** A node is born valid: field defaults are seeded, so nothing has to consult the type later. */
    @Test
    public void createSeedsFieldDefaults() {
        NodeData node = colourNode().create(0f, 0f);

        assertEquals("vec4(1.0)", node.properties().get("Value"));
        assertEquals("Object", node.properties().get("Space"));
    }

    /**
     * <b>A port field must find its port by ID, not by drawn label.</b>
     *
     * <p>{@code NodePort.getName()} returns the label, which carries the arity — {@code "Value(4)"}. A
     * lookup comparing that matches nothing, and nothing anywhere reports it: the binder just falls back
     * to putting the editor in the node's body, so every inline field silently became a body row.</p>
     */
    @Test
    public void aPortIsFoundByIdNotByItsDrawnLabel() {
        var port = new com.crystalgui.widget.graph.NodePort(
                com.crystalgui.graph.PortDirection.INPUT,
                new com.crystalgui.graph.port.BasicPortType("vec4", 4), "Value");

        assertEquals("the id is what a graphDocument and a field refer to", "Value", port.getPortId());
        assertEquals("the label is what the user sees", "Value(4)", port.getName());

        var node = new com.crystalgui.widget.graph.GraphNode("Color");
        node.addPort(port);
        assertSame(port, node.portNamed("Value"));
        assertNull("the label must not resolve", node.portNamed("Value(4)"));
    }

    /** An option a later build dropped falls back, rather than showing blank while something else runs. */
    @Test
    public void anUnknownEnumValueResolvesToTheDefault() {
        NodeField space = NodeField.enumOf("Space", "Space", "Object", "World");

        assertEquals("World", space.resolve("World"));
        assertEquals("Object", space.resolve("Tangent"));
        assertEquals("Object", space.resolve(null));
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /**
     * <b>A field change is undoable.</b>
     *
     * <p>It is graphDocument state — it changes what the node does and must survive a reload — so this
     * project's rule puts it through an {@code Edit}. The first dropdown shipped writing the graphDocument
     * directly, which meant Ctrl+Z silently skipped it.</p>
     */
    @Test
    public void aFieldChangeIsUndoable() {
        GraphDocument graphDocument = new GraphDocument();
        NodeData node = graphDocument.addNode(colourNode().create(0f, 0f));
        UndoStack undo = new UndoStack();

        undo.execute(SetNodeFieldEdit.of(graphDocument, node.id(), "Space", "World"));
        assertEquals("World", graphDocument.node(node.id()).properties().get("Space"));

        undo.undo();
        assertEquals("Object", graphDocument.node(node.id()).properties().get("Space"));

        undo.redo();
        assertEquals("World", graphDocument.node(node.id()).properties().get("Space"));
    }

    /**
     * Consecutive edits to the same field coalesce, so typing is one undo step rather than one per key.
     *
     * <p>The merged edit must keep the FIRST edit's "before", or undoing lands in the middle of the run.
     */
    @Test
    public void consecutiveEditsToOneFieldMerge() {
        GraphDocument graphDocument = new GraphDocument();
        NodeData node = graphDocument.addNode(colourNode().create(0f, 0f));

        SetNodeFieldEdit first = SetNodeFieldEdit.of(graphDocument, node.id(), "Value", "vec4(0.5)");
        first.apply();
        SetNodeFieldEdit second = SetNodeFieldEdit.of(graphDocument, node.id(), "Value", "vec4(0.25)");
        second.apply();

        var merged = (SetNodeFieldEdit) first.mergeWith(second);
        assertNotNull(merged);
        assertEquals("vec4(1.0)", merged.before());
        assertEquals("vec4(0.25)", merged.after());

        merged.undo();
        assertEquals("the whole run undoes at once",
                "vec4(1.0)", graphDocument.node(node.id()).properties().get("Value"));
    }

    /** Different fields must not merge, or two unrelated changes become one undo step. */
    @Test
    public void differentFieldsDoNotMerge() {
        GraphDocument graphDocument = new GraphDocument();
        NodeData node = graphDocument.addNode(colourNode().create(0f, 0f));

        SetNodeFieldEdit value = SetNodeFieldEdit.of(graphDocument, node.id(), "Value", "vec4(0.5)");
        SetNodeFieldEdit space = SetNodeFieldEdit.of(graphDocument, node.id(), "Space", "World");

        assertNull(value.mergeWith(space));
    }

    /** Setting what is already set must not reach the stack — an undo press that does nothing is a bug. */
    @Test
    public void aNoOpChangeIsRecognised() {
        GraphDocument graphDocument = new GraphDocument();
        NodeData node = graphDocument.addNode(colourNode().create(0f, 0f));

        assertFalse(SetNodeFieldEdit.of(graphDocument, node.id(), "Space", "Object").changesAnything());
        assertTrue(SetNodeFieldEdit.of(graphDocument, node.id(), "Space", "World").changesAnything());
    }

    /** An edit whose node was deleted must no-op rather than throw — undo order makes that reachable. */
    @Test
    public void anEditSurvivesItsNodeBeingGone() {
        GraphDocument graphDocument = new GraphDocument();
        NodeData node = graphDocument.addNode(colourNode().create(0f, 0f));
        SetNodeFieldEdit edit = SetNodeFieldEdit.of(graphDocument, node.id(), "Space", "World");
        graphDocument.removeNode(node.id());

        edit.apply();
        edit.undo();
    }
}
