package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.document.BuilderEdit;

import com.crystalgui.ui.dom.Attribute;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.document.NodeSelectors;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyActions;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.widget.collection.tree.TreeEditModel;
import com.crystalgui.widget.collection.tree.TreeEditing;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>L4.7 — the Hierarchy edits the document like a file tree</b>: a drop, a paste, a duplicate and a
 * delete are each one undo step, and what lands is what is selected.
 */
public class HierarchyEditingTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [\n"
            + "      { \"kind\": \"text\", \"id\": \"title\", \"state\": { \"text\": \"bao\" } },\n"
            + "      { \"kind\": \"element\", \"id\": \"group\" },\n"
            + "      { \"kind\": \"text\", \"id\": \"note\", \"state\": { \"text\": \"mao\" } }\n"
            + "    ] }\n"
            + "}\n";

    private UIBuilderView editor;
    private UIElement root, title, group, note;
    private HierarchyPanel hierarchy;
    private TreeEditing<UIElement> editing;
    private TreeEditModel<UIElement> model;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        editor = new UIBuilderView(new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement host = new UIElement().layout(l -> l.width(800).height(500));
        host.append(editor.view());
        document.append(host);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        root = editor.document().root();
        title = root.children().get(0);
        group = root.children().get(1);
        note = root.children().get(2);

        hierarchy = new HierarchyPanel(editor.surface());
        document.append(hierarchy);
        settle();
        editing = hierarchy.editing();
        model = editing.model();
    }

    private void settle() {
        document.update(W, H);
        frame();
    }

    private UndoStack history() {
        return editor.document().history();
    }

    @Test
    public void aDropIntoAContainerIsOneUndoStepAndSelectsWhatLanded() {
        assertTrue(model.canDrop(List.of(title), group));
        model.move(List.of(title), new TreeEditModel.Target<>(group, -1));
        assertSame(group, title.parentElement());
        assertSame("the dropped node is not the selection", title, editor.selection().node());

        history().undo();
        assertSame(root, title.parentElement());
        assertEquals(0, root.indexOf(title));
        assertFalse("a drop was more than one undo step", history().canUndo());
    }

    @Test
    public void aDropBeforeAndAfterReordersAmongSiblings() {
        model.move(List.of(note), new TreeEditModel.Target<>(root, 0));
        assertEquals(List.of(note, title, group), root.children());
        model.move(List.of(note), new TreeEditModel.Target<>(root, 3));
        assertEquals(List.of(title, group, note), root.children());
    }

    @Test
    public void aTextLeafAndTheNodesOwnSubtreeRefuseADrop() {
        assertFalse("a text node draws its own content and takes no children", model.canDrop(List.of(note), title));
        assertFalse(model.canDrop(List.of(root), group));
        assertFalse("the root is not picked up", model.canEdit(root));
    }

    @Test
    public void cutAndPasteMovesTheSelectionAfterTheSelectedNode() {
        editor.selection().selectOnly(title);
        settle();
        editing.cut();
        editor.selection().selectOnly(note);
        settle();
        editing.paste();
        assertEquals(List.of(group, note, title), root.children());
        history().undo();
        assertEquals(List.of(title, group, note), root.children());
    }

    @Test
    public void duplicateCopiesBesideWithAFreeIdAndSelectsTheCopy() {
        editor.selection().selectOnly(title);
        settle();
        assertTrue(editing.canDuplicate());
        editing.duplicate();
        assertEquals(4, root.children().size());
        UIElement copy = root.children().get(1);
        assertEquals("title2", copy.id());
        assertSame(copy, editor.selection().node());
    }

    @Test
    public void deletingASelectionRemovesItAllAndOneUndoPutsItBack() {
        editor.selection().replaceWith(List.of(title, note));
        settle();
        assertTrue(editing.canDelete());
        editing.delete();
        assertEquals(List.of(group), root.children());
        assertNull(editor.selection().node());

        history().undo();
        assertEquals(List.of(title, group, note), root.children());
    }

    @Test
    public void newGoesIntoASelectedContainerOrAfterASelectedLeafAsOneUndoStep() {
        editor.selection().selectOnly(group);
        settle();
        UIElement inside = new UIElement();
        hierarchy.insertNew(inside);
        assertSame(group, inside.parentElement());
        assertSame("the new node is not the selection", inside, editor.selection().node());

        editor.selection().selectOnly(title);
        settle();
        UIElement after = new UIElement();
        hierarchy.insertNew(after);
        assertEquals(1, root.indexOf(after));

        history().undo();
        assertNull("one undo left the node in", after.parentElement());
    }

    @Test
    public void aSelectorStopsAtTheNearestIdAndNamesClassesOnlyWhereASiblingShares() {
        UIElement first = new UIElement();
        UIElement second = new UIElement();
        second.addClass("primary");
        group.append(first, second);
        String kind = second.tagName();
        assertEquals("#title", NodeSelectors.cssPath(title, root));
        assertEquals("#group > " + kind + ".primary", NodeSelectors.cssPath(second, root));
        assertEquals("an engine state class is no part of a selector", "#group > " + kind,
                NodeSelectors.cssPath(first.addClass("__selected__"), root));
    }

    @Test
    public void theRowMenuOffersTheAttributesAndCopySelector() {
        Disposable builder = BuilderCommands.register();
        Disposable rows = HierarchyActions.register(CommandRegistry.global());
        try {
            editor.selection().selectOnly(title);
            settle();
            Menu menu = ContextMenu.of(HierarchyPanel.CONTEXT_MENU).build(CommandRegistry.global(), hierarchy.tree());
            List<String> labels = new ArrayList<>();
            MenuItem copyAttributes = null;
            for (MenuItem item : menu.getItems()) {
                labels.add(item.getText());
                if ("Copy Attributes".equals(item.getText())) copyAttributes = item;
            }
            assertTrue(labels.toString(), labels.containsAll(List.of("Copy Attributes", "Paste Attributes", "Copy Selector")));
            assertTrue("Copy Attributes cannot see the row's node", copyAttributes.isEnabled());
        } finally {
            rows.dispose();
            builder.dispose();
        }
    }

    @Test
    public void aPressOnARowsLabelDragsTheRow() {
        UIText label = null;
        for (UIElement row : hierarchy.tree().realisedRows().values()) {
            for (UIElement child : row.children()) {
                if (child instanceof UIText text && "#title".equals(text.getText())) label = text;
            }
        }
        int[] at = centreOf(label);
        assertSame("the press must land on the label, not the row", label, hitTarget(at[0], at[1]));
        press(at[0], at[1]);
        move(at[0], at[1] + 20f * uiScale());
        Drag drag = document.input().mode(Drag.class);
        assertTrue("a press on the label did not drag the row", drag != null && drag.isActivated());
        release(at[0], at[1] + 20f * uiScale());
    }

    /**
     * A press on an unselected row highlights it and selects its node on the CLICK, VS Code's list: choosing a node
     * rebuilds the Inspector, and a press that paid for it held the drag it armed for that whole frame.
     */
    @Test
    public void aPressChoosesTheNodeOnTheClickNotThePress() {
        editor.selection().selectOnly(note);
        settle();
        UIText label = null;
        for (UIElement row : hierarchy.tree().realisedRows().values()) {
            for (UIElement child : row.children()) {
                if (child instanceof UIText text && "#title".equals(text.getText())) label = text;
            }
        }
        int[] at = centreOf(label);
        press(at[0], at[1]);
        assertSame("the press chose the node before the gesture could become a drag", note, editor.selection().node());
        release(at[0], at[1]);
        assertSame("the click did not choose the node", title, editor.selection().node());
    }

    /**
     * A row is the same Inspector subject as the canvas: the same sections, asking the same questions.
     *
     * <p>The Inspector takes its subject from the focus owner, and a press on a row moves focus there before the
     * click has chosen anything. With a key the row did not forward, fewer sections answered, the subject read as
     * changed, and the Inspector rebuilt into the old node's half-view before rebuilding into the new one.</p>
     */
    @Test
    public void aRowIsTheSameInspectorSubjectAsTheCanvas() {
        Disposable sections = BuilderInspectorSections.register();
        try {
            editor.selection().selectOnly(title);
            settle();
            UIElement row = hierarchy.tree().realisedRows().values().iterator().next();
            assertEquals(subjectOf(DataContext.from(editor.surface())), subjectOf(DataContext.from(row)));
        } finally {
            sections.dispose();
        }
    }

    private static List<String> subjectOf(DataContext context) {
        List<String> keys = new ArrayList<>();
        for (InspectorSection section : InspectorRegistry.sectionsFor(context)) {
            keys.add(section.getClass().getSimpleName() + "=" + section.subjectKey(context));
        }
        return keys;
    }

    @Test
    public void newOffersEachKindUnderItsOwnNameAndGlyph() {
        Disposable rows = HierarchyActions.register(CommandRegistry.global());
        try {
            Menu menu = ContextMenu.of(HierarchyPanel.NEW_MENU).build(CommandRegistry.global(), hierarchy.tree());
            MenuItem button = null;
            for (MenuItem item : menu.getItems()) {
                if ("Button".equals(item.getText())) button = item;
            }
            assertTrue("New offers no Button row", button != null);
            assertTrue("the Button row draws no icon", button.hasClass(MenuItem.ICON_CLASS));
            assertTrue("and it is not tinted as a control", button.hasClass(GlyphRole.CONTROL.cssClass()));
            assertTrue("the menu reserves no icon column", menu.hasClass(Menu.HAS_ICONS_CLASS));
        } finally {
            rows.dispose();
        }
    }

    @Test
    public void aRowDrawsItsNodesGlyphAndFollowsALayoutChangeWithinAFrame() {
        KindGlyphs.Glyph text = hierarchy.glyphOnRow(title);
        assertEquals("crystalgui:nodes/ui/text", text.icon());
        assertEquals("crystalgui:nodes/ui/column", hierarchy.glyphOnRow(root).icon());

        // NOT THROUGH THE DOCUMENT, so nothing tells the panel: only the per-frame check can notice.
        root.layout(l -> l.flexDirection(FlexDirection.ROW));
        settle();
        assertEquals("crystalgui:nodes/ui/row", hierarchy.glyphOnRow(root).icon());
    }

    @Test
    public void aNodeFromAnotherDocumentIsCopiedNotMoved() {
        UIElement foreign = new UIElement().setId("title");
        UIElement elsewhere = new UIElement();
        elsewhere.append(foreign);
        model.move(List.of(foreign), new TreeEditModel.Target<>(group, -1));
        assertSame("the other document lost its node", elsewhere, foreign.parentElement());
        assertEquals(1, group.children().size());
        assertEquals("a copy kept an id this document already has", "title2", group.children().get(0).id());
    }

    /** Hiding a node dims its row and every row under it; showing it again clears them. Disabled dims nothing. */
    @Test
    public void aHiddenNodesSubtreeIsDimmedInTheTree() {
        editor.document().apply(new BuilderEdit.SetAttribute<>(root, Attribute.HIDDEN, false, true));
        settle();
        assertTrue(rowOf(title).hasClass(HierarchyPanel.HIDDEN_NODE_CLASS));

        history().undo();
        settle();
        assertFalse(rowOf(title).hasClass(HierarchyPanel.HIDDEN_NODE_CLASS));

        editor.document().apply(new BuilderEdit.SetAttribute<>(title, Attribute.ENABLED, true, false));
        settle();
        assertFalse(rowOf(title).hasClass(HierarchyPanel.HIDDEN_NODE_CLASS));
    }

    private UIElement rowOf(UIElement node) {
        for (Map.Entry<Integer, UIElement> realised : hierarchy.tree().realisedRows().entrySet()) {
            if (hierarchy.tree().rowAt(realised.getKey()).item() == node) return realised.getValue();
        }
        throw new AssertionError("no row for " + node);
    }

    /**
     * <b>Ctrl+Shift+C copies a selector in the Hierarchy</b> -- bound on the tree rather than declared,
     * because the same chord is Inspect Element everywhere else and an element's keymap is asked first.
     */
    @Test
    public void ctrlShiftCCopiesASelectorInTheHierarchy() {
        assertEquals(KeyChord.parse("Ctrl+Shift+C"),
                Keymap.acceleratorFor(hierarchy.tree(), HierarchyActions.COPY_SELECTOR));
    }

    /** <b>...and the canvas's attribute keys reach it too</b>, so its menu rows show them and they fire. */
    @Test
    public void theAttributeKeysWorkInTheHierarchy() {
        assertEquals(KeyChord.parse("Alt+C"),
                Keymap.acceleratorFor(hierarchy.tree(), BuilderCommands.COPY_ATTRIBUTES));
        assertEquals(KeyChord.parse("Alt+V"),
                Keymap.acceleratorFor(hierarchy.tree(), BuilderCommands.PASTE_ATTRIBUTES));
    }
}
