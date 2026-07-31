package com.crystalgui.headless;

import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphIds;
import com.crystalgui.graph.NodeBuilder;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.2.5 — the graph document.
 *
 * <h3>Why this lives in headlessTest</h3>
 * <p>A dedicated server authors and validates graphs, so the model must run with no GL context and no
 * CrystalGraphics core on the classpath. The absence <em>is</em> the assertion: a stray import of
 * anything paint-time fails here with {@code NoClassDefFoundError} rather than in production.</p>
 *
 * <h3>What is actually being asserted</h3>
 * <p>The properties that make a document trustworthy rather than merely present: that it round-trips
 * byte-identically (which is what makes content addressing work at all), that a cycle is refused at the
 * moment of connection rather than discovered by the compiler, that an unknown node type survives a
 * load, and that ids are stable — because every edge in the file is a bet on that.</p>
 */
public class GraphDocumentTest {

    /** The fluent path, which is what a caller should be using — a named id only because a test has to
     * be able to say which node it means. */
    private static NodeData node(GraphDocument doc, String id, float x, float y) {
        return doc.newNode("test.node").id(id).at(x, y)
                .in("in", "vec3")
                .out("out", "vec3")
                .add();
    }

    private static NodeData node(String id, float x, float y) {
        return NodeBuilder.of("test.node").id(id).at(x, y)
                .in("in", "vec3").out("out", "vec3").build();
    }

    private static GraphDocument chain(String... ids) {
        GraphDocument doc = new GraphDocument();
        for (int i = 0; i < ids.length; i++) node(doc, ids[i], i * 200f, 0f);
        for (int i = 0; i + 1 < ids.length; i++) doc.link(ids[i], "out", ids[i + 1], "in");
        return doc;
    }

    // ── Structure ───────────────────────────────────────────────────────────

    @Test
    public void connectingLinksTwoPorts() {
        GraphDocument doc = chain("alpha", "beta");

        assertEquals(1, doc.edges().size());
        EdgeData edge = doc.edges().get(0);
        assertEquals("alpha", edge.from().nodeId());
        assertEquals("beta", edge.to().nodeId());
    }

    /** One edge per input, many per output — Unity's rule, so an occupied input is replaced. */
    @Test
    public void anOccupiedInputIsReplacedRatherThanRefused() {
        GraphDocument doc = new GraphDocument();
        doc.addNode(node("alpha", 0f, 0f));
        doc.addNode(node("beta", 0f, 200f));
        doc.addNode(node("target", 300f, 0f));

        doc.connect(new PortRef("alpha", "out"), new PortRef("target", "in"));
        doc.connect(new PortRef("beta", "out"), new PortRef("target", "in"));

        assertEquals("the input holds exactly one edge", 1, doc.edges().size());
        assertEquals("beta", doc.edges().get(0).from().nodeId());
    }

    @Test
    public void removingANodeTakesItsEdges() {
        GraphDocument doc = chain("alpha", "beta", "gamma");
        assertEquals(2, doc.edges().size());

        doc.removeNode("beta");

        assertEquals("an edge to a node that is gone is not a state this can represent",
                0, doc.edges().size());
        assertEquals(2, doc.nodeCount());
    }

    // ── Validation ──────────────────────────────────────────────────────────

    /**
     * <b>A cycle is refused at connect time.</b>
     *
     * <p>The compiler topologically sorts, so a cycle is not a rendering artefact — it is a graph that
     * cannot compile. Refusing it at the moment of connection is the only point where the user can still
     * see which wire caused it.</p>
     */
    @Test
    public void aCycleIsRefusedWhenTheWireIsDrawn() {
        GraphDocument doc = chain("alpha", "beta", "gamma");

        String why = doc.whyNotConnectable(new PortRef("gamma", "out"), new PortRef("alpha", "in"));

        assertNotNull("closing the loop must be refused", why);
        assertTrue("and the reason must name it: " + why, why.contains("cycle"));
        assertNull(doc.connect(new PortRef("gamma", "out"), new PortRef("alpha", "in")));
        assertEquals(2, doc.edges().size());
    }

    @Test
    public void aNodeCannotFeedItself() {
        GraphDocument doc = new GraphDocument();
        doc.addNode(node("alpha", 0f, 0f));

        assertFalse(doc.canConnect(new PortRef("alpha", "out"), new PortRef("alpha", "in")));
    }

    @Test
    public void directionsAndTypesAreChecked() {
        GraphDocument doc = new GraphDocument();
        doc.addNode(node("alpha", 0f, 0f));
        doc.addNode(new NodeData("beta", "test.node", 200f, 0f,
                List.of(PortSpec.input("in", "float")), java.util.Map.of()));

        assertTrue(doc.whyNotConnectable(new PortRef("alpha", "out"), new PortRef("beta", "in"))
                .contains("incompatible"));
        assertTrue("two outputs is not a wire",
                doc.whyNotConnectable(new PortRef("alpha", "out"), new PortRef("alpha", "out")) != null);

        // The consumer's rule, not the document's: GLSL promotes a scalar into a vector.
        doc.setTypeCompatibility((source, target) -> source.equals("float") || source.equals(target));
        doc.addNode(new NodeData("gamma", "test.node", 400f, 0f,
                List.of(PortSpec.output("out", "float")), java.util.Map.of()));
        assertTrue("promotion is the consumer's to define",
                doc.canConnect(new PortRef("gamma", "out"), new PortRef("beta", "in")));
    }

    /** The order the compiler will evaluate in, and the answer to "is this graph valid?" in one call. */
    @Test
    public void topologicalOrderPutsEveryNodeAfterWhatFeedsIt() {
        GraphDocument doc = chain("alpha", "beta", "gamma");

        List<NodeData> order = doc.topologicalOrder();

        assertNotNull(order);
        assertEquals(List.of("alpha", "beta", "gamma"),
                order.stream().map(NodeData::id).toList());
    }

    /**
     * A cycle has no evaluation order, and can only arrive through a load — {@code connect} refuses to
     * create one, so {@code restoreEdge} is how a hand-edited or corrupt file presents it.
     */
    @Test
    public void topologicalOrderReportsACycleAsNull() {
        GraphDocument doc = chain("alpha", "beta");
        assertNotNull(doc.topologicalOrder());

        doc.restoreEdge(EdgeData.of("beta", "out", "alpha", "in"));

        assertNull("a cycle has no evaluation order", doc.topologicalOrder());
    }

    // ── Serialization ───────────────────────────────────────────────────────

    /**
     * <b>Encode → decode → encode is byte-identical.</b>
     *
     * <p>Not tidiness: it is the whole basis of content addressing. A document that encodes differently
     * on the second pass cannot be named by its hash, and everything built on that — sending a graph by
     * hash, comparing two revisions, caching a compile — silently stops working.</p>
     */
    @Test
    public void aDocumentRoundTripsByteIdentically() {
        GraphDocument doc = chain("alpha", "beta", "gamma");
        doc.replaceNode(doc.node("beta").withProperty("space", "world").withProperty("scale", "0.9"));

        Object once = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, doc);
        GraphDocument decoded = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, once);
        Object twice = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, decoded);

        assertEquals(ContentHash.of(PlainOps.INSTANCE, once), ContentHash.of(PlainOps.INSTANCE, twice));
        assertEquals(doc.nodeCount(), decoded.nodeCount());
        assertEquals(doc.edges(), decoded.edges());
        assertEquals("world", decoded.node("beta").properties().get("space"));
    }

    @Test
    public void jsonAndPlainOpsAgree() {
        GraphDocument doc = chain("alpha", "beta");

        var json = GraphCodecs.DOCUMENT.encode(JsonOps.INSTANCE, doc);
        GraphDocument fromJson = GraphCodecs.DOCUMENT.decode(JsonOps.INSTANCE, json);

        assertEquals(doc.nodeCount(), fromJson.nodeCount());
        assertEquals(doc.edges(), fromJson.edges());
    }

    /**
     * <b>A document whose node types are unknown still opens, keeps its edges, and round-trips.</b>
     *
     * <p>The reason ports are stored per node rather than looked up from the type. This is the "opened
     * without the plugin" case, and it is a deliberate divergence from {@code ElementRegistry}, which
     * throws on an unknown tag — eating somebody's graph is a far worse outcome than showing a grey
     * box.</p>
     */
    @Test
    public void anUnknownNodeTypeSurvivesALoad() {
        GraphDocument doc = new GraphDocument();
        doc.addNode(new NodeData("alpha", "some.mod.NotInstalled", 10f, 20f,
                List.of(PortSpec.output("out", "mystery")), java.util.Map.of("k", "v")));
        doc.addNode(new NodeData("beta", "some.mod.AlsoMissing", 300f, 20f,
                List.of(PortSpec.input("in", "mystery")), java.util.Map.of()));
        doc.setTypeCompatibility(TypeCompatibility.ANY);
        doc.connect(new PortRef("alpha", "out"), new PortRef("beta", "in"));

        Object encoded = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, doc);
        GraphDocument reopened = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, encoded);

        assertEquals(2, reopened.nodeCount());
        assertEquals("the edge must survive a build that knows neither type", 1, reopened.edges().size());
        assertEquals("some.mod.NotInstalled", reopened.node("alpha").typeId());
        assertEquals("v", reopened.node("alpha").properties().get("k"));
        assertEquals(ContentHash.of(PlainOps.INSTANCE, encoded),
                ContentHash.of(PlainOps.INSTANCE, GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, reopened)));
    }

    // ── Copy, paste, ids ────────────────────────────────────────────────────

    /**
     * <b>Duplicate keeps internal edges and drops external ones.</b>
     *
     * <p>A choice, not a law — Blender keeps incoming links. Dropping them means a duplicate never
     * silently re-feeds the original's upstream, which is a graph the user did not draw.</p>
     */
    @Test
    public void copyingKeepsInternalEdgesAndDropsExternalOnes() {
        GraphDocument doc = chain("alpha", "beta", "gamma");

        GraphDocument copy = doc.copyOf(List.of("beta", "gamma"), 40f, 40f);

        assertEquals(2, copy.nodeCount());
        assertEquals("beta->gamma is internal and comes along", 1, copy.edges().size());
        assertTrue("and the ids are fresh, or a paste would collide",
                copy.nodes().stream().noneMatch(n -> n.id().equals("beta") || n.id().equals("gamma")));
        assertEquals("offset so the copy does not hide the original",
                doc.node("beta").x() + 40f, copy.nodes().iterator().next().x(), 1e-4f);
    }

    @Test
    public void pastingIntoTheSameDocumentReissuesCollidingIds() {
        GraphDocument doc = chain("alpha", "beta");
        GraphDocument clipboard = doc.copyOf(List.of("alpha", "beta"), 0f, 0f);

        List<String> added = doc.merge(clipboard);

        assertEquals(4, doc.nodeCount());
        assertEquals(2, added.size());
        assertEquals("the copy's own edge comes with it", 2, doc.edges().size());
    }

    /** An id becomes a prefix in generated code, so it must be a legal identifier. */
    @Test
    public void generatedIdsAreUsableInGeneratedCode() {
        for (int i = 0; i < 200; i++) {
            String id = GraphIds.generate();
            assertTrue(id, GraphIds.isValid(id));
            assertFalse("must not start with a digit: " + id, Character.isDigit(id.charAt(0)));
        }
        assertFalse(GraphIds.isValid("has-a-dash"));
        assertFalse(GraphIds.isValid("2fast"));
    }

    /**
     * <b>The builder generates ids, and that is the point of it.</b>
     *
     * <p>The verbose constructor made every caller invent an id, which is how a codebase ends up with
     * hand-written ids that eventually collide — and the collision surfaces half a graph later as a
     * rejected {@code addNode}. Naming one is now the exception, for loaders and for tests that have to
     * say which node they mean.</p>
     */
    @Test
    public void theBuilderGeneratesIdsAndReadsLikeTheGraph() {
        GraphDocument doc = new GraphDocument();
        doc.setTypeCompatibility((from, to) -> from.equals("float") || from.equals(to));

        NodeData perlin = doc.newNode("shader.PerlinNoise3D").at(250f, 200f)
                .in("Sampling Coordinates", "vec3")
                .in("Noise Scale", "float")
                .out("Value", "float")
                .prop("Noise Scale", "0.9")
                .add();
        NodeData add = doc.newNode("shader.Add").at(480f, 40f)
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3")
                .add();

        assertNotEquals("two nodes must not share an id", perlin.id(), add.id());
        assertTrue(GraphIds.isValid(perlin.id()));
        assertNotNull("and they wire up by name", doc.link(perlin, "Value", add, "B"));
        assertEquals("0.9", perlin.properties().get("Noise Scale"));
    }

    // ── The changeset ───────────────────────────────────────────────────────

    /**
     * <b>The document reports what changed, so the view never rebuilds.</b>
     *
     * <p>Rebuilding detaches the element under the pointer, and in a graph editor the pointer is
     * routinely on the thing that just changed. Unity and LDLib2 both landed on a changeset for the same
     * reason.</p>
     */
    @Test
    public void theChangesetDescribesExactlyWhatMoved() {
        GraphDocument doc = chain("alpha", "beta");
        doc.getChangeset().clear();

        doc.moveNode("alpha", 50f, 60f);
        doc.addNode(node("gamma", 400f, 0f));
        doc.connect(new PortRef("beta", "out"), new PortRef("gamma", "in"));

        var changes = doc.getChangeset();
        assertEquals(java.util.Set.of("alpha"), changes.movedNodes());
        assertEquals(java.util.Set.of("gamma"), changes.addedNodes());
        assertEquals(1, changes.addedEdges().size());
        assertTrue(changes.removedNodes().isEmpty());

        changes.clear();
        assertTrue(changes.isEmpty());
    }

    /** A node removed and re-added in one batch never left, as far as the view is concerned — and
     * rebuilding it would be exactly the detach the changeset exists to avoid. */
    @Test
    public void addingBackWhatWasRemovedCancelsOut() {
        GraphDocument doc = new GraphDocument();
        doc.addNode(node("alpha", 0f, 0f));
        doc.getChangeset().clear();

        doc.removeNode("alpha");
        doc.addNode(node("alpha", 0f, 0f));

        assertTrue(doc.getChangeset().removedNodes().isEmpty());
        assertTrue(doc.getChangeset().addedNodes().isEmpty());
    }
}
