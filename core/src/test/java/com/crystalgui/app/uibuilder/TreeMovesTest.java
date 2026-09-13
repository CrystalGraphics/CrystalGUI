package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.TreeMoves;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>The edits a drop commits</b> — GrapesJS's index arithmetic, where a wrong answer is off by one and
 * looks like an unsteady hand.
 */
public class TreeMovesTest {

    private UiBuilderDocument model;
    private UIElement row;
    private UIElement a;
    private UIElement b;
    private UIElement c;
    private UIElement other;
    private UIElement d;

    @Before
    public void aTree() {
        UIElementRegistry.bootstrap();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:moves.cgui");
        row = new UIElement();
        a = new UIElement().setId("a");
        b = new UIElement().setId("b");
        c = new UIElement().setId("c");
        other = new UIElement();
        d = new UIElement().setId("d");
        row.append(a, b, c);
        other.append(d);
        model.root().append(row, other);
    }

    /** A drop index counts the node itself, so moving it later in its own parent lands one place earlier. */
    @Test
    public void movingLaterInTheSameParentTakesOneOff() {
        model.applyAll("move", TreeMoves.move(row, 2, List.of(a)));
        assertEquals(List.of(b, a, c), row.children());
    }

    @Test
    public void aNodeAlreadyWhereItWouldLandIsNotMoved() {
        assertTrue(TreeMoves.move(row, 1, List.of(a)).isEmpty());
        assertTrue(TreeMoves.move(row, 0, List.of(a)).isEmpty());
    }

    /** Several nodes land in document order from one index, whichever parent each came from. */
    @Test
    public void nodesFromTwoParentsLandInDocumentOrder() {
        model.applyAll("move", TreeMoves.move(row, 3, List.of(d, a)));
        assertEquals(List.of(b, c, a, d), row.children());
        assertTrue(other.children().isEmpty());
    }

    /** A node inside another carried node travels with it and is not moved on its own. */
    @Test
    public void aDescendantTravelsWithItsAncestor() {
        List<BuilderEdit> edits = TreeMoves.move(row, 0, List.of(d, other));
        assertEquals(1, edits.size());
        assertEquals(other, edits.get(0).node());
    }

    /** A copy carries its design values, and none of its ids is one the document already has. */
    @Test
    public void aCopyCarriesItsDesignValuesAndFreesItsIds() {
        UIElement inner = new UIElement().setId("b-inner");
        b.append(inner);
        model.extras().put(b, DocumentExtras.DESIGN, new JsonPrimitive("hint"));

        List<BuilderEdit> copies = TreeMoves.duplicate(model, row, 2, List.of(b));
        model.applyAll("duplicate", copies);

        UIElement copy = row.children().get(2);
        assertEquals(copies.get(0).node(), copy);
        assertEquals(new JsonPrimitive("hint"), model.extras().get(copy, DocumentExtras.DESIGN));
        assertEquals("numbered as a rename's offer is", "b2", copy.id());
        assertEquals("b-inner2", copy.children().get(0).id());
    }
}
