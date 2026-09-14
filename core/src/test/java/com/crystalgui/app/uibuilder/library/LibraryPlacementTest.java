package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.DropResolver;
import com.crystalgui.app.uibuilder.canvas.Placement;
import com.crystalgui.app.uibuilder.document.NewNode;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.panel.HierarchyPanel;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.collection.tree.TreeEditModel;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/** B.7: a card placed by drag onto the canvas or the Hierarchy, or by double-click — one undo step each. */
public class LibraryPlacementTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private BuilderEditor editor;
    private HierarchyPanel hierarchy;
    private LibraryPanel library;
    private UIElement group;
    private UIText label;

    @Before
    public void openABuilderAndTheLibrary() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:library.cgui");
        group = new UIElement().layout(l -> l.width(200).height(80));
        label = new UIText("words");
        model.root().append(group, label);

        editor = new BuilderEditor(model);
        UIElement canvasHost = new UIElement().layout(l -> l.width(400).height(260));
        canvasHost.append(editor.view());
        UIElement libraryHost = new UIElement().layout(l -> l.width(300).height(300));
        library = new LibraryPanel(LibraryCatalog.current());
        libraryHost.append(library);
        hierarchy = new HierarchyPanel(editor.surface());
        document.append(canvasHost, libraryHost, hierarchy);
        settle();
    }

    @Test
    public void aCardDraggedOntoTheCanvasLandsWhereItIsDroppedAsOneUndoStep() {
        int depth = model.history().undoDepth();
        int[] from = centreOf(card(Button.NAME.local()));
        press(from[0], from[1]);
        move(from[0] + 8, from[1] + 8);
        float[] into = at(group, 0.5f, 0.8f);
        move(into[0], into[1]);
        release(into[0], into[1]);
        settle();

        assertEquals(1, group.children().size());
        UIElement placed = group.children().get(0);
        assertTrue(placed instanceof Button);
        assertSame("what landed is not the selection", placed, editor.selection().node());
        assertEquals("a placement was more than one undo step", depth + 1, model.history().undoDepth());
        model.history().undo();
        assertTrue(group.children().isEmpty());
    }

    @Test
    public void aTextDroppedInTheHierarchyLandsAndOpensForEditing() {
        TreeEditModel<UIElement> rows = hierarchy.editing().model();
        NewNode text = new NewNode("<text>", () -> new UIText("Text"));
        assertTrue(rows.canDropForeign(text, group));
        rows.dropForeign(text, new TreeEditModel.Target<>(group, 0));

        UIElement placed = group.children().get(0);
        assertTrue(placed instanceof UIText);
        assertTrue("a dropped text does not open for editing", editor.textEditing().isEditing());
        assertSame(placed, editor.textEditing().target());
    }

    @Test
    public void aTextLeafRefusesADropOnBothSurfaces() {
        int depth = model.history().undoDepth();
        NewNode button = new NewNode("<button>", () -> new Button("Button"));

        assertFalse("the Hierarchy offers a text leaf as a container", hierarchy.editing().model().canDropForeign(button, label));
        assertFalse(Placement.at(editor.surface(), label, 0, new Button("Button")));
        float[] over = at(label, 0.5f, 0.5f);
        DropResolver.Drop drop = new DropResolver(model.root(), editor.surface().dropIndicator()).resolve(List.of(), over[0], over[1]);
        assertNotSame("the canvas drops into a text leaf", label, drop == null ? null : drop.target());
        assertEquals(depth, model.history().undoDepth());
    }

    @Test
    public void aDoubleClickOnACardAsksToPlaceIt() {
        List<LibraryCatalog.Entry> placed = new ArrayList<>();
        library.onPlace.connect(placed::add);
        int[] at = centreOf(card(Button.NAME.local()));
        click(at[0], at[1]);
        click(at[0], at[1]);

        assertEquals(1, placed.size());
        assertEquals(Button.NAME, placed.get(0).kind());
    }

    private PreviewCard card(String local) {
        return library.realisedCards().stream().filter(card -> card.entry().kind().local().equals(local))
                .findFirst().orElseThrow();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private static float[] at(UIElement node, float fx, float fy) {
        Box box = node.box();
        Vector2f world = Transform2D.apply(box.localToWorld(), box.width() * fx, box.height() * fy);
        return new float[] {world.x, world.y};
    }
}
