package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.mirror.ClientTreeMirror;
import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.net.mirror.ServerTreeMirror;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeTreeSource;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The mirror over the NEW engine — the second real tree it has run over, after
 * {@code MirrorIsEngineAgnosticTest}'s twelve-line fixture proved it could.
 *
 * <p>This is the milestone's networking claim in full: a {@link UINodeTreeSource} and a
 * {@link UINodeMirror}, and the same {@code ServerTreeMirror}/{@code ClientTreeMirror} that serve the
 * old engine carry a node tree across with its identity, attributes and structure intact — and
 * without ever describing a shadow tree.</p>
 */
public class MirrorOverUINodeTreeTest {

    private final UINodeMirror<Object> nodes = new UINodeMirror<>(PlainOps.INSTANCE);

    private UIDocument serverDocument;
    private UINodeTreeSource serverTree;
    private ServerTreeMirror<UINode, Object> server;
    private UINode clientRoot;
    private UINodeTreeSource clientTree;
    private ClientTreeMirror<UINode, Object> client;

    private UINode serverRootWith(String... ids) {
        serverDocument = new UIDocument();
        for (String id : ids) serverDocument.append(new UINode().setId(id));
        serverTree = new UINodeTreeSource(serverDocument);
        return serverDocument;
    }

    private void open() {
        server = new ServerTreeMirror<>(serverTree, nodes, PlainOps.INSTANCE);
        int count = server.describeAndNumber();
        serverTree.observe(server);
        clientRoot = nodes.decode(nodes.describe(serverDocument));
        clientTree = new UINodeTreeSource(clientRoot);
        client = new ClientTreeMirror<>(clientTree, nodes, PlainOps.INSTANCE);
        assertEquals("both sides number the same pristine description alike",
                count, client.number(clientRoot, 0));
    }

    private void pumpStructure() {
        StateMap<Object> ops = server.drainStructure();
        if (ops != null) client.applyStructure(ops);
    }

    private void pumpState() {
        Map<UINode, StateMap<Object>> entries = server.drainState();
        if (entries != null) client.applyState(server.pack(entries.values()), null);
    }

    private static UINode childById(UINode root, String id) {
        for (UINode child : root.children()) if (child.id().equals(id)) return child;
        throw new AssertionError("no child " + id + " under " + root);
    }

    @Test
    public void aNodeTreeMirrorsPerfectly() {
        UINode root = serverRootWith("first", "second");
        childById(root, "first").addClass("primary").set(Attribute.ENABLED, false);
        childById(root, "second").append(new UINode().setId("grandchild"));
        open();

        assertEquals("crystalgui:document", clientRoot.name().toString());
        assertEquals(2, clientRoot.children().size());
        UINode first = childById(clientRoot, "first");
        assertTrue(first.hasClass("primary"));
        assertFalse("a carried attribute travels", first.get(Attribute.ENABLED));
        assertEquals("grandchild", childById(clientRoot, "second").children().get(0).id());
    }

    @Test
    public void anInsertKeepsEverySiblingInstance() {
        UINode root = serverRootWith("first", "second");
        open();
        UINode firstBefore = childById(clientRoot, "first");
        UINode secondBefore = childById(clientRoot, "second");

        root.insertAt(0, new UINode().setId("inserted"));
        pumpStructure();

        assertEquals(List.of("inserted", "first", "second"),
                clientRoot.children().stream().map(UINode::id).toList());
        assertSame("the existing nodes must survive a sibling insert", firstBefore, clientRoot.children().get(1));
        assertSame(secondBefore, clientRoot.children().get(2));
    }

    @Test
    public void aRemovalTakesTheSubtreeWithIt() {
        UINode root = serverRootWith("keep", "gone");
        childById(root, "gone").append(new UINode().setId("under"));
        open();
        assertEquals(2, clientRoot.children().size());

        root.remove(childById(root, "gone"));
        pumpStructure();

        assertEquals(List.of("keep"), clientRoot.children().stream().map(UINode::id).toList());
        assertNull(clientTree.byId(serverTree.peekId(root) + 2));
    }

    @Test
    public void aReparentArrivesAsAMoveAndKeepsTheInstance() {
        UINode root = serverRootWith("from", "to");
        UINode moving = new UINode().setId("moving");
        childById(root, "from").append(moving);
        open();
        UINode clientMoving = childById(childById(clientRoot, "from"), "moving");

        childById(root, "to").append(moving);
        pumpStructure();

        assertTrue(childById(clientRoot, "from").children().isEmpty());
        assertSame("the same instance, moved", clientMoving, childById(childById(clientRoot, "to"), "moving"));
    }

    @Test
    public void anAttributeChangeArrivesAsAnAttribute() {
        UINode root = serverRootWith("node");
        open();
        UINode clientNode = childById(clientRoot, "node");

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
        UINode root = serverRootWith("host");
        UINode host = childById(root, "host");
        host.attachShadow().append(new UINode().setId("part"));
        host.append(new UINode().setId("content"));
        open();

        UINode clientHost = childById(clientRoot, "host");
        assertNull("the far side's registered class rebuilds its own parts", clientHost.shadowRoot());
        assertEquals("the light content travels", List.of("content"),
                clientHost.children().stream().map(UINode::id).toList());

        host.shadowRoot().append(new UINode().setId("more-scaffolding"));
        assertNull("and a change inside the shadow tree produces no traffic", server.drainStructure());
    }

    @Test
    public void aLateViewerIsToldTheIds() {
        UINode root = serverRootWith("a", "b");
        open();
        root.insertAt(0, new UINode().setId("late"));
        pumpStructure();

        UINode lateRoot = nodes.decodeLive(nodes.describeLive(root, serverTree::idOf), (node, id) -> { });
        assertEquals(List.of("late", "a", "b"), lateRoot.children().stream().map(UINode::id).toList());
    }
}
