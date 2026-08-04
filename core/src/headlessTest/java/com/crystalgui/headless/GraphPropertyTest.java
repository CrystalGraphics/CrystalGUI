package com.crystalgui.headless;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.PropertyEdits;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.14 — the property model.
 *
 * <p>Headless, like the rest of the document layer: a dedicated server authors and validates a graph it
 * will never draw, and a property is part of what it authors.</p>
 */
public class GraphPropertyTest {

    private GraphDocument doc() {
        return new GraphDocument();
    }

    // ── Naming ──────────────────────────────────────────────────────────────

    /**
     * <b>A reference is sanitised on the way in, not on the way out.</b>
     *
     * <p>Done at construction so a document can never hold a reference that will not compile. The
     * alternative is discovering it at emit time, by which point the offending keystroke is long past
     * and the error names a generated file the user never wrote.</p>
     */
    @Test
    public void aReferenceIsMadeLegal() {
        assertEquals("Unity's own derivation", "_Vec_prop", GraphProperty.referenceFor("Vec prop"));
        assertEquals("a leading underscore is added", "_x", GraphProperty.sanitiseReference("x"));
        assertEquals("illegal characters become underscores",
                "_a_b_c", GraphProperty.sanitiseReference("_a-b.c"));
        assertEquals("an empty name still yields an identifier",
                "_Property", GraphProperty.sanitiseReference(""));
        assertEquals("and a bare underscore is not one on its own",
                "_Property", GraphProperty.sanitiseReference("_"));
    }

    /**
     * <b>Renaming leaves the reference alone.</b>
     *
     * <p>Unity's behaviour, and not an oversight: once a reference exists, materials and scripts point at
     * it, so rewriting it on every rename would break all of them silently. A caller wanting both does
     * both.</p>
     */
    @Test
    public void renamingDoesNotRewriteTheReference() {
        GraphProperty tint = GraphProperty.of("Tint", "color", "(1,1,1,1)");
        assertEquals("_Tint", tint.reference());
        assertEquals("_Tint", tint.withName("Base Colour").reference());
    }

    /** A suggested name avoids collisions, case-insensitively — two identical pills help nobody. */
    @Test
    public void aSuggestedNameAvoidsCollisions() {
        GraphDocument document = doc();
        assertEquals("Colour", document.uniquePropertyName("Colour"));
        document.addProperty(GraphProperty.of("Colour", "color", ""));
        assertEquals("Colour (1)", document.uniquePropertyName("Colour"));
        document.addProperty(GraphProperty.of("colour (1)", "color", ""));
        assertEquals("case is not a difference", "Colour (2)", document.uniquePropertyName("Colour"));
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** Order is authored, so it is preserved and answerable. */
    @Test
    public void propertiesKeepTheirAuthoredOrder() {
        GraphDocument document = doc();
        document.addProperty(GraphProperty.of("A", "float", "0"));
        document.addProperty(GraphProperty.of("B", "vec2", "vec2(0,0)"));
        document.addProperty(GraphProperty.of("C", "color", ""));

        assertEquals(List.of("A", "B", "C"),
                document.properties().stream().map(GraphProperty::name).toList());
        assertEquals(1, document.indexOfProperty(document.properties().get(1).id()));
    }

    /** An id identifies exactly one property. */
    @Test
    public void aDuplicateIdIsRefused() {
        GraphDocument document = doc();
        GraphProperty one = GraphProperty.of("A", "float", "0");
        document.addProperty(one);
        assertThrows(IllegalArgumentException.class, () -> document.addProperty(one));
    }

    /** An edit keeps its position — a rename must not send it to the bottom of the Blackboard. */
    @Test
    public void replacingKeepsThePosition() {
        GraphDocument document = doc();
        document.addProperty(GraphProperty.of("A", "float", "0"));
        GraphProperty middle = document.addProperty(GraphProperty.of("B", "float", "0"));
        document.addProperty(GraphProperty.of("C", "float", "0"));

        document.replaceProperty(middle.withName("Renamed"));
        assertEquals(1, document.indexOfProperty(middle.id()));
        assertEquals("Renamed", document.property(middle.id()).name());
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    /**
     * <b>Undoing a delete puts it back where it was.</b>
     *
     * <p>The reason {@code Remove} records an index. Appending instead would silently reorder the
     * generated {@code Properties} block — a diff in the shader for an operation the user believes was
     * a no-op, and one nothing on screen would explain.</p>
     */
    @Test
    public void undoingADeleteRestoresThePosition() {
        GraphDocument document = doc();
        document.addProperty(GraphProperty.of("A", "float", "0"));
        GraphProperty middle = document.addProperty(GraphProperty.of("B", "float", "0"));
        document.addProperty(GraphProperty.of("C", "float", "0"));

        UndoStack undo = new UndoStack();
        undo.execute(PropertyEdits.Remove.of(document, middle.id()));
        assertEquals(2, document.propertyCount());

        undo.undo();
        assertEquals(3, document.propertyCount());
        assertEquals("back in the middle, not appended", 1, document.indexOfProperty(middle.id()));
    }

    @Test
    public void addingAndRemovingUndo() {
        GraphDocument document = doc();
        UndoStack undo = new UndoStack();
        GraphProperty tint = GraphProperty.of("Tint", "color", "(1,1,1,1)");

        undo.execute(PropertyEdits.Add.of(document, tint));
        assertEquals(1, document.propertyCount());
        undo.undo();
        assertEquals(0, document.propertyCount());
        undo.redo();
        assertEquals(1, document.propertyCount());
    }

    /** Consecutive edits to one property collapse, so typing a name is one undo step. */
    @Test
    public void consecutiveEditsToOnePropertyMerge() {
        GraphDocument document = doc();
        GraphProperty tint = document.addProperty(GraphProperty.of("T", "color", ""));
        UndoStack undo = new UndoStack();

        undo.beginMergeRun();
        for (String name : List.of("Ti", "Tin", "Tint")) {
            undo.execute(PropertyEdits.Change.of(document, document.property(tint.id()).withName(name)));
        }
        undo.endMergeRun();

        assertEquals("typing a name is one step", 1, undo.undoDepth());
        assertEquals("Tint", document.property(tint.id()).name());
        undo.undo();
        assertEquals("and it undoes to where the typing started", "T",
                document.property(tint.id()).name());
    }

    /** Edits to DIFFERENT properties never merge, however fast they arrive. */
    @Test
    public void editsToDifferentPropertiesDoNotMerge() {
        GraphDocument document = doc();
        GraphProperty a = document.addProperty(GraphProperty.of("A", "float", "0"));
        GraphProperty b = document.addProperty(GraphProperty.of("B", "float", "0"));
        UndoStack undo = new UndoStack();

        undo.beginMergeRun();
        undo.execute(PropertyEdits.Change.of(document, a.withName("A2")));
        undo.execute(PropertyEdits.Change.of(document, b.withName("B2")));
        undo.endMergeRun();
        assertEquals(2, undo.undoDepth());
    }

    @Test
    public void reorderingUndoes() {
        GraphDocument document = doc();
        GraphProperty a = document.addProperty(GraphProperty.of("A", "float", "0"));
        document.addProperty(GraphProperty.of("B", "float", "0"));
        document.addProperty(GraphProperty.of("C", "float", "0"));

        UndoStack undo = new UndoStack();
        undo.execute(PropertyEdits.Move.of(document, a.id(), 2));
        assertEquals(2, document.indexOfProperty(a.id()));
        undo.undo();
        assertEquals(0, document.indexOfProperty(a.id()));
    }

    // ── Serialisation ───────────────────────────────────────────────────────

    @Test
    public void aPropertyRoundTrips() {
        GraphDocument document = doc();
        document.addProperty(new GraphProperty("p1", "Tint", "_Tint", "color", "(1,0,0,1)",
                false, "Surface", java.util.Map.of("mode", "HDR")));

        Object encoded = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, document);
        GraphDocument reloaded = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, encoded);

        assertEquals(1, reloaded.propertyCount());
        GraphProperty back = reloaded.property("p1");
        assertNotNull(back);
        assertEquals("Tint", back.name());
        assertEquals("_Tint", back.reference());
        assertEquals("color", back.typeId());
        assertEquals("(1,0,0,1)", back.defaultValue());
        assertFalse("exposed must survive as false — the default is true, so this is the hard direction",
                back.exposed());
        assertEquals("Surface", back.category());
        assertEquals("HDR", back.option("mode"));
    }

    /** Order survives a save, because the generated Properties block follows it. */
    @Test
    public void orderSurvivesARoundTrip() {
        GraphDocument document = doc();
        for (String name : List.of("A", "B", "C", "D")) {
            document.addProperty(GraphProperty.of(name, "float", "0"));
        }
        GraphDocument reloaded = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE,
                GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, document));
        assertEquals(List.of("A", "B", "C", "D"),
                reloaded.properties().stream().map(GraphProperty::name).toList());
    }

    /** A graph with no properties encodes exactly as it did before they existed. */
    @Test
    public void anUntouchedDocumentIsUnchangedByTheFeature() {
        String encoded = String.valueOf(GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, doc()));
        assertFalse("an empty list must be omitted, not written as an empty array",
                encoded.contains("props"));
    }

    /** Two graphs differing only in a property must not be interchangeable in a content-addressed cache. */
    @Test
    public void aPropertyChangesTheContentHash() {
        GraphDocument bare = doc();
        GraphDocument withOne = doc();
        withOne.addProperty(new GraphProperty("p1", "Tint", "_Tint", "color", "", true, "", null));

        assertNotEquals(
                ContentHash.of(PlainOps.INSTANCE, GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, bare)),
                ContentHash.of(PlainOps.INSTANCE, GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, withOne)));
    }

    /** Clearing a document drops its properties — they describe the graph, not the editor. */
    @Test
    public void clearingDropsProperties() {
        GraphDocument document = doc();
        document.addProperty(GraphProperty.of("A", "float", "0"));
        document.clear();
        assertEquals(0, document.propertyCount());
    }
}
