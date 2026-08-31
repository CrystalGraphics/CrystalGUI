package com.crystalgui.ui;

import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.graph.port.BasicPortType;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeCreationMenu;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeView;
import com.crystalgraphics.platform.input.CgKeyCodes;
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

    // ── Search: ranking, and the category that explains it ─────────────────

    /**
     * <b>A NAME match must rank above a CATEGORY-only match — the "Enter creates the wrong node" bug.</b>
     *
     * <p>The menu pre-selects row 0 so Enter takes the best match, but results were only ever sorted
     * alphabetically. In the real shader library that made {@code vec} pre-select <b>Cross Product</b> —
     * which does not contain the string and matched solely through its {@code Math/Vector} category —
     * while {@code Vector 2} sat twelfth. Reproduced here in miniature: {@code plu} names Plus and is
     * also the category of two other types.</p>
     */
    @Test
    public void aNameMatchRanksAboveACategoryOnlyMatch() {
        library.register(NodeType.of("t.plus").label("Plus").category("Math"));
        library.register(NodeType.of("t.alpha").label("Alpha").category("Plumbing"));
        library.register(NodeType.of("t.beta").label("Beta").category("Plumbing"));

        graph.openCreationMenu(10f, 10f);
        frame();
        NodeCreationMenu menu = graph.creationMenu();
        menu.searchField().setText("plu");
        frame();

        assertEquals("the name match must be first, because Enter takes row 0",
                "Plus", menu.visibleEntries().get(0).label());
    }

    /** Everything that matches is still offered — ranking reorders, it does not filter. */
    @Test
    public void rankingKeepsEveryMatchRatherThanDroppingTheWeakOnes() {
        library.register(NodeType.of("t.plus").label("Plus").category("Math"));
        library.register(NodeType.of("t.alpha").label("Alpha").category("Plumbing"));

        graph.openCreationMenu(10f, 10f);
        frame();
        NodeCreationMenu menu = graph.creationMenu();
        menu.searchField().setText("plu");
        frame();

        var labels = menu.visibleEntries().stream().map(e -> e.label()).toList();
        assertTrue(labels.contains("Plus"));
        assertTrue("the category-only match is kept, just ranked below", labels.contains("Alpha"));
    }

    /**
     * <b>A search result shows its category; browsing does not.</b>
     *
     * <p>Flattening throws away the folder a row came from, and the menu matches categories — so without
     * this a correct result set reads as noise. Browsing needs none of it: the folder is on screen
     * directly above the row.</p>
     */
    @Test
    public void searchResultsCarryTheirCategoryAndBrowsingDoesNot() {
        graph.openCreationMenu(10f, 10f);
        frame();
        NodeCreationMenu menu = graph.creationMenu();

        menu.searchField().setText("add");
        frame();
        assertEquals("Math", categoryTextOfFirstRow(menu));

        menu.searchField().setText("");
        frame();
        assertEquals("browsing shows the tree, so the row repeats nothing",
                "", categoryTextOfFirstRow(menu));
    }

    /**
     * <b>The matched characters are registered for {@code ::highlight(search-match)}.</b>
     *
     * <p>Asserted through the highlight registry rather than pixels — the ranges are what Java owns; the
     * colour is the stylesheet's. Also the regression guard for row RECYCLING: a row that stops matching
     * must be cleared, or it keeps the previous occupant's ranges and the tint drifts onto unrelated rows
     * as the list scrolls.</p>
     */
    @Test
    public void matchedCharactersAreRegisteredAsHighlightsAndClearedWhenTheyStop() {
        graph.openCreationMenu(10f, 10f);
        frame();
        NodeCreationMenu menu = graph.creationMenu();

        menu.searchField().setText("ad");
        frame();
        UIText label = firstRowLabel(menu);
        var ranges = label.highlights().get(NodeCreationMenu.MATCH_HIGHLIGHT);
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0).start());
        assertEquals(2, ranges.get(0).end());

        menu.searchField().setText("");
        frame();
        assertTrue("a row with no match must be cleared, not left wearing the last one's ranges",
                firstRowLabel(menu).highlights().get(NodeCreationMenu.MATCH_HIGHLIGHT).isEmpty());
    }

    /** The label of the first realised row — reaching through the row template by class, as a theme would. */
    private static UIText firstRowLabel(NodeCreationMenu menu) {
        return (UIText) menu.treeView().querySelectorAll("." + NodeCreationMenu.LABEL_CLASS).get(0);
    }

    /** The first row's category, segments joined — the separator between them is a drawn shape, not text. */
    private static String categoryTextOfFirstRow(NodeCreationMenu menu) {
        var found = menu.treeView().querySelectorAll("." + NodeCreationMenu.CATEGORY_SEGMENT_CLASS);
        StringBuilder out = new StringBuilder();
        for (var element : found) {
            String text = ((UIText) element).getText();
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(text);
            if (out.length() > 40) break;
        }
        return out.toString();
    }

    // ── The menu ────────────────────────────────────────────────────────────

    @Test
    public void openingWithoutAWireListsEveryType() {
        NodeCreationMenu menu = graph.creationMenu();
        assertNotNull(menu);

        graph.openCreationMenu(100f, 100f);
        frame();

        // Both types are in "Math", so what is on screen is the folder plus its two children. Counting
        // OFFERS rather than rows is what a caller means by "every type" — rows include structure.
        assertEquals(2, menu.visibleOffers().size());
        assertEquals("the folder is a row too", 3, menu.visibleEntries().size());
        assertTrue(menu.visibleEntries().get(0).isCategory());
    }

    /**
     * <b>A small library opens ready to use; folders only appear once they earn their keep.</b>
     *
     * <p>Unity collapses everything by default, which is right for its several hundred nodes and pure
     * friction for six — two clicks to reach a list that would have fitted on screen whole. Below the
     * threshold every folder starts open, so a small library behaves exactly like the flat list it was
     * before the tree existed.</p>
     */
    @Test
    public void aSmallLibraryStartsFullyExpandedAndALargeOneDoesNot() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        assertEquals("2 offers is well under the threshold", 2, menu.visibleOffers().size());

        for (int i = 0; i < menu.getAutoExpandThreshold() + 2; i++) {
            library.register(NodeType.of("shader.Bulk" + i).label("Bulk" + i).category("Bulk")
                    .out("Out", "vec3"));
        }
        graph.openCreationMenu(0f, 0f);
        frame();

        assertTrue("past the threshold the folders stay shut", menu.visibleOffers().isEmpty());
        assertEquals("only the two top-level folders show", 2, menu.visibleEntries().size());
        assertTrue("but everything is still reachable", menu.allOffers().size() > menu.getAutoExpandThreshold());
    }

    /**
     * <b>Typing flattens.</b>
     *
     * <p>A result set is ranked, not filed. Leaving matches buried under collapsed folders is exactly
     * what the user typed in order to avoid.</p>
     */
    @Test
    public void aQueryDropsTheFoldersEntirely() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        assertTrue("browsing shows structure", menu.visibleEntries().get(0).isCategory());
        assertTrue("and offers no default action, because the top row is a folder",
                menu.treeView().getSelectedIndices().isEmpty());

        menu.searchField().setText("Add");
        frame();

        assertEquals(1, menu.visibleEntries().size());
        assertFalse("searching shows results", menu.visibleEntries().get(0).isCategory());
        assertEquals("Add", menu.visibleEntries().get(0).label());
        // The command-palette rule: with a query, Enter takes the best match without an arrow press.
        assertEquals("a query highlights its top match", java.util.Set.of(0),
                menu.treeView().getSelectedIndices());

        menu.searchField().setText("");
        frame();
        assertTrue("and clearing the query gives the highlight back up",
                menu.treeView().getSelectedIndices().isEmpty());
    }

    /** A category row opens and closes rather than creating anything — the one thing that would be a
     * disaster to get wrong, since both kinds of row look alike and sit in the same list. */
    @Test
    public void pressingACategoryTogglesItInsteadOfCreatingANode() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        int nodesBefore = graph.nodes().size();

        boolean[] chose = { false };
        menu.onChosen.connect(offer -> chose[0] = true);
        menu.treeView().collapseAll();
        frame();

        assertEquals("collapsed to the one folder", 1, menu.visibleEntries().size());
        pressCentreOf(menu.entries().get(0));
        frame();

        assertFalse("a folder is not a node", chose[0]);
        assertEquals(nodesBefore, graph.nodes().size());
        assertEquals("it opened instead", 3, menu.visibleEntries().size());
        assertTrue("and it is still open", menu.isOpen());
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
                2, menu.visibleOffers().size());

        menu.openForOutput("float", PROMOTES, 10f, 10f, graph);
        frame();
        assertEquals("a float promotes into both vec3 inputs and fits Step.Edge",
                3, menu.visibleOffers().size());
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
     * <b>A long label is contained rather than allowed to widen the menu — and this is a deliberate
     * regression from the content-sized version.</b>
     *
     * <p>The menu used to size itself to its widest row. A {@code TreeView} cannot support that: it is
     * virtualised, so its rows are {@code position: absolute} and contribute to neither intrinsic axis,
     * and a box content-sized around one measures as empty. The menu therefore opens at a definite size
     * inside the same floor and ceiling as before, stays resizable, and a label past the edge ellipsizes
     * at its own row. Unity's Create Node window makes the same trade.</p>
     */
    @Test
    public void aLongLabelIsContainedRatherThanWideningTheMenu() {
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
        assertTrue("inside the floor and the ceiling: " + menuWidth, menuWidth >= 169f && menuWidth <= 320f);

        // The row that carries the long label must not push past its own menu.
        float menuRight = menu.getRuntimeCache().getX() + menuWidth;
        for (UIElement entry : menu.entries()) {
            float right = entry.getRuntimeCache().getX() + entry.getRuntimeCache().getWidth();
            assertTrue("a row escaped the menu: " + right + " vs " + menuRight, right <= menuRight + 0.5f);
        }
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

        NodeCreationMenu menu = graph.creationMenu();
        UIElement row = menu.entries().get(0);
        float rowWidth = row.getRuntimeCache().getWidth();
        float inset = labelXOfRow(menu, 0) - row.getRuntimeCache().getX();

        // The twisty sits before the label, so the label is legitimately inset a little. What must never
        // happen is CENTRING, which is what a missing flex-direction produced.
        assertTrue("the label is left-aligned after the twisty, not centred; inset was " + inset
                        + " in a row " + rowWidth + " wide",
                inset > 0f && inset < Math.max(20f, rowWidth * 0.25f));
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

    /**
     * <b>Depth is visible.</b>
     *
     * <p>A tree whose rows all start at the same x is a list with extra rows in it. The indent comes from
     * {@code TreeView}, written at DEFAULT origin, which is the weakest there is — so any sheet rule
     * setting {@code padding-left} on a row silently flattens the whole thing, and it still looks like a
     * plausible menu. Asserting the *difference* between depths is the only thing that catches it.</p>
     */
    @Test
    public void deeperRowsAreIndentedFurther() {
        library.clear();
        library.register(NodeType.of("shader.Position").label("Position").category("Input/Geometry")
                .out("Out", "vec3"));
        // The theme too, because that is what the harness runs and a sheet at a stronger origin is
        // precisely how this breaks.
        window.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:graph"));
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        assertEquals("Input > Geometry > Position", 3, menu.visibleEntries().size());

        float depth0 = labelXOfRow(menu, 0);
        float depth1 = labelXOfRow(menu, 1);
        float depth2 = labelXOfRow(menu, 2);

        assertTrue("depth 1 must sit right of depth 0: " + depth0 + " vs " + depth1, depth1 > depth0 + 4f);
        assertTrue("depth 2 must sit right of depth 1: " + depth1 + " vs " + depth2, depth2 > depth1 + 4f);
    }

    /**
     * The twisty's look (chevron-right/-down, or nothing for a leaf) is a {@code shape(...)} drawn
     * directly by {@code overlay:}, not a font glyph — the bundled Minecraft font has neither
     * U+25B6 nor U+25BC, and a missing glyph draws a blank advance rather than failing, which is
     * exactly what silently swallowed the twisty before this existed. Java no longer decides the
     * appearance at all; it only has to get the state CLASSES right, which is what this checks —
     * {@code default.css}'s {@code nodecreationmenu .__twisty__} rules own the rest.
     */
    @Test
    public void expandableRowsCarryTheRightTwistyStateClasses() {
        library.clear();
        library.register(NodeType.of("shader.Position").label("Position").category("Input").out("Out", "vec3"));
        graph.openCreationMenu(0f, 0f);
        frame();
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        UIElement folderRow = menu.entries().get(0);
        UIElement leafRow = menu.entries().get(1);

        assertTrue("an open folder is __expanded__", folderRow.hasClass(TreeView.EXPANDED_CLASS));
        assertTrue("a leaf is __leaf__", leafRow.hasClass(TreeView.LEAF_CLASS));
        assertFalse("a leaf is not __expanded__", leafRow.hasClass(TreeView.EXPANDED_CLASS));

        menu.treeView().collapseAll();
        frame();
        assertTrue("a closed folder is __collapsed__", folderRow.hasClass(TreeView.COLLAPSED_CLASS));
        assertFalse("and no longer __expanded__", folderRow.hasClass(TreeView.EXPANDED_CLASS));
    }

    /**
     * <b>Up/Down and Enter drive the list while the search box keeps focus.</b>
     *
     * <p>Reported from the harness: the arrows did nothing. Moving focus into the tree is the obvious fix
     * and is wrong twice — it stops you typing, and it would not have worked anyway, because a
     * {@code ListView} sets no focus policy and {@code requestFocus} refuses a {@code FocusPolicy.NONE}
     * element silently. Forwarding the keys keeps one focus owner.</p>
     */
    @Test
    public void arrowsAndEnterDriveTheListWithoutStealingFocus() {
        library.clear();
        library.register(NodeType.of("shader.Add").label("Add").out("Out", "vec3"));
        library.register(NodeType.of("shader.Step").label("Step").out("Out", "vec3"));
        graph.openCreationMenu(0f, 0f);
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        assertTrue("the box owns focus from the start", menu.searchField().isFocused());
        assertEquals(2, menu.visibleEntries().size());
        assertTrue("browsing highlights nothing", menu.treeView().getSelectedIndices().isEmpty());

        pressKey(CgKeyCodes.KEY_DOWN);
        frame();
        assertTrue("the box must KEEP focus, or you cannot carry on typing",
                menu.searchField().isFocused());
        assertEquals("the first arrow lands on the first row, not the second",
                java.util.Set.of(0), menu.treeView().getSelectedIndices());

        pressKey(CgKeyCodes.KEY_DOWN);
        frame();
        assertEquals(java.util.Set.of(1), menu.treeView().getSelectedIndices());

        pressKey(CgKeyCodes.KEY_UP);
        frame();
        assertEquals(java.util.Set.of(0), menu.treeView().getSelectedIndices());

        pressKey(CgKeyCodes.KEY_UP);
        frame();
        assertEquals("clamped at the top rather than wrapping to the end",
                java.util.Set.of(0), menu.treeView().getSelectedIndices());

        int before = graph.nodes().size();
        pressKey(CgKeyCodes.KEY_RETURN);
        frame();
        assertEquals("Enter creates the highlighted row", before + 1, graph.nodes().size());
        assertFalse("and the menu closes behind it", menu.isOpen());
    }

    /**
     * <b>Opening or closing a folder leaves the highlight on that folder.</b>
     *
     * <p>Reported from the harness as "Enter defocuses it", and the name is the interesting part: nothing
     * to do with focus. {@code toggleExpanded} re-flattens the model, {@code refresh()} clears before it
     * re-adds, and {@code ListView} drops any selection a model change invalidated — so every index goes
     * with the clear. The row came back unhighlighted, and the next arrow press restarted from the top.</p>
     */
    @Test
    public void togglingAFolderKeepsTheHighlightOnIt() {
        library.clear();
        library.register(NodeType.of("shader.A").label("A").category("Alpha").out("Out", "vec3"));
        library.register(NodeType.of("shader.B").label("B").category("Beta").out("Out", "vec3"));
        graph.openCreationMenu(0f, 0f);
        frame();

        NodeCreationMenu menu = graph.creationMenu();
        assertEquals("Alpha, A, Beta, B", 4, menu.visibleEntries().size());

        pressKey(CgKeyCodes.KEY_DOWN);
        frame();
        assertEquals("standing on the Alpha folder", java.util.Set.of(0),
                menu.treeView().getSelectedIndices());

        pressKey(CgKeyCodes.KEY_RETURN);
        frame();
        assertEquals("the folder closed", 3, menu.visibleEntries().size());
        assertEquals("and it is still the highlighted row",
                java.util.Set.of(0), menu.treeView().getSelectedIndices());

        pressKey(CgKeyCodes.KEY_RETURN);
        frame();
        assertEquals("re-opened", 4, menu.visibleEntries().size());
        assertEquals(java.util.Set.of(0), menu.treeView().getSelectedIndices());

        // And the arrows carry on from there rather than restarting at the top.
        pressKey(CgKeyCodes.KEY_DOWN);
        frame();
        assertEquals(java.util.Set.of(1), menu.treeView().getSelectedIndices());
        assertTrue("all of it without ever leaving the search box", menu.searchField().isFocused());
    }

    /**
     * <b>A dragged menu stays where it was put, and a reopened one goes back to its anchor.</b>
     *
     * <p>The interesting half is that it stays. A {@code Popover} re-runs {@code AnchoredPlacement} from
     * a per-frame ticker, so anything else writing {@code left}/{@code top} is overwritten within one
     * frame and the menu looks nailed down. Moving it by hand therefore has to <em>detach</em> it from
     * its anchor rather than fight it — which keeps the "one writer of left/top" rule intact instead of
     * breaking it.</p>
     */
    @Test
    public void theMenuCanBeMovedAndStaysPutUntilReopened() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(40f, 40f);
        frame();
        frame();

        assertNotNull("there has to be something to drag it by", menu.titleBar());
        assertFalse(menu.isFreelyPositioned());
        float anchoredLeft = menu.getRuntimeCache().getX();

        menu.moveTo(200f, 150f);
        // Several frames, because the placement ticker gets a go on every one of them.
        frame();
        frame();
        frame();

        assertTrue(menu.isFreelyPositioned());
        float movedLeft = menu.getRuntimeCache().getX();
        assertTrue("the placement ticker dragged it back to its anchor: " + movedLeft
                        + " vs anchored " + anchoredLeft,
                Math.abs(movedLeft - anchoredLeft) > 1f);

        // Reopening re-anchors — a menu moved once must not open in that spot forever.
        graph.openCreationMenu(40f, 40f);
        frame();
        frame();
        assertFalse(menu.isFreelyPositioned());
        assertEquals("back to where its anchor puts it",
                anchoredLeft, menu.getRuntimeCache().getX(), 1f);
    }

    /**
     * <b>A wire dropped on a valid port connects and the menu stays shut.</b>
     *
     * <p>It used to do both, which looked like the menu opening for no reason. The cause was ordering in
     * {@code UIDragController}: {@code onDragEnd} ran <em>before</em> {@code DragEvent.Drop}, so the port
     * compared its connection count against the drag-start snapshot, saw no change, and concluded the
     * wire had landed on empty canvas — then the drop fired and connected. The web's order is drop, then
     * dragend, and it is the only one that lets a source ask "did my drag land?" at all.</p>
     *
     * <p>Driven through {@code consumeMouseEvent} end to end, because every unit-level test of this
     * passed while the gesture was broken.</p>
     */
    @Test
    public void droppingAWireOnAPortConnectsWithoutOpeningTheMenu() {
        GraphNode from = new GraphNode("From");
        NodePort out = from.addOutput(VEC3, "Out");
        graph.addNode(from, 10f, 10f);

        GraphNode to = new GraphNode("To");
        NodePort in = to.addInput(VEC3, "In");
        graph.addNode(to, 200f, 10f);
        frame();
        frame();

        dragBetween(out, in);
        frame();

        assertTrue("the wire must land", out.isConnected());
        assertEquals(1, graph.getConnections().size());
        assertFalse("and the create menu must not appear on a successful connection",
                graph.creationMenu().isOpen());
    }

    /** The mirror: dropped on nothing, the menu is exactly what should appear. */
    @Test
    public void droppingAWireOnEmptyCanvasStillOpensTheMenu() {
        GraphNode from = new GraphNode("From");
        NodePort out = from.addOutput(VEC3, "Out");
        graph.addNode(from, 10f, 10f);
        frame();
        frame();

        var start = centreOf(out);
        var empty = new org.joml.Vector2f(start.x() + 40f, start.y() + 220f);
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(start.x()), Math.round(start.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 11L));
        frame();
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(empty.x()), Math.round(empty.y()), 0, 0, -1, false, 0f, 12L));
        frame();
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(empty.x()), Math.round(empty.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, false, 0f, 13L));
        frame();

        assertFalse(out.isConnected());
        assertTrue("a wire dropped on nothing is exactly when the menu should offer something",
                graph.creationMenu().isOpen());
    }

    private org.joml.Vector2f centreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
    }

    private void dragBetween(UIElement fromPort, UIElement toPort) {
        var a = centreOf(fromPort);
        var b = centreOf(toPort);
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(a.x()), Math.round(a.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 21L));
        frame();
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(b.x()), Math.round(b.y()), 0, 0, -1, false, 0f, 22L));
        frame();
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(b.x()), Math.round(b.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, false, 0f, 23L));
    }

    private void pressKey(int keyCode) {
        window.getInputHandler().consumeKeyboardEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Keyboard.Event(
                        '\0', keyCode, true, false, 0L));
    }

    private void pressCentreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        var at = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 7L));
    }

    private static float labelXOfRow(NodeCreationMenu menu, int index) {
        UIElement row = menu.entries().get(index);
        return row.getChildren().get(1).getRuntimeCache().getX();
    }

    @Test
    public void typingNarrowsTheList() {
        NodeCreationMenu menu = graph.creationMenu();
        graph.openCreationMenu(0f, 0f);
        frame();
        assertEquals(2, menu.visibleOffers().size());

        menu.searchField().setText("plus");
        frame();

        assertEquals("'plus' is Add's synonym", 1, menu.visibleOffers().size());
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
