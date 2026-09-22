package com.crystalgui.widget.graph;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Right-click on a node or a wire: what gets selected, and the one command the menus added.
 */
public class GraphContextMenuTest extends UiDocumentTestBase {

    private static final PortType VEC3 = new BasicPortType("vec3", 3);

    private GraphView graph;

    @Before
    public void setUp() {
        GraphCommands.register();
        graph = new GraphView();
        graph.layout(l -> l.width(360).height(300));
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.append(graph);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    private GraphNode node(String title, float x, float y) {
        GraphNode node = new GraphNode(title);
        node.addInput(VEC3, "In");
        node.addOutput(VEC3, "Out");
        graph.addNode(node, x, y);
        frame();
        return node;
    }

    private void rightClick(UIElement element) {
        var box = element.box();
        Vector2f at = Transform2D.apply(box.localToWorld(), box.width() * 0.5f, box.height() * 0.5f);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.RIGHT_BUTTON, true, 0f, 1L));
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.RIGHT_BUTTON, false, 0f, 2L));
        frame();
    }

    /** The open menu, which takes focus when it opens. */
    private Menu openMenu() {
        for (UIElement at = document.focus().focused(); at != null; at = at.parentElement()) {
            if (at instanceof Menu menu) return menu;
        }
        return null;
    }

    @Test
    public void aRightClickSelectsTheNodeItLandsOnAndOpensItsMenu() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        graph.selectNode(b, false);

        rightClick(a);

        assertEquals("the commands act on what was clicked", List.of(a), graph.selectedNodes());
        Menu menu = openMenu();
        assertNotNull("a menu opened", menu);
        // RESOLVED AGAINST THIS GRAPH: Delete applies to the node, and Disconnect All has no wire to act on.
        assertTrue(item(menu, "Delete").isEnabled());
        assertFalse(item(menu, "Disconnect All").isEnabled());
    }

    private static MenuItem item(Menu menu, String label) {
        for (MenuItem item : menu.getItems()) {
            if (label.equals(item.getText())) return item;
        }
        throw new AssertionError("no '" + label + "' row");
    }

    @Test
    public void aRightClickOnPartOfTheSelectionKeepsAllOfIt() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        graph.selectNode(a, false);
        graph.selectNode(b, true);

        rightClick(a);

        assertEquals("as in a file manager", 2, graph.selectedNodes().size());
    }

    @Test
    public void disconnectAllIsOneUndoStep() {
        GraphNode a = node("A", 20f, 20f);
        GraphNode b = node("B", 220f, 20f);
        GraphNode c = node("C", 20f, 160f);
        NodePort into = b.getInputPorts().get(0);
        assertNotNull(graph.connect(a.getOutputPorts().get(0), into));
        assertNotNull(graph.connect(b.getOutputPorts().get(0), c.getInputPorts().get(0)));
        graph.selectNode(b, false);

        assertTrue(graph.selectionHasWires());
        assertEquals(2, graph.disconnectSelection());
        assertTrue("every wire on the node went", graph.connectionsOf(into).isEmpty());

        graph.undoStack().undo();
        assertEquals("one undo brings them all back", 1, graph.connectionsOf(into).size());
        assertEquals(1, graph.connectionsOf(b.getOutputPorts().get(0)).size());
    }
}
