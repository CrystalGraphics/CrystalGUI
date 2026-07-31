package com.crystalgui.headless;

import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.2.6 — the node library.
 *
 * <h3>What is actually being asserted</h3>
 * <p>The library's whole job is answering two questions well: <b>what did the user type</b>, and
 * <b>what can this wire land on</b>. The second is the interesting one — Unity's contextual menu lists
 * every compatible <em>port</em> rather than every compatible node, so picking an entry creates the node
 * and completes the connection in one step.</p>
 *
 * <p>Headless, because a server assembling a graph from a template needs the same library, and because
 * a node type that quietly depended on a widget would fail here rather than in production.</p>
 */
public class NodeLibraryTest {

    /** GLSL's rule, which is the case the filter has to get right: a scalar promotes into a vector. */
    private static final TypeCompatibility PROMOTES =
            (from, to) -> from.equals("float") || from.equals(to);

    private NodeTypeRegistry library;

    @Before
    public void setUp() {
        library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Add").label("Add").category("Math")
                .synonyms("plus", "sum")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        library.register(NodeType.of("shader.Multiply").label("Multiply").category("Math")
                .synonyms("times", "product")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        library.register(NodeType.of("shader.Position").label("Position").category("Input/Geometry")
                .out("Out", "vec3")
                .defaultProperty("Space", "World"));
        library.register(NodeType.of("shader.Step").label("Step").category("Math")
                .in("Edge", "float").in("In", "float").out("Out", "float"));
    }

    // ── Creating ────────────────────────────────────────────────────────────

    @Test
    public void aTypeCreatesANodeWithItsPortsAndDefaults() {
        NodeData node = library.get("shader.Position").create(40f, 60f);

        assertEquals("shader.Position", node.typeId());
        assertEquals(40f, node.x(), 1e-4f);
        assertEquals(1, node.ports().size());
        assertEquals("World", node.properties().get("Space"));
        assertTrue("every created node gets its own id",
                !node.id().equals(library.get("shader.Position").create(0f, 0f).id()));
    }

    /** The library builds document nodes, never widgets — which is what lets a server use it. */
    @Test
    public void aCreatedNodeGoesStraightIntoADocument() {
        GraphDocument doc = new GraphDocument();
        doc.setTypeCompatibility(PROMOTES);

        NodeData position = doc.addNode(library.get("shader.Position").create(0f, 0f));
        NodeData add = doc.addNode(library.get("shader.Add").create(300f, 0f));

        assertNotNull(doc.link(position, "Out", add, "A"));
        assertEquals(2, doc.nodeCount());
    }

    // ── Search ──────────────────────────────────────────────────────────────

    @Test
    public void searchMatchesLabelCategoryAndSynonyms() {
        assertEquals(1, library.search("multi").size());
        assertEquals("by category", 3, library.search("Math").size());
        assertEquals("by synonym — 'plus' finds Add, which is the point of declaring them",
                "shader.Add", library.search("plus").get(0).id());
        assertEquals("times", "shader.Multiply", library.search("times").get(0).id());
        assertEquals("case-insensitive", 1, library.search("POSITION").size());
        assertEquals("a blank query opens the menu full", 4, library.search("").size());
    }

    // ── The contextual filter ───────────────────────────────────────────────

    /**
     * <b>A dropped wire offers ports, not nodes.</b>
     *
     * <p>Unity lists every compatible port on every matching node, so choosing an entry creates the node
     * <em>and</em> lands the wire. Add and Multiply each contribute two entries, because either input
     * would do.</p>
     */
    @Test
    public void droppingAVec3OffersEveryPortThatCouldTakeIt() {
        List<NodeTypeRegistry.Offer> offers = library.offersForOutput("vec3", PROMOTES, "");

        // Add.A, Add.B, Multiply.A, Multiply.B — Position has no inputs, Step's are floats.
        assertEquals(4, offers.size());
        assertTrue(offers.stream().allMatch(o -> o.port().typeId().equals("vec3")));
        assertTrue(offers.stream().anyMatch(o -> o.label().equals("Add - A")));
        assertTrue(offers.stream().anyMatch(o -> o.label().equals("Add - B")));
    }

    /**
     * <b>The filter asks the document's compatibility rule, not equality.</b>
     *
     * <p>A float output promotes into a vec3 input under GLSL's rules, so it must be offered those
     * ports. Filtering on equality would leave the menu empty on exactly the graphs that needed it.</p>
     */
    @Test
    public void promotionWidensTheOffersRatherThanBeingIgnored() {
        List<NodeTypeRegistry.Offer> promoting = library.offersForOutput("float", PROMOTES, "");
        List<NodeTypeRegistry.Offer> exact = library.offersForOutput("float", TypeCompatibility.EXACT, "");

        assertEquals("float feeds both Step inputs, and promotes into all four vec3 inputs",
                6, promoting.size());
        assertEquals("under equality, only the two float inputs", 2, exact.size());
    }

    @Test
    public void theSearchNarrowsTheContextualOffersToo() {
        List<NodeTypeRegistry.Offer> offers = library.offersForOutput("vec3", PROMOTES, "sum");

        assertEquals("'sum' is Add's synonym, so only its two inputs remain", 2, offers.size());
        assertTrue(offers.stream().allMatch(o -> o.type().id().equals("shader.Add")));
    }

    /** Dragging out of an input asks the mirror question. */
    @Test
    public void draggingFromAnInputOffersOutputsThatCouldFeedIt() {
        List<NodeTypeRegistry.Offer> offers = library.offersForInput("vec3", PROMOTES, "");

        // Add.Out, Multiply.Out, Position.Out (vec3) and Step.Out (float, promotes).
        assertEquals(4, offers.size());
        assertTrue(offers.stream().allMatch(o -> o.port().direction().isOutput()));
    }

    @Test
    public void anIncompatibleDragOffersNothingRatherThanEverything() {
        List<NodeTypeRegistry.Offer> offers =
                library.offersForOutput("texture2d", TypeCompatibility.EXACT, "");

        assertTrue("nothing here takes a texture, and the menu must say so by being empty",
                offers.isEmpty());
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Test
    public void duplicateIdsAreRefused() {
        try {
            library.register(NodeType.of("shader.Add").label("Something Else"));
            fail("two types must not share an id");
        } catch (IllegalArgumentException expected) {
            // matching every other registry in this codebase
        }
    }

    /** A library belongs to an editor, not the process — a shader graph and a dialogue graph in one
     * process have entirely different ones. */
    @Test
    public void librariesAreIndependent() {
        NodeTypeRegistry other = new NodeTypeRegistry();
        other.register(NodeType.of("dialogue.Say").label("Say"));

        assertEquals(4, library.size());
        assertEquals(1, other.size());
        assertNull(other.get("shader.Add"));
    }
}
