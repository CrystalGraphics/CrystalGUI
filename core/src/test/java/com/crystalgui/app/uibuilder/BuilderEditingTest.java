package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.BuilderToolbar;
import com.crystalgui.app.uibuilder.canvas.ResizeHandles;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * <b>L4.5–L4.7 — resizing, retyping, and the tree beside the canvas.</b>
 *
 * <p>What each of these actually pins is that a gesture is <b>one undo step</b> and that the two views of
 * the selection never disagree. The geometry a handle produces is the layout engine's and is not asserted
 * here; that a drag leaves exactly one entry in the history is the builder's own contract.</p>
 */
public class BuilderEditingTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [\n"
            + "      { \"kind\": \"text\", \"id\": \"title\", \"state\": { \"text\": \"bao\" } }\n"
            + "    ] }\n"
            + "}\n";

    private BuilderEditor editor;
    private UIText title;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        editor = new BuilderEditor(new UiBuilderDocument(
                SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor.view());
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();

        title = (UIText) editor.document().root().children().get(0);
    }

    // ── L4.5 ────────────────────────────────────────────────────────────────────────────────────

    /** Eight of them, and they follow the selection rather than existing per node. */
    @Test
    public void theHandlesAppearOnASingleSelectionAndNotOnNone() {
        ResizeHandles handles = editor.handles();
        assertEquals(8, handles.handles().size());
        assertSame("nothing selected, nothing to resize", null, handles.target());

        editor.selection().selectOnly(title);
        assertSame(title, handles.target());

        editor.selection().clear();
        assertSame(null, handles.target());
    }

    // ── L4.6 ────────────────────────────────────────────────────────────────────────────────────

    /** <b>Retyping a text node is one edit</b>, and the document carries the new text. */
    @Test
    public void editingTextInPlaceWritesOneUndoableEdit() {
        editor.selection().selectOnly(title);
        assertTrue(editor.editSelectedText());
        assertTrue(editor.textEditing().isEditing());

        editor.textEditing().field().setText("mao");
        editor.textEditing().commit();
        document.update(W, H);

        assertEquals("mao", title.getText());
        assertTrue(editor.document().history().canUndo());

        editor.document().history().undo();
        document.update(W, H);
        assertEquals("and one undo puts it back", "bao", title.getText());
    }

    /** Committing what was already there writes nothing — an opened-and-closed field is not an edit. */
    @Test
    public void committingUnchangedTextRecordsNothing() {
        editor.selection().selectOnly(title);
        editor.editSelectedText();
        editor.textEditing().commit();

        assertFalse(editor.document().history().canUndo());
    }

    /** Escape closes the field and leaves the node alone. */
    @Test
    public void cancellingLeavesTheTextAlone() {
        editor.selection().selectOnly(title);
        editor.editSelectedText();
        editor.textEditing().field().setText("discarded");
        editor.textEditing().cancel();

        assertEquals("bao", title.getText());
        assertFalse(editor.textEditing().isEditing());
        assertFalse(editor.document().history().canUndo());
    }

    /** Only a text node can be edited in place; anything else refuses rather than opening an empty field. */
    @Test
    public void aNonTextNodeCannotBeEditedInPlace() {
        editor.selection().selectOnly(editor.document().root());
        assertFalse(editor.editSelectedText());
        assertFalse(editor.textEditing().isEditing());
    }

    // ── The toolbar ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Pressing Preview toggles, and says so.</b>
     *
     * <p>Preview's only visible effect is that the document's widgets become live — so on a document
     * with nothing to press, a toggle carrying no state of its own is indistinguishable from a button
     * that does nothing, which is exactly how it was reported.</p>
     */
    @Test
    public void thePreviewButtonTogglesAndShowsIt() {
        Button preview = editor.toolbar().previewButton();
        assertTrue(editor.surface().isDesignMode());
        assertFalse(preview.hasClass(BuilderToolbar.ACTIVE_CLASS));

        preview.onPressed.emit();

        assertFalse("the document is live now", editor.surface().isDesignMode());
        assertTrue("and the button looks it", preview.hasClass(BuilderToolbar.ACTIVE_CLASS));

        preview.onPressed.emit();

        assertTrue(editor.surface().isDesignMode());
        assertFalse(preview.hasClass(BuilderToolbar.ACTIVE_CLASS));
    }

    // ── L4.7 ────────────────────────────────────────────────────────────────────────────────────

    /** The hierarchy shows the document's LIGHT tree — the root and what the file declares. */
    @Test
    public void theHierarchyShowsTheDocumentsOwnNodes() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);

        assertTrue("the root is a row", hierarchy.tree().visibleRows().stream()
                .anyMatch(row -> row.item() == editor.document().root()));
        assertTrue("and so is the text it declares", hierarchy.tree().visibleRows().stream()
                .anyMatch(row -> row.item() == title));
    }

    /**
     * <b>The hierarchy must not clear the selection it was told about.</b>
     *
     * <p>Reported as "click an element and the handles vanish and it stays broken". Following a selection
     * refreshes the tree, and a refresh re-emits the list's own selection — straight back into the
     * handler that writes the shared selection. Outside the re-entrancy guard that write lands with
     * whatever the rebuilt list happened to have, which is nothing.</p>
     */
    @Test
    public void showingASelectionInTheHierarchyDoesNotClearIt() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);

        editor.selection().selectOnly(title);
        document.update(W, H);
        frame();

        assertSame("the hierarchy answered its own refresh and cleared the selection",
                title, editor.selection().node());
        assertSame("...and the engine's item set with it",
                title, editor.surface().selection().items().isEmpty()
                        ? null : editor.surface().selection().items().get(0));
        assertSame("so the handles came off the node", title, editor.handles().target());
    }

    /**
     * <b>Clicking a row in the hierarchy selects, and keeps selecting.</b>
     *
     * <p>The direction that was reported: click a row and the handles vanish, and it stays broken.</p>
     */
    @Test
    public void clickingAHierarchyRowSelectsAndTheSelectionSurvives() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);
        frame();

        int row = rowFor(hierarchy, title);
        hierarchy.tree().select(row);
        document.update(W, H);
        frame();

        assertSame("a row click selected nothing", title, editor.selection().node());
        assertSame("and the handles are not on it", title, editor.handles().target());

        // AND AGAIN, on the other node: "perma break" means the second one does nothing.
        int rootRow = rowFor(hierarchy, editor.document().root());
        hierarchy.tree().select(rootRow);
        document.update(W, H);
        frame();

        assertSame("the second row click did nothing", editor.document().root(),
                editor.selection().node());
    }

    /**
     * <b>The row and the inspector always name the same node.</b>
     *
     * <p>Reported as "#root is highlighted and the inspector shows #title". A refresh re-emits the list's
     * own selection, which lands in the handler that writes the shared selection — so a rebuild caused by
     * the selection changing wrote back whatever the rebuilt list happened to have, and the two halves
     * drifted apart.</p>
     */
    @Test
    public void theRowAndTheSharedSelectionNeverDisagree() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);
        frame();

        UIElement root = editor.document().root();

        hierarchy.tree().select(rowFor(hierarchy, root));
        document.update(W, H);
        frame();
        assertSame("the row was clicked and something else got selected",
                root, editor.selection().node());
        assertSame(root, rowItem(hierarchy));

        hierarchy.tree().select(rowFor(hierarchy, title));
        document.update(W, H);
        frame();
        assertSame(title, editor.selection().node());
        assertSame(title, rowItem(hierarchy));

        // AND FROM THE CANVAS, which is the direction that rebuilds the tree to reveal the node.
        editor.selection().selectOnly(root);
        document.update(W, H);
        frame();
        assertSame("the hierarchy answered its own rebuild", root, editor.selection().node());
        assertSame("the row highlight did not follow", root, rowItem(hierarchy));
    }

    /** What the list itself has highlighted, which is what a reader sees. */
    private static UIElement rowItem(HierarchyPanel hierarchy) {
        var indices = hierarchy.tree().getSelectedIndices();
        if (indices.isEmpty()) return null;
        return hierarchy.tree().visibleRows().get(indices.iterator().next()).item();
    }

    private static int rowFor(HierarchyPanel hierarchy, UIElement node) {
        var rows = hierarchy.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == node) return i;
        }
        throw new AssertionError("no row for " + node);
    }

    /** Selecting on the canvas expands the hierarchy to the node, so a deep selection is findable. */
    @Test
    public void theHierarchyFollowsTheCanvasSelection() {
        HierarchyPanel hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        document.update(W, H);

        editor.selection().selectOnly(title);
        document.update(W, H);

        assertNotNull(hierarchy.tree());
        assertTrue("the branch holding it is open",
                hierarchy.tree().isExpanded(editor.document().root()));
    }
}
