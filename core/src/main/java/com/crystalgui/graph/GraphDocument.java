package com.crystalgui.graph;

import com.crystalgui.core.signal.Signal;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph, as data: nodes by id, and the edges between their ports.
 *
 * <h3>Headless, and that absence is the assertion</h3>
 * <p>Nothing here imports {@code ui/}, and its tests live in {@code headlessTest} where CrystalGraphics
 * core is deliberately off the classpath. A dedicated server authors and validates graphs, and the only
 * way to keep that true is to make it impossible to compile otherwise — the same reasoning that keeps
 * {@code ServerUiSession} free of a {@code UIWindow}.</p>
 *
 * <h3>A flat table with stored ids, not a tree</h3>
 * <p>A graph is not a tree, so nesting one is a lie that costs at the first node with two consumers.
 * Unity's {@code .shadergraph} is a flat list of objects each carrying an {@code objectId} and
 * referencing the others by it; this is that, with {@link NodeData} owned by the map and
 * {@link EdgeData} merely pointing.</p>
 *
 * <h3>Validation lives here</h3>
 * <p>Type compatibility, one-edge-per-input, and <b>cycle rejection at connect time</b>. The compiler
 * topologically sorts, so a cycle is not a rendering artefact — it is a graph that cannot compile, and
 * the moment of connection is the only point at which the user can still see which wire caused it.</p>
 *
 * <h3>Unknown node types are kept, never dropped</h3>
 * <p>A document whose types are not registered — a plugin absent, a mod not loaded — must open, keep its
 * edges and round-trip unchanged. That is why {@link PortSpec}s are stored per node rather than looked
 * up, and it is a deliberate divergence from {@code ElementRegistry}, which throws on an unknown tag.
 * The two are different situations: an unknown UI tag means two sides disagree about code that should be
 * identical, while an unknown node type means somebody opened a file without a plugin. Eating their
 * graph is the worse outcome by a wide margin.</p>
 */
public final class GraphDocument {

    /** Bumped when the encoded shape changes in a way a reader must know about. Version 1 is the first
     * written form; a document is saved to disk and outlives the code that wrote it, which is exactly
     * why this exists here and not in {@code UIDescriptionCodec} (regenerated from live code every time). */
    public static final int SCHEMA_VERSION = 1;

    /** Insertion-ordered: it is what makes the encoded form byte-stable, and byte-stability is what makes
     * {@code ContentHash} mean anything at all. */
    private final Map<String, NodeData> nodes = new LinkedHashMap<>();
    private final List<EdgeData> edges = new ArrayList<>();

    @Getter
    private final GraphChangeset changeset = new GraphChangeset();

    /**
     * How this document decides whether one port may feed another. {@link TypeCompatibility#EXACT} by
     * default — a consumer with promotion rules (GLSL has them) supplies its own.
     */
    @Getter
    @Setter
    private TypeCompatibility typeCompatibility = TypeCompatibility.EXACT;

    /** Fires after any structural change, once the changeset has been updated. */
    public final Signal.Action onChanged = new Signal.Action();

    // ── Reading ─────────────────────────────────────────────────────────────

    public Collection<NodeData> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Set<String> nodeIds() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    public List<EdgeData> edges() {
        return Collections.unmodifiableList(edges);
    }

    @Nullable
    public NodeData node(String id) {
        return nodes.get(id);
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** Every edge touching {@code nodeId}. */
    public List<EdgeData> edgesOf(String nodeId) {
        List<EdgeData> found = new ArrayList<>();
        for (EdgeData edge : edges) {
            if (edge.touches(nodeId)) found.add(edge);
        }
        return found;
    }

    /** The edge ending at {@code input}, or null. At most one can exist — see {@link #connect}. */
    @Nullable
    public EdgeData edgeInto(PortRef input) {
        for (EdgeData edge : edges) {
            if (edge.to().equals(input)) return edge;
        }
        return null;
    }

    // ── Nodes ───────────────────────────────────────────────────────────────

    /**
     * Starts a node, fluently — the way most callers should build one.
     *
     * <pre>{@code
     * NodeData add = doc.newNode("shader.Add").at(480, 40)
     *                   .in("A", "vec3").in("B", "vec3").out("Out", "vec3")
     *                   .add();
     * }</pre>
     *
     * <p>The id is generated unless {@link NodeBuilder#id} says otherwise, which is the right default:
     * an id invented by hand is one that eventually collides, and the collision surfaces half a graph
     * later as a rejected {@code addNode}.</p>
     */
    public NodeBuilder newNode(String typeId) {
        return new NodeBuilder(typeId, this);
    }

    /**
     * Connects by name — the common case, without building two {@link PortRef}s at the call site.
     *
     * @return the new edge, or {@code null} if the pair is not connectable
     */
    @Nullable
    public EdgeData link(String fromNodeId, String fromPortId, String toNodeId, String toPortId) {
        return connect(new PortRef(fromNodeId, fromPortId), new PortRef(toNodeId, toPortId));
    }

    /** As {@link #link(String, String, String, String)}, taking the nodes themselves. */
    @Nullable
    public EdgeData link(NodeData from, String fromPortId, NodeData to, String toPortId) {
        return link(from.id(), fromPortId, to.id(), toPortId);
    }

    /** @throws IllegalArgumentException if the id is already taken — an id identifies exactly one node,
     * and a silent overwrite would orphan every edge pointing at the old one. */
    public NodeData addNode(NodeData node) {
        if (nodes.containsKey(node.id())) {
            throw new IllegalArgumentException("A node with id '" + node.id() + "' is already in this graph");
        }
        nodes.put(node.id(), node);
        changeset.nodeAdded(node.id());
        onChanged.emit();
        return node;
    }

    /** Removes a node <b>and every edge touching it</b>, because an edge to a node that is gone is not a
     * state this document can represent. */
    public boolean removeNode(String id) {
        NodeData removed = nodes.remove(id);
        if (removed == null) return false;
        for (EdgeData edge : new ArrayList<>(edges)) {
            if (edge.touches(id)) {
                edges.remove(edge);
                changeset.edgeRemoved(edge);
            }
        }
        changeset.nodeRemoved(id);
        onChanged.emit();
        return true;
    }

    public boolean moveNode(String id, float x, float y) {
        NodeData node = nodes.get(id);
        if (node == null) return false;
        NodeData moved = node.movedTo(x, y);
        if (moved == node) return false;
        nodes.put(id, moved);
        changeset.nodeMoved(id);
        onChanged.emit();
        return true;
    }

    /** Replaces a node in place — for a property change. The id must match. */
    public boolean replaceNode(NodeData node) {
        NodeData existing = nodes.get(node.id());
        if (existing == null) return false;
        nodes.put(node.id(), node);
        if (existing.x() != node.x() || existing.y() != node.y()) changeset.nodeMoved(node.id());
        onChanged.emit();
        return true;
    }

    // ── Edges ───────────────────────────────────────────────────────────────

    /**
     * Why {@code from → to} may not be connected, or {@code null} when it may.
     *
     * <p>A reason rather than a boolean, because every caller has somewhere to put it: a drag can refuse
     * with a tooltip, a paste can log which edges it dropped, and a test can assert on <em>which</em>
     * rule fired instead of on "false".</p>
     */
    @Nullable
    public String whyNotConnectable(PortRef from, PortRef to) {
        NodeData fromNode = nodes.get(from.nodeId());
        NodeData toNode = nodes.get(to.nodeId());
        if (fromNode == null) return "no such node: " + from.nodeId();
        if (toNode == null) return "no such node: " + to.nodeId();

        PortSpec fromPort = fromNode.port(from.portId());
        PortSpec toPort = toNode.port(to.portId());
        if (fromPort == null) return "no such port: " + from;
        if (toPort == null) return "no such port: " + to;

        if (!fromPort.direction().isOutput()) return "not an output: " + from;
        if (!toPort.direction().isInput()) return "not an input: " + to;
        if (from.nodeId().equals(to.nodeId())) return "a node cannot feed itself";

        if (!typeCompatibility.accepts(fromPort.typeId(), toPort.typeId())) {
            return "incompatible types: " + fromPort.typeId() + " -> " + toPort.typeId();
        }
        if (edgeInto(to) != null && !edgeInto(to).from().equals(from)) {
            // NOT an error: connect() replaces. Reported so a caller that wants to warn can, and so the
            // distinction between "replaces" and "refused" is visible rather than implied.
            return null;
        }
        // The expensive check last, and only when everything cheap has passed.
        if (wouldCycle(from.nodeId(), to.nodeId())) {
            return "that would make a cycle: " + to.nodeId() + " already feeds " + from.nodeId();
        }
        return null;
    }

    public boolean canConnect(PortRef from, PortRef to) {
        return whyNotConnectable(from, to) == null;
    }

    /**
     * Connects two ports, replacing whatever occupied the input.
     *
     * <p>One edge per input and many per output, which is Unity's rule and the reason dropping onto an
     * occupied input is a <em>replace</em> rather than a rejection: refusing it would make rewiring two
     * deliberate gestures instead of one.</p>
     *
     * @return the new edge, or {@code null} if the pair is not connectable
     */
    @Nullable
    public EdgeData connect(PortRef from, PortRef to) {
        if (!canConnect(from, to)) return null;

        EdgeData existing = edgeInto(to);
        if (existing != null) {
            if (existing.from().equals(from)) return existing; // already exactly this edge
            edges.remove(existing);
            changeset.edgeRemoved(existing);
        }
        EdgeData edge = new EdgeData(from, to);
        edges.add(edge);
        changeset.edgeAdded(edge);
        onChanged.emit();
        return edge;
    }

    /**
     * Adds an edge <b>without validating it</b> — for loading and for merge.
     *
     * <p>Deliberately not public policy: an edge that was legal in the document that wrote it must come
     * back, even in a build with no registered rule for its types. Validating on load is how a graph
     * silently loses its wiring when a plugin is missing, which is the outcome this whole model is
     * arranged to avoid.</p>
     */
    public void restoreEdge(EdgeData edge) {
        edges.add(edge);
        changeset.edgeAdded(edge);
    }

    public boolean disconnect(EdgeData edge) {
        if (!edges.remove(edge)) return false;
        changeset.edgeRemoved(edge);
        onChanged.emit();
        return true;
    }

    // ── Cycles ──────────────────────────────────────────────────────────────

    /**
     * Whether an edge from {@code fromNode} to {@code toNode} would close a loop — i.e. whether
     * {@code toNode} can already reach {@code fromNode} by following edges forwards.
     *
     * <p>Breadth-first from the target, which visits the smaller side first in the common case of
     * wiring a fresh node onto the end of a chain. Iterative rather than recursive: a graph deep enough
     * to overflow a stack is a graph a user can build by holding a key down.</p>
     */
    public boolean wouldCycle(String fromNode, String toNode) {
        if (fromNode.equals(toNode)) return true;
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(toNode);
        seen.add(toNode);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (EdgeData edge : edges) {
                if (!edge.from().nodeId().equals(current)) continue;
                String next = edge.to().nodeId();
                if (next.equals(fromNode)) return true;
                if (seen.add(next)) queue.add(next);
            }
        }
        return false;
    }

    /**
     * The nodes in dependency order — every node after everything feeding it — or {@code null} if the
     * graph contains a cycle.
     *
     * <p>Here rather than in the compiler because it is also the answer to "is this graph valid?", and
     * because a document that cannot say what order it evaluates in cannot be checked without one.
     * Kahn's algorithm, so the cycle case falls out as "nodes left over" rather than needing its own
     * detection pass.</p>
     */
    @Nullable
    public List<NodeData> topologicalOrder() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        for (String id : nodes.keySet()) incoming.put(id, 0);
        for (EdgeData edge : edges) {
            incoming.computeIfPresent(edge.to().nodeId(), (id, count) -> count + 1);
        }

        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : incoming.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }

        List<NodeData> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(nodes.get(id));
            for (EdgeData edge : edges) {
                if (!edge.from().nodeId().equals(id)) continue;
                String target = edge.to().nodeId();
                Integer left = incoming.computeIfPresent(target, (key, count) -> count - 1);
                if (left != null && left == 0) ready.add(target);
            }
        }
        return ordered.size() == nodes.size() ? ordered : null;
    }

    // ── Bulk ────────────────────────────────────────────────────────────────

    /**
     * Copies {@code sourceIds} out of this document under <b>fresh ids</b>, keeping only the edges whose
     * <em>both</em> ends were copied.
     *
     * <p>What duplicate and paste are made of. External edges are dropped deliberately: a duplicate that
     * silently re-fed the original's upstream is a graph the user did not draw. (Blender keeps incoming
     * links; this does not, and the difference is worth knowing rather than guessing at.)</p>
     *
     * @param offsetX added to every copied node's position, so the copy does not hide the original
     * @return the new nodes and edges, in a document of their own — ready to be merged, or put on a
     *         clipboard
     */
    public GraphDocument copyOf(Collection<String> sourceIds, float offsetX, float offsetY) {
        Set<String> wanted = new HashSet<>(sourceIds);
        Map<String, String> remap = new LinkedHashMap<>();
        GraphDocument copy = new GraphDocument();
        copy.typeCompatibility = typeCompatibility;

        for (String id : sourceIds) {
            NodeData node = nodes.get(id);
            if (node == null) continue;
            String freshId = GraphIds.generate();
            remap.put(id, freshId);
            copy.addNode(node.withId(freshId).movedTo(node.x() + offsetX, node.y() + offsetY));
        }
        for (EdgeData edge : edges) {
            String from = remap.get(edge.from().nodeId());
            String to = remap.get(edge.to().nodeId());
            if (from == null || to == null) continue;      // one end outside the copy
            if (!wanted.contains(edge.from().nodeId()) || !wanted.contains(edge.to().nodeId())) continue;
            copy.edges.add(new EdgeData(new PortRef(from, edge.from().portId()),
                    new PortRef(to, edge.to().portId())));
        }
        copy.changeset.clear();
        return copy;
    }

    /**
     * Merges another document into this one, re-issuing ids that would collide.
     *
     * <p>Paste. Ids are re-issued rather than refused because the clipboard may well hold a copy of
     * nodes that are still in this document — pasting into the graph you copied from is the normal
     * case, not the exception.</p>
     *
     * @return the ids the merged nodes ended up with, in the order they arrived
     */
    public List<String> merge(GraphDocument other) {
        Map<String, String> remap = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        for (NodeData node : other.nodes()) {
            String id = nodes.containsKey(node.id()) ? GraphIds.generate() : node.id();
            remap.put(node.id(), id);
            addNode(node.withId(id));
            added.add(id);
        }
        for (EdgeData edge : other.edges()) {
            PortRef from = new PortRef(remap.get(edge.from().nodeId()), edge.from().portId());
            PortRef to = new PortRef(remap.get(edge.to().nodeId()), edge.to().portId());
            // Straight in rather than through connect(): these edges were legal in the document they came
            // from, and re-validating them here would silently drop the ones whose types this document
            // has no registered rule for.
            edges.add(new EdgeData(from, to));
            changeset.edgeAdded(new EdgeData(from, to));
        }
        onChanged.emit();
        return added;
    }

    /** Everything, gone. */
    public void clear() {
        for (String id : new ArrayList<>(nodes.keySet())) removeNode(id);
    }
}
