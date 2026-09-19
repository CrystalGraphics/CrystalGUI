package com.crystalgui.app.shadergraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.graph.GraphNode;

/**
 * <b>Two panes onto one graph edit one graph, and look at it separately.</b> A split, or a torn-out window: the
 * document, its history and its compile are shared; the camera and the widgets are each pane's own.
 *
 * <p>No frame is pumped once a graph has nodes, as in {@link ShaderGraphViewTest}: a frame attaches the previews,
 * which need a GL context. A pane catches up with {@code syncFromDocument()}, which is what its frame hook calls.</p>
 */
public class ShaderGraphSplitTest extends UiDocumentTestBase {

    private ShaderGraphDocument model;
    private ShaderGraphView left;
    private ShaderGraphView right;

    @Before
    public void twoPanes() {
        model = new ShaderGraphDocument();
        left = new ShaderGraphView(model);
        right = new ShaderGraphView(model);
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(left);
        root.append(right);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        left.addStarterGraph();
        right.graph().syncFromDocument();
    }

    @Test
    public void anEditInOnePaneIsInTheOther() {
        int nodes = model.graph().nodeCount();
        assertTrue(nodes > 0);
        assertEquals("the second pane shows the graph the first seeded", nodes, right.graph().nodes().size());

        GraphNode victim = right.graph().nodes().get(0);
        right.graph().removeNode(victim);
        left.graph().syncFromDocument();
        assertEquals("a delete in one pane is a delete in the document", nodes - 1, model.graph().nodeCount());
        assertEquals("and in the other pane", nodes - 1, left.graph().nodes().size());
    }

    @Test
    public void anUndoInEitherPaneReversesWhatEitherDid() {
        int nodes = model.graph().nodeCount();
        right.graph().removeNode(right.graph().nodes().get(0));

        left.graph().undoStack().undo();

        assertEquals("one history: the left pane undid the right pane's delete", nodes, model.graph().nodeCount());
        assertEquals(nodes, left.graph().nodes().size());
        assertEquals("and the pane that made it shows the node back", nodes, right.graph().nodes().size());
    }

    @Test
    public void eachPaneKeepsItsOwnCamera() {
        left.graph().setZoom(2f);
        assertNotEquals(2f, right.graph().getZoom(), 0.0001f);
    }

    @Test
    public void closingOnePaneLeavesTheOtherFollowingTheDocument() {
        right.dispose();
        int nodes = model.graph().nodeCount();

        left.graph().removeNode(left.graph().nodes().get(0));
        left.graph().undoStack().undo();

        assertEquals(nodes, model.graph().nodeCount());
        assertEquals(nodes, left.graph().nodes().size());
    }
}
