package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.canvas.Artboard;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
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

    /**
     * The page is laid out and has a size — the check for "the tab opened and there is nothing in it".
     *
     * <p>{@code update} rather than {@code layout}: the latter is the box tree alone and runs no
     * cascade, so every rule in the user-agent sheet would appear not to match.</p>
     */
    @Test
    public void theArtboardHasABoxWhenTheTabIsLaidOut() {
        UIElementRegistry.bootstrap();
        UIDocument window = new UIDocument();
        window.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        UIBuilderView editor = new UIBuilderView(open());
        window.append(editor.view());

        window.update(1200f, 800f);

        Box surface = editor.view().box();
        assertNotNull("the surface must have a box, or the tab is empty", surface);
        assertTrue("the surface fills the tab: " + surface.width(), surface.width() > 100f);
        assertTrue("and its height: " + surface.height(), surface.height() > 100f);

        Box page = editor.artboard().box();
        assertNotNull("the artboard must have a box", page);
        assertEquals(800f, page.width(), 1f);
        assertEquals(480f, page.height(), 1f);

        editor.disposeView();
    }

    /** A file that will not parse still opens, and says why in Problems. */
    @Test
    public void abrokenFileOpensEmptyAndReportsIt() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument document =
                new UiBuilderDocument("{ not json".getBytes(StandardCharsets.UTF_8), "test:broken");

        assertNotNull(document.root());
        assertEquals(1, document.diagnostics().all().size());
    }

    /** The tab: an artboard on a surface, holding the pane's copy of the document's tree — never the tree itself. */
    @Test
    public void theEditorPutsTheDocumentOnAnArtboard() {
        UiBuilderDocument document = open();
        UIBuilderView editor = new UIBuilderView(document);

        Artboard board = editor.artboard();
        assertEquals(List.of(board), editor.surface().surface().items());
        assertSame("the artboard shows this pane's copy", editor.shownTree().root(), board.children().get(0));
        assertNotSame("and never the document's own tree, which one pane would take from another",
                document.root(), board.children().get(0));
        assertEquals(800f, board.boardWidth(), 0.001f);

        editor.disposeView();
    }

    // ── L4.9 ────────────────────────────────────────────────────────────────────────────────────

    /** <b>A scrub is one step</b>: sixty writes of one slot inside a held gesture undo to the first value. */
    @Test
    public void aHeldRunOfOneSlotIsOneUndoStep() {
        UiBuilderDocument document = open();
        UIText title = (UIText) document.root().getElementById("title");
        String before = encoded(document);

        document.history().beginMergeRun();
        String previous = "Status";
        for (int i = 0; i < 60; i++) {
            String next = "Status " + i;
            document.apply(new BuilderEdit.SetState(title, "text", new JsonPrimitive(previous), new JsonPrimitive(next)));
            previous = next;
        }
        document.history().endMergeRun();

        assertEquals(1, document.history().undoDepth());
        document.history().undo();
        assertEquals(before, encoded(document));
    }

    /** Two different slots are two steps even inside one gesture, and nothing merges outside one. */
    @Test
    public void editsOfDifferentFieldsOrOutsideAGestureStaySeparate() {
        UiBuilderDocument document = open();
        UIElement root = document.root();

        document.history().beginMergeRun();
        document.apply(new BuilderEdit.SetId(root, "root", "page"));
        document.apply(new BuilderEdit.SetAttribute<>(root, Attribute.HIT_TEST, true, false));
        document.history().endMergeRun();
        assertEquals(2, document.history().undoDepth());

        document.apply(new BuilderEdit.SetId(root, "page", "a"));
        document.apply(new BuilderEdit.SetId(root, "a", "b"));
        assertEquals("no merge window: typing twice is two steps", 4, document.history().undoDepth());
    }

    /**
     * <b>Engine classes are the widget's, not the author's.</b> A labelled button saves without
     * {@code __labelled__}, a class edit leaves it on the node, and undo does not take it away.
     */
    @Test
    public void engineClassesAreNeitherSavedNorWrittenByAClassEdit() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument document = new UiBuilderDocument(("{ \"cgui\": 1, \"root\": { \"kind\": \"element\","
                + " \"children\": [ { \"kind\": \"button\", \"id\": \"ok\", \"state\": { \"text\": \"OK\" } } ] } }")
                .getBytes(StandardCharsets.UTF_8), "test:button");
        UIElement button = document.root().getElementById("ok");
        assertTrue("the widget labels itself", button.hasClass(Button.LABELLED_CLASS));
        assertFalse(encoded(document), encoded(document).contains(Button.LABELLED_CLASS));

        document.apply(new BuilderEdit.SetClasses(button, List.copyOf(button.classes()), List.of("primary")));
        assertTrue(button.hasClass("primary"));
        assertTrue("left alone", button.hasClass(Button.LABELLED_CLASS));
        assertFalse(encoded(document).contains(Button.LABELLED_CLASS));

        document.history().undo();
        assertFalse(button.hasClass("primary"));
        assertTrue("and undo does not take it either", button.hasClass(Button.LABELLED_CLASS));
    }
}
