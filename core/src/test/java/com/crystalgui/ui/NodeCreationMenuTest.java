package com.crystalgui.ui;

import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.graph.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeCreationMenu;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import com.crystalgui.ui.elements.graph.PortType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.2.6 — the create-node menu, from the view's side.
 *
 * <h3>What is actually being asserted</h3>
 * <p>The library's filtering is proven headlessly in {@code NodeLibraryTest}. What this pins is the part
 * that only exists once there is a widget: that the menu <b>creates and connects in one undo step</b>,
 * that it offers ports rather than nodes when a wire is being held, and that a node whose type has no
 * registered widget still comes out structurally correct — the placeholder path that makes a document
 * renderable with no factories at all.</p>
 */
public class NodeCreationMenuTest extends UiTestBase {

    private static final PortType VEC3 = new BasicPortType("vec3", 3);
    private static final TypeCompatibility PROMOTES =
            (from, to) -> from.equals("float") || from.equals(to);

    private UIWindow window;
    private GraphView graph;
    private NodeTypeRegistry library;

    @Before
    public void setUp() {
        library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Add").label("Add").category("Math").synonyms("plus")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        library.register(NodeType.of("shader.Step").label("Step").category("Math")
                .in("Edge", "float").out("Out", "float"));

        graph = new GraphView();
        graph.layout(l -> l.width(360).height(300));
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(), PROMOTES);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(graph);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 800);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    // ── The placeholder path ────────────────────────────────────────────────

    /**
     * <b>A type with no registered widget still builds a correct node.</b>
     *
     * <p>Title, ports, sides — all from the document's own stored ports. This is the same mechanism as
     * "opened without the plugin", and it is why a graph renders at all before anybody writes a single
     * widget factory.</p>
     */
    @Test
    public void aTypeWithNoWidgetStillProducesARealNode() {
        var factory = NodeWidgetFactory.of(library).build();
        var data = library.get("shader.Add").create(20f, 30f);

        GraphNode node = factory.create(library.get("shader.Add"), data);

        assertEquals("Add", node.getTitle());
        assertEquals(2, node.getInputPorts().size());
        assertEquals(1, node.getOutputPorts().size());
        assertEquals("A", node.getInputPorts().get(0).getName().split("\\(")[0]);
    }

    /** A type that is not in the library at all still renders, and says which one is missing. */
    @Test
    public void anUnregisteredTypeRendersAndNamesItself() {
        var factory = NodeWidgetFactory.of(library).build();
        var data = com.crystalgui.graph.NodeBuilder.of("some.mod.Missing").at(0f, 0f)
                .in("In", "mystery").build();

        GraphNode node = factory.create(null, data);

        assertEquals("the id is what tells a user which plugin they lack", "some.mod.Missing", node.getTitle());
        assertTrue(node.hasClass(NodeWidgetFactory.UNKNOWN_TYPE_CLASS));
        assertEquals(1, node.getInputPorts().size());
    }

    /** A registered builder wins over the placeholder — that is the whole point of registering one. */
    @Test
    public void aRegisteredBuilderIsUsedInsteadOfThePlaceholder() {
        var factory = NodeWidgetFactory.of(library)
                .register("shader.Add", data -> {
                    GraphNode custom = new GraphNode("Custom Add");
                    custom.addInput(VEC3, "A");
                    return custom;
                })
                .build();

        GraphNode node = factory.create(library.get("shader.Add"), library.get("shader.Add").create(0f, 0f));

        assertEquals("Custom Add", node.getTitle());
        assertEquals(1, node.getInputPorts().size());
    }

    // ── The menu ────────────────────────────────────────────────────────────

    @Test
    public void openingWithoutAWireListsEveryType() {
        NodeCreationMenu menu = graph.creationMenu();
        assertNotNull(menu);

        graph.openCreationMenu(100f, 100f);
        frame();

        assertEquals(2, menu.entries().size());
    }

    /**
     * <b>Held a wire, the menu offers ports.</b>
     *
     * <p>Unity lists every compatible port on every matching node, so picking an entry creates the node
     * <em>and</em> lands the wire. A vec3 output offers Add's two inputs — and Step's float input too,
     * because this graph's rule promotes.</p>
     */
    @Test
    public void openingFromAnOutputOffersCompatiblePorts() {
        NodeCreationMenu menu = graph.creationMenu();
        menu.openForOutput("vec3", PROMOTES, 10f, 10f, graph);
        frame();

        assertEquals("Add.A and Add.B; Step.Edge takes a float and a vec3 does not promote to one",
                2, menu.entries().size());

        menu.openForOutput("float", PROMOTES, 10f, 10f, graph);
        frame();
        assertEquals("a float promotes into both vec3 inputs and fits Step.Edge",
                3, menu.entries().size());
    }

    /**
     * <b>The rows have a size on screen, not merely an existence in the tree.</b>
     *
     * <p>Every other test here counts entries, and all of them passed while the menu rendered as a
     * search box above an empty void: the list carried {@code height: 0} plus {@code flex-grow: 1}, which
     * is the correct idiom inside a container with a <em>definite</em> height and a no-op inside a
     * content-sized popover, because there is no leftover space to grow into. Counting children cannot
     * see that. Measuring them can.</p>
     */
    @Test
    public void theEntriesActuallyHaveHeight() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        assertFalse("there should be rows at all", menu.entries().isEmpty());

        // Measuring the ROWS is not enough, and that is the trap: a 13px row inside a 0-height list still
        // measures 13px, it is merely clipped out of existence. The container is what collapsed, so the
        // container is what has to be asserted -- a test that measured the rows passed happily while the
        // menu rendered as a search box above an empty void.
        UIElement list = menu.entries().get(0).getParent();
        float rowsTotal = 0f;
        for (UIElement entry : menu.entries()) rowsTotal += entry.getRuntimeCache().getHeight();

        assertTrue("the list collapsed to nothing: " + list.getRuntimeCache().getHeight(),
                list.getRuntimeCache().getHeight() > 0f);
        assertTrue("and it must be tall enough to show its rows",
                list.getRuntimeCache().getHeight() >= Math.min(rowsTotal, 160f) - 1f);
    }

    /**
     * <b>A press inside the menu belongs to the menu, not to the canvas underneath it.</b>
     *
     * <p>The menu is an internal child of the graph that is then promoted to the top layer, so a press on
     * its search box still bubbles <em>through</em> the graph. The graph's background handler treated
     * that as a press on empty canvas: it cleared the selection and started a marquee, which takes
     * pointer capture and steals the interaction. The symptom was exact and baffling — the rows worked,
     * because their own handler stops propagation, while the search field and the resize handles did
     * nothing at all.</p>
     */
    @Test
    public void pressingTheMenuDoesNotStartAMarqueeOnTheCanvas() {
        GraphNode existing = new GraphNode("Node");
        existing.addOutput(VEC3, "Out");
        graph.addNode(existing, 20f, 20f);
        graph.getSelection().selectOnly(existing);
        graph.openCreationMenu(60f, 60f);
        frame();
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        var searchCache = menu.searchField().getRuntimeCache();
        var at = com.crystalgui.core.data.Transform2D.apply(searchCache.localToWorld.get(),
                searchCache.getX() + searchCache.getWidth() * 0.5f,
                searchCache.getY() + searchCache.getHeight() * 0.5f);

        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        frame();

        assertFalse("the canvas must not start a rubber band under its own popup", graph.isMarqueeActive());
        assertTrue("nor clear what was selected", graph.getSelection().contains(existing));
        assertTrue("and the press must reach the search box", menu.searchField().isFocused());
    }

    /**
     * <b>A resized menu contains its own rows.</b>
     *
     * <p>{@code resize} writes an explicit height, and without a clip the rows carried on painting past
     * the border and over the canvas below. This engine's notes already say a resizable box should set
     * {@code overflow} — it is the half browsers get for free by restricting {@code resize} to scroll
     * containers, and it was missed when the menu was made resizable.</p>
     */
    @Test
    public void aMenuDraggedShorterKeepsItsRowsInside() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        // What a drag on the bottom-right handle produces: an explicit, smaller height.
        menu.layout(l -> l.height(40f));
        frame();
        frame();

        float menuBottom = menu.getRuntimeCache().getY() + menu.getRuntimeCache().getHeight();
        UIElement list = menu.entries().get(0).getParent();
        assertTrue("the list must give way rather than overflow: list bottom "
                        + (list.getRuntimeCache().getY() + list.getRuntimeCache().getHeight())
                        + " vs menu bottom " + menuBottom,
                list.getRuntimeCache().getY() + list.getRuntimeCache().getHeight() <= menuBottom + 0.5f);
        assertTrue("and it must still be usable, not squeezed to nothing",
                list.getRuntimeCache().getHeight() > 0f);
    }

    /**
     * <b>It cannot be dragged smaller than its own search box.</b>
     *
     * <p>With {@code overflow: hidden} in place, shrinking past the search bar clips it and leaves a
     * menu with nothing to type into and nothing to pick from — worse than the overflow it replaced.
     * The floor is {@code min-height}, which is where {@code resize}'s only constraints live: the
     * resizer deliberately does no clamping of its own, so Taffy is the single place it is applied.</p>
     */
    @Test
    public void itCannotBeShrunkPastItsSearchBox() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        // What dragging the corner all the way in produces.
        menu.layout(l -> l.height(4f).width(10f));
        frame();
        frame();

        var search = menu.searchField().getRuntimeCache();
        var box = menu.getRuntimeCache();
        assertTrue("the floor must hold: " + box.getHeight(), box.getHeight() >= 45f);
        assertTrue("and the search box must stay whole inside it",
                search.getY() + search.getHeight() <= box.getY() + box.getHeight() + 0.5f);
        assertTrue(box.getWidth() >= 119f);
    }

    /**
     * <b>The menu is as wide as its widest row.</b>
     *
     * <p>A fixed width clipped {@code "Perlin noise 3D - Sampling Coordinates"} while wasting space on
     * {@code "Add - A"}, and a row whose label cannot be read is a row nobody can choose. Sized to
     * content between a floor and a ceiling — the ceiling exists so one pathological label cannot
     * produce a menu wider than the window.</p>
     */
    @Test
    public void theMenuWidensToFitItsLongestEntry() {
        library.register(NodeType.of("shader.LongOne")
                .label("Perlin noise 3D with a very long name indeed")
                .in("Sampling Coordinates", "vec3"));
        window.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:graph"));

        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        float menuWidth = menu.getRuntimeCache().getWidth();
        assertTrue("wider than the floor, because a long row demanded it: " + menuWidth,
                menuWidth > 170f);
        assertTrue("but never wider than the ceiling: " + menuWidth, menuWidth <= 320f);

        // And a short library stays at the floor rather than collapsing to the text.
        library.clear();
        library.register(NodeType.of("shader.Add").label("Add").in("A", "vec3"));
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();
        assertTrue("short labels must not produce a sliver: " + menu.getRuntimeCache().getWidth(),
                menu.getRuntimeCache().getWidth() >= 169f);
    }

    /**
     * The rows lay out along the row axis. Taffy's default here is COLUMN, so without saying so the
     * labels were centred across the row and clipped at both ends — which is what it looked like.
     */
    @Test
    public void entryLabelsStartAtTheLeftEdge() {
        window.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:graph"));
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        UIElement row = graph.creationMenu().entries().get(0);
        UIElement label = row.getChildren().get(0);
        float inset = label.getRuntimeCache().getX() - row.getRuntimeCache().getX();

        assertTrue("a label centred in its row is a label clipped at both ends; inset was " + inset,
                inset < 8f);
    }

    /**
     * <b>A press on the canvas closes the menu; the wheel over it does not zoom the graph.</b>
     *
     * <p>Both were the same shape of bug — the menu is the graph's DOM child, promoted to the top layer,
     * so its input still travels through the graph. Light dismiss failed for a subtler reason: the graph
     * was named as the menu's <em>invoker</em>, and an invoker counts as part of its own popover (the
     * carve-out that stops a dropdown button being dismissed by the press that opens it), so every press
     * anywhere on the canvas read as a press inside the menu.</p>
     */
    @Test
    public void theMenuDismissesOnAnOutsidePressAndDoesNotLetTheWheelThrough() {
        graph.openCreationMenu(20f, 20f);
        frame();
        assertTrue("it should be open", graph.creationMenu().isOpen());

        float zoomBefore = graph.getZoom();
        // A wheel over the menu itself: the list may decline it (short, or at its end), and the graph
        // must not take it as a zoom.
        var menuCache = graph.creationMenu().getRuntimeCache();
        var overMenu = com.crystalgui.core.data.Transform2D.apply(menuCache.localToWorld.get(),
                menuCache.getX() + menuCache.getWidth() * 0.5f,
                menuCache.getY() + menuCache.getHeight() * 0.5f);
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(overMenu.x()), Math.round(overMenu.y()), 0, 0, -1, false, 1f, -1L));
        frame();
        assertEquals("the wheel over a popup is the popup's", zoomBefore, graph.getZoom(), 1e-4f);

        // A press well away from it closes it.
        var away = com.crystalgui.core.data.Transform2D.apply(
                graph.getRuntimeCache().localToWorld.get(),
                graph.getRuntimeCache().getX() + graph.getRuntimeCache().getWidth() - 8f,
                graph.getRuntimeCache().getY() + graph.getRuntimeCache().getHeight() - 8f);
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(away.x()), Math.round(away.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 3L));
        frame();

        assertFalse("clicking outside must dismiss it", graph.creationMenu().isOpen());
    }

    @Test
    public void typingNarrowsTheList() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        assertEquals(2, menu.entries().size());

        menu.searchField().setText("plus");
        frame();

        assertEquals("'plus' is Add's synonym", 1, menu.entries().size());
    }

    /**
     * <b>Create-and-connect is one undo step.</b>
     *
     * <p>Two presses to remove a node you just made is the same failure as forty presses to undo one
     * drag: the user did one thing.</p>
     */
    @Test
    public void choosingAnOfferCreatesTheNodeAndConnectsItAsOneStep() {
        GraphNode source = new GraphNode("Source");
        NodePort out = source.addOutput(VEC3, "Out");
        graph.addNode(source, 20f, 20f);
        frame();
        int before = graph.undoStack().undoDepth();

        NodeCreationMenu menu = graph.creationMenu();
        menu.openForOutput("vec3", PROMOTES, 10f, 10f, graph);
        frame();
        // Stand in for the drag that would normally have set this up, then choose the first offer.
        var offer = library.offersForOutput("vec3", PROMOTES, "").get(0);
        menu.onChosen.emit(offer);
        frame();

        assertEquals("the node is there", 2, graph.nodes().size());
        assertEquals("one step, not two", before + 1, graph.undoStack().undoDepth());
        assertTrue("and it is the selected one, as every editor does", graph.selectedNodes().size() == 1);

        graph.undoStack().undo();
        frame();
        assertEquals("one press takes the whole thing back", 1, graph.nodes().size());
        assertFalse(out.isConnected());
    }
}
