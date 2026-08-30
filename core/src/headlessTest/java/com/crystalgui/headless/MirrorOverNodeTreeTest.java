package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.mirror.ClientTreeMirror;
import com.crystalgui.net.mirror.DomNodeMirror;
import com.crystalgui.net.mirror.ServerTreeMirror;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Document;
import com.crystalgui.ui.dom.Node;
import com.crystalgui.ui.dom.NodeTreeSource;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The mirror over the NEW engine — the second real tree it has run over, after
 * {@code MirrorIsEngineAgnosticTest}'s twelve-line fixture proved it could.
 *
 * <p>This is the milestone's networking claim in full: a {@link NodeTreeSource} and a
 * {@link DomNodeMirror}, and the same {@code ServerTreeMirror}/{@code ClientTreeMirror} that serve the
 * old engine carry a node tree across with its identity, attributes and structure intact — and
 * without ever describing a shadow tree.</p>
 */
public class MirrorOverNodeTreeTest {

    private final DomNodeMirror<Object> nodes = new DomNodeMirror<>(PlainOps.INSTANCE);

    private Document serverDocument;
    private NodeTreeSource serverTree;
    private ServerTreeMirror<Node, Object> server;
    private Node clientRoot;
    private NodeTreeSource clientTree;
    private ClientTreeMirror<Node, Object> client;

    private Node serverRootWith(String... ids) {
        serverDocument = new Document();
        for (String id : ids) serverDocument.append(new Node().setId(id));
        serverTree = new NodeTreeSource(serverDocument);
        return serverDocument;
    }

    private void open() {
        server = new ServerTreeMirror<>(serverTree, nodes, PlainOps.INSTANCE);
        int count = server.describeAndNumber();
        serverTree.observe(server);
        clientRoot = nodes.decode(nodes.describe(serverDocument));
        clientTree = new NodeTreeSource(clientRoot);
        client = new ClientTreeMirror<>(clientTree, nodes, PlainOps.INSTANCE);
        assertEquals("both sides number the same pristine description alike",
                count, client.number(clientRoot, 0));
    }

    private void pumpStructure() {
        StateMap<Object> ops = server.drainStructure();
        if (ops != null) client.applyStructure(ops);
    }

    private void pumpState() {
        Map<Node, StateMap<Object>> entries = server.drainState();
        if (entries != null) client.applyState(server.pack(entries.values()), null);
    }

    private static Node childById(Node root, String id) {
        for (Node child : root.children()) if (child.id().equals(id)) return child;
        throw new AssertionError("no child " + id + " under " + root);
    }

    @Test
    public void aNodeTreeMirrorsPerfectly() {
        Node root = serverRootWith("first", "second");
        childById(root, "first").addClass("primary").set(Attribute.ENABLED, false);
        childById(root, "second").append(new Node().setId("grandchild"));
        open();

        assertEquals("crystalgui:document", clientRoot.name().toString());
        assertEquals(2, clientRoot.children().size());
        Node first = childById(clientRoot, "first");
        assertTrue(first.hasClass("primary"));
        assertFalse("a carried attribute travels", first.get(Attribute.ENABLED));
        assertEquals("grandchild", childById(clientRoot, "second").children().get(0).id());
    }

    @Test
    public void anInsertKeepsEverySiblingInstance() {
        Node root = serverRootWith("first", "second");
        open();
        Node firstBefore = childById(clientRoot, "first");
        Node secondBefore = childById(clientRoot, "second");

        root.insertAt(0, new Node().setId("inserted"));
        pumpStructure();

        assertEquals(List.of("inserted", "first", "second"),
                clientRoot.children().stream().map(Node::id).toList());
        assertSame("the existing nodes must survive a sibling insert", firstBefore, clientRoot.children().get(1));
        assertSame(secondBefore, clientRoot.children().get(2));
    }

    @Test
    public void aRemovalTakesTheSubtreeWithIt() {
        Node root = serverRootWith("keep", "gone");
        childById(root, "gone").append(new Node().setId("under"));
        open();
        assertEquals(2, clientRoot.children().size());

        root.remove(childById(root, "gone"));
        pumpStructure();

        assertEquals(List.of("keep"), clientRoot.children().stream().map(Node::id).toList());
        assertNull(clientTree.byId(serverTree.peekId(root) + 2));
    }

    @Test
    public void aReparentArrivesAsAMoveAndKeepsTheInstance() {
        Node root = serverRootWith("from", "to");
        Node moving = new Node().setId("moving");
        childById(root, "from").append(moving);
        open();
        Node clientMoving = childById(childById(clientRoot, "from"), "moving");

        childById(root, "to").append(moving);
        pumpStructure();

        assertTrue(childById(clientRoot, "from").children().isEmpty());
        assertSame("the same instance, moved", clientMoving, childById(childById(clientRoot, "to"), "moving"));
    }

    @Test
    public void anAttributeChangeArrivesAsAnAttribute() {
        Node root = serverRootWith("node");
        open();
        Node clientNode = childById(clientRoot, "node");

        childById(root, "node").addClass("lit").set(Attribute.INERT, true);
        pumpStructure();
        pumpState();

        assertTrue(clientNode.hasClass("lit"));
        assertTrue(clientNode.get(Attribute.INERT));

        childById(root, "node").removeClass("lit").set(Attribute.INERT, false);
        pumpState();
        assertFalse("a withdrawn class is withdrawn", clientNode.hasClass("lit"));
        assertFalse("and an attribute back at its initial is back", clientNode.get(Attribute.INERT));
    }

    @Test
    public void aShadowTreeNeverTravels() {
        Node root = serverRootWith("host");
        Node host = childById(root, "host");
        host.attachShadow().append(new Node().setId("part"));
        host.append(new Node().setId("content"));
        open();

        Node clientHost = childById(clientRoot, "host");
        assertNull("the far side's registered class rebuilds its own parts", clientHost.shadowRoot());
        assertEquals("the light content travels", List.of("content"),
                clientHost.children().stream().map(Node::id).toList());

        host.shadowRoot().append(new Node().setId("more-scaffolding"));
        assertNull("and a change inside the shadow tree produces no traffic", server.drainStructure());
    }

    @Test
    public void aLateViewerIsToldTheIds() {
        Node root = serverRootWith("a", "b");
        open();
        root.insertAt(0, new Node().setId("late"));
        pumpStructure();

        Node lateRoot = nodes.decodeLive(nodes.describeLive(root, serverTree::idOf), (node, id) -> { });
        assertEquals(List.of("late", "a", "b"), lateRoot.children().stream().map(Node::id).toList());
    }
}
