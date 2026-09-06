package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.mirror.ClientTreeMirror;
import com.crystalgui.net.mirror.NodeMirror;
import com.crystalgui.net.mirror.ServerTreeMirror;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.TreeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * <b>The mirror is written against the seam, not against an engine.</b>
 *
 * <p>Proved the only way it can be: by mirroring a tree that is <em>not</em> a {@code UIElement} tree.
 * The node below is a twelve-line class with a name, a label and children, and the mirror moves it
 * across a wire without either half of it knowing what it is.</p>
 *
 * <h3>Why this test exists</h3>
 *
 * <p>The plan's §0 turns on one sentence — <i>"the mirror is written once; the engine swap underneath
 * it is a port of the seam's implementation, not of the mirror"</i> — and for one milestone that was
 * <b>false while every test passed</b>. The seam was generic, and the mirror was written inside
 * {@code ServerUiSession implements TreeObserver<UIElement>} holding a concrete
 * {@code ElementTreeSource}. Nothing failed, because there was only ever one tree to mirror; the cost
 * would have arrived at M5 as a rewrite of op recording, coalescing, id allocation and the integrity
 * check — the exact logic that produced three silent defects the week it was written.</p>
 *
 * <p>So this is the guard against re-coupling. A change that reaches for {@code UIElement} inside the
 * mirror does not compile against this fixture, which is a much earlier and louder failure than
 * discovering it when the second engine lands.</p>
 */
public class MirrorIsEngineAgnosticTest {

    // ── A tree that has nothing to do with the UI engine ─────────────────────

    /** A node of some other tree entirely. It knows nothing about ids, networks or widgets. */
    private static final class Node {
        final String kind;
        String label;
        boolean marked;
        @Nullable Node parent;
        final List<Node> children = new ArrayList<>();
        /** What a session has asked this node to report. Per node, like every tree. */
        final Set<String> reports = new LinkedHashSet<>();

        Node(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }
    }

    private static final class Tree implements TreeSource<Node> {
        private final Node root;
        private final Map<Node, Integer> ids = new IdentityHashMap<>();
        private final Map<Integer, Node> byId = new HashMap<>();
        private int next;
        @Nullable private TreeObserver<Node> observer;

        Tree(Node root) {
            this.root = root;
        }

        // Mutation the FIXTURE does, reporting it the way a real engine would.
        void add(Node parent, Node child, int index) {
            child.parent = parent;
            parent.children.add(index, child);
            if (observer != null) observer.inserted(child, parent, index);
        }

        void remove(Node parent, Node child) {
            parent.children.remove(child);
            child.parent = null;
            if (observer != null) observer.removed(child, parent);
        }

        void relabel(Node node, String label) {
            node.label = label;
            if (observer != null) observer.stateChanged(node);
        }

        void mark(Node node) {
            node.marked = true;
            if (observer != null) observer.attributeChanged(node);
        }

        @Override public int idOf(Node node) {
            return ids.computeIfAbsent(node, n -> {
                int id = next++;
                byId.put(id, n);
                return id;
            });
        }

        @Override public int peekId(Node node) {
            Integer id = ids.get(node);
            return id == null ? -1 : id;
        }

        @Override @Nullable public Node byId(int id) {
            return byId.get(id);
        }

        @Override public int allocate(Node subtreeRoot) {
            int base = idOf(subtreeRoot);
            for (Node child : subtreeRoot.children) allocate(child);
            return base;
        }

        @Override public void release(Node subtreeRoot) {
            Integer id = ids.remove(subtreeRoot);
            if (id != null) byId.remove(id);
            for (Node child : subtreeRoot.children) release(child);
        }

        @Override public void assignAt(Node node, int id) {
            ids.put(node, id);
            byId.put(id, node);
            next = Math.max(next, id + 1);
        }

        @Override public void resetIds() {
            ids.clear();
            byId.clear();
            next = 0;
        }

        @Override public Node root() {
            return root;
        }

        @Override @Nullable public Node parentOf(Node node) {
            return node.parent;
        }

        @Override public List<Node> childrenOf(Node node) {
            return node.children;
        }

        @Override public boolean contains(Node node) {
            for (Node at = node; at != null; at = at.parent) {
                if (at == root) return true;
            }
            return false;
        }

        @Override public NodeContract contractOf(Node node) {
            return NodeContract.INERT;
        }

        @Override public void observe(@Nullable TreeObserver<Node> observer) {
            this.observer = observer;
        }

        @Override @Nullable public TreeObserver<Node> observer() {
            return observer;
        }

        @Override public void close() {
            observer = null;
        }
    }

    /** How a {@link Node} is described. The whole of what the mirror needs to know about this tree. */
    private static final class Nodes implements NodeMirror<Node, Object> {
        private final DynamicOps<Object> ops = PlainOps.INSTANCE;

        @Override public Object describe(Node node) {
            return write(node, null);
        }

        @Override public TreeSource<Node> sourceOver(Node root) {
            return new Tree(root);
        }

        @Override public Set<String> reportedEventsOf(Node node) {
            return node.reports;
        }

        @Override public void addReportedEvent(Node node, String kind) {
            node.reports.add(kind);
        }

        @Override public Object describeLive(Node node, ToIntFunction<Node> idOf) {
            return write(node, idOf);
        }

        private Object write(Node node, @Nullable ToIntFunction<Node> idOf) {
            Map<Object, Object> fields = new LinkedHashMap<>();
            fields.put(ops.createString("kind"), ops.createString(node.kind));
            fields.put(ops.createString("label"), ops.createString(node.label));
            fields.put(ops.createString("marked"), ops.createBoolean(node.marked));
            if (idOf != null) {
                fields.put(ops.createString("nid"), ops.createNumber(idOf.applyAsInt(node)));
            }
            List<Object> children = new ArrayList<>();
            for (Node child : node.children) children.add(write(child, idOf));
            fields.put(ops.createString("children"), ops.createList(children));
            return ops.createMap(fields);
        }

        @Override public Node decode(Object described) {
            return read(described, null);
        }

        @Override public Node decodeLive(Object described, ObjIntConsumer<Node> idSink) {
            return read(described, idSink);
        }

        private Node read(Object described, @Nullable ObjIntConsumer<Node> idSink) {
            Map<Object, Object> fields = ops.getMapValue(described);
            Node node = new Node(str(fields, "kind"), str(fields, "label"));
            Object marked = fields.get(ops.createString("marked"));
            if (marked != null) node.marked = ops.getBooleanValue(marked);
            Object nid = fields.get(ops.createString("nid"));
            if (nid != null && idSink != null) {
                idSink.accept(node, ((Number) nid).intValue());
            }
            Object children = fields.get(ops.createString("children"));
            if (children != null) {
                for (Object child : ops.getListValue(children)) {
                    Node decoded = read(child, idSink);
                    decoded.parent = node;
                    node.children.add(decoded);
                }
            }
            return node;
        }

        private String str(Map<Object, Object> fields, String key) {
            return ops.getStringValue(fields.get(ops.createString(key)));
        }

        @Override public Object encodeState(Node node) {
            return ops.createMap(Map.of(ops.createString("label"), ops.createString(node.label)));
        }

        @Override public void applyState(Object value, Node node) {
            node.label = ops.getStringValue(ops.getMapValue(value).get(ops.createString("label")));
        }

        @Override public Object encodeAttributes(Node node) {
            return ops.createMap(Map.of(ops.createString("marked"), ops.createBoolean(node.marked)));
        }

        @Override public void applyAttributes(Object value, Node node) {
            node.marked = ops.getBooleanValue(ops.getMapValue(value).get(ops.createString("marked")));
        }

        @Override public Object encodeInlineStyle(Node node) {
            return ops.createMap(Map.of());   // this tree has no styling; the field still travels
        }

        @Override public void applyInlineStyle(Object value, Node node) {
            // nothing to do
        }

        @Override public void insertChild(Node parent, Node child, int index) {
            if (child.parent != null) child.parent.children.remove(child);
            child.parent = parent;
            int at = index < 0 || index > parent.children.size() ? parent.children.size() : index;
            parent.children.add(at, child);
        }

        @Override public void removeChild(Node parent, Node child) {
            parent.children.remove(child);
            child.parent = null;
        }
    }

    // ── The fixture ──────────────────────────────────────────────────────────

    private Tree serverTree;
    private ServerTreeMirror<Node, Object> server;
    private Tree clientTree;
    private ClientTreeMirror<Node, Object> client;
    private final Nodes nodes = new Nodes();

    /** Opens: number the server's tree, ship the description, number the client's the same way. */
    private void open() {
        Node serverRoot = serverTree.root();
        server = new ServerTreeMirror<>(serverTree, nodes, PlainOps.INSTANCE);
        int count = server.describeAndNumber();
        serverTree.observe(server);

        Node clientRoot = nodes.decode(nodes.describe(serverRoot));
        clientTree = new Tree(clientRoot);
        client = new ClientTreeMirror<>(clientTree, nodes, PlainOps.INSTANCE);
        assertEquals("both sides number the same pristine description alike",
                count, client.number(clientRoot, 0));
    }

    private void pumpStructure() {
        StateMap<Object> ops = server.drainStructure();
        if (ops != null) client.applyStructure(ops);
    }

    private void pumpState() {
        java.util.Map<Node, StateMap<Object>> entries = server.drainState();
        if (entries != null) client.applyState(server.pack(entries.values()), null);
    }

    private Node serverRootWith(String... labels) {
        Node root = new Node("root", "root");
        for (String label : labels) {
            Node child = new Node("leaf", label);
            child.parent = root;
            root.children.add(child);
        }
        serverTree = new Tree(root);
        return root;
    }

    // ── The assertions ───────────────────────────────────────────────────────

    @Test
    public void aTreeThatIsNotAUiElementTreeMirrorsPerfectly() {
        Node root = serverRootWith("first", "second");
        open();

        assertEquals(2, clientTree.root().children.size());
        assertEquals("first", clientTree.root().children.get(0).label);
        assertEquals("second", clientTree.root().children.get(1).label);
    }

    /**
     * The headline assertion, on a foreign tree: an insert keeps every sibling INSTANCE.
     *
     * <p>The same guarantee {@code MirrorIdentityTest} makes for a {@code UIElement} tree, made here by
     * machinery that has never heard of one.</p>
     */
    @Test
    public void anInsertKeepsEverySiblingInstance() {
        Node root = serverRootWith("first", "second");
        open();
        Node firstBefore = clientTree.root().children.get(0);
        Node secondBefore = clientTree.root().children.get(1);

        serverTree.add(root, new Node("leaf", "inserted"), 0);
        pumpStructure();

        assertEquals(3, clientTree.root().children.size());
        assertEquals("inserted", clientTree.root().children.get(0).label);
        assertSame("the existing nodes must survive a sibling insert",
                firstBefore, clientTree.root().children.get(1));
        assertSame(secondBefore, clientTree.root().children.get(2));
    }

    @Test
    public void aRemoveTakesOnlyWhatItNames() {
        Node root = serverRootWith("first", "second");
        open();
        Node secondBefore = clientTree.root().children.get(1);

        serverTree.remove(root, root.children.get(0));
        pumpStructure();

        assertEquals(1, clientTree.root().children.size());
        assertSame(secondBefore, clientTree.root().children.get(0));
    }

    @Test
    public void stateAndAttributesTravel() {
        Node root = serverRootWith("first");
        open();
        assertEquals("first", clientTree.root().children.get(0).label);

        serverTree.relabel(root.children.get(0), "renamed");
        serverTree.mark(root.children.get(0));
        pumpState();

        Node mirrored = clientTree.root().children.get(0);
        assertEquals("renamed", mirrored.label);
        assertTrue("an attribute change must travel too", mirrored.marked);
    }

    /** The coalescing rule, which is about op recording rather than about any engine. */
    @Test
    public void aSubtreeAddedAndRemovedInOneTickProducesNoOps() {
        Node root = serverRootWith("first");
        open();

        Node fleeting = new Node("leaf", "gone");
        serverTree.add(root, fleeting, 0);
        serverTree.remove(root, fleeting);

        assertNull("nothing happened as far as the far side is concerned", server.drainStructure());
    }

    @Test
    public void anIdleTreeDrainsNothing() {
        serverRootWith("first");
        open();
        assertNull(server.drainStructure());
        assertNull(server.drainState());
    }

    /** A reshaped tree serves ids, because a walk no longer reproduces the numbering. */
    @Test
    public void aLateViewerIsToldTheIdsAfterAReshape() {
        Node root = serverRootWith("first", "second");
        open();
        serverTree.add(root, new Node("leaf", "inserted"), 0);
        pumpStructure();

        Map<Node, Integer> carried = new LinkedHashMap<>();
        Node late = nodes.decodeLive(nodes.describeLive(root, serverTree::idOf), carried::put);
        assertNotNull(late);
        assertEquals("every described node carries its id", 4, carried.size());

        Tree lateTree = new Tree(late);
        ClientTreeMirror<Node, Object> lateMirror =
                new ClientTreeMirror<>(lateTree, nodes, PlainOps.INSTANCE);
        for (Map.Entry<Node, Integer> entry : carried.entrySet()) {
            lateTree.assignAt(entry.getKey(), entry.getValue());
        }
        lateMirror.number(late, carried.size());

        // The point of the exercise: the newcomer resolves the SERVER's ids, which it could not have
        // derived, because the insert renumbered nothing.
        for (Node node : root.children) {
            Node here = lateTree.byId(serverTree.idOf(node));
            assertNotNull("id " + serverTree.idOf(node) + " must resolve on the late viewer", here);
            assertEquals(node.label, here.label);
        }
    }
}
