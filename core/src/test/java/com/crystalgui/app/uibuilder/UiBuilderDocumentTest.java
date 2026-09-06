package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.canvas.Artboard;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

/**
 * The document a builder edits: one undo history, a stable encoding, and every edit invertible.
 *
 * <p>"Every edit undoes to byte-identical" is the assertion the whole format rests on — a change that
 * cannot be undone exactly is a file that drifts every time somebody opens it.</p>
 */
public class UiBuilderDocumentTest {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"stylesheets\": [\"cguitest:sample\"],\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\", \"class\": \"page\",\n"
            + "    \"children\": [ { \"kind\": \"text\", \"id\": \"title\","
            + " \"state\": { \"text\": \"Status\" } } ] }\n"
            + "}\n";

    private static UiBuilderDocument open() {
        UIElementRegistry.bootstrap();
        return new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page");
    }

    private static String encoded(UiBuilderDocument document) {
        return new String(document.encode(), StandardCharsets.UTF_8);
    }

    @Test
    public void itOpensTheTreeAndTheHeader() {
        UiBuilderDocument document = open();

        assertEquals("root", document.root().id());
        assertEquals(List.of("cguitest:sample"), document.stylesheets());
        assertNotNull(document.root().getElementById("title"));
    }

    /** The same tree encodes the same bytes, twice running. */
    @Test
    public void encodingIsStable() {
        UiBuilderDocument document = open();

        assertEquals(encoded(document), encoded(document));
    }

    @Test
    public void anEmptyFileOpensRatherThanFailing() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument document = new UiBuilderDocument(new byte[0], "test:new");

        assertNotNull(document.root());
        assertTrue(encoded(document).contains("\"cgui\""));
    }

    @Test
    public void everyEditUndoesToByteIdentical() {
        UiBuilderDocument document = open();
        String before = encoded(document);
        UIElement root = document.root();
        UIElement title = root.getElementById("title");

        List<BuilderEdit> edits = List.of(
                new BuilderEdit.SetId(title, title.id(), "renamed"),
                new BuilderEdit.SetClasses(root, List.copyOf(root.classes()), List.of("page", "wide")),
                new BuilderEdit.SetState(title, "text", new JsonPrimitive("Status"),
                        new JsonPrimitive("Changed")),
                new BuilderEdit.SetAttribute<>(title, Attribute.HIDDEN, false, true),
                new BuilderEdit.Insert(root, new UIText("added"), 1),
                new BuilderEdit.Remove(root, title, 0),
                new BuilderEdit.SetExtra(document.extras(), title, DocumentExtras.BIND, null,
                        new JsonPrimitive("subject")));

        for (BuilderEdit edit : edits) {
            document.apply(edit);
            assertFalse("the edit changed something: " + edit.label(), before.equals(encoded(document)));
            document.history().undo();
            assertEquals("undoing " + edit.label() + " must return the file", before, encoded(document));
        }
    }

    /** A move is one edit, never a remove and an insert — those lose the node. */
    @Test
    public void aMoveIsOneStep() {
        UiBuilderDocument document = open();
        UIElement root = document.root();
        UIElement title = root.getElementById("title");
        UIElement second = new UIText("second");
        document.apply(new BuilderEdit.Insert(root, second, 1));
        String before = encoded(document);

        document.apply(new BuilderEdit.Move(title, root, 0, root, 1));

        assertEquals(second, root.children().get(0));
        document.history().undo();
        assertEquals(before, encoded(document));
    }

    @Test
    public void severalEditsCanBeOneStep() {
        UiBuilderDocument document = open();
        UIElement root = document.root();
        String before = encoded(document);

        document.applyAll("wrap", List.of(
                new BuilderEdit.Insert(root, new UIText("a"), 1),
                new BuilderEdit.Insert(root, new UIText("b"), 2)));

        assertEquals(3, root.children().size());
        document.history().undo();
        assertEquals("one undo takes both back", before, encoded(document));
    }

    /** Design data survives a round trip through the file, and never reaches the tree's description. */
    @Test
    public void extrasAreWrittenAndReadBack() {
        UiBuilderDocument document = open();
        UIElement title = document.root().getElementById("title");
        document.apply(new BuilderEdit.SetExtra(document.extras(), title, DocumentExtras.BIND, null,
                new JsonPrimitive("subject")));

        UiBuilderDocument reopened = new UiBuilderDocument(document.encode(), "test:page");

        assertEquals(new JsonPrimitive("subject"),
                reopened.extras().get(reopened.root().getElementById("title"), DocumentExtras.BIND));
    }

    @Test
    public void editsAreNeverMerged() {
        assertFalse(open().mergeable());
    }

    /** The tab: an artboard on a surface, holding the document's own tree. */
    @Test
    public void theEditorPutsTheDocumentOnAnArtboard() {
        UiBuilderDocument document = open();
        BuilderEditor editor = new BuilderEditor(document);

        Artboard board = editor.artboard();
        assertEquals(List.of(board), editor.surface().surface().items());
        assertEquals(document.root(), board.children().get(0));
        assertEquals(800f, board.boardWidth(), 0.001f);

        editor.disposeView();
    }
}
