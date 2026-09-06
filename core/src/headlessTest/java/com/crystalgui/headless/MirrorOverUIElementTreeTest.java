package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.mirror.ClientTreeMirror;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.net.mirror.ServerTreeMirror;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The mirror over the NEW engine — the second real tree it has run over, after
 * {@code MirrorIsEngineAgnosticTest}'s twelve-line fixture proved it could.
 *
 * <p>This is the milestone's networking claim in full: a {@link UIElementTreeSource} and a
 * {@link UIElementMirror}, and the same {@code ServerTreeMirror}/{@code ClientTreeMirror} that serve the
 * old engine carry a node tree across with its identity, attributes and structure intact — and
 * without ever describing a shadow tree.</p>
 */
public class MirrorOverUIElementTreeTest {

    private final UIElementMirror<Object> nodes = new UIElementMirror<>(PlainOps.INSTANCE);

    private UIDocument serverDocument;
    private UIElementTreeSource serverTree;
    private ServerTreeMirror<UIElement, Object> server;
    private UIElement clientRoot;
    private UIElementTreeSource clientTree;
    private ClientTreeMirror<UIElement, Object> client;

    private UIElement serverRootWith(String... ids) {
        serverDocument = new UIDocument();
        for (String id : ids) serverDocument.append(new UIElement().setId(id));
        serverTree = new UIElementTreeSource(serverDocument);
        return serverDocument;
    }

    private void open() {
        server = new ServerTreeMirror<>(serverTree, nodes, PlainOps.INSTANCE);
        int count = server.describeAndNumber();
        serverTree.observe(server);
        clientRoot = nodes.decode(nodes.describe(serverDocument));
        clientTree = new UIElementTreeSource(clientRoot);
        client = new ClientTreeMirror<>(clientTree, nodes, PlainOps.INSTANCE);
        assertEquals("both sides number the same pristine description alike",
                count, client.number(clientRoot, 0));
    }

    private void pumpStructure() {
        StateMap<Object> ops = server.drainStructure();
        if (ops != null) client.applyStructure(ops);
    }

    private void pumpState() {
        Map<UIElement, StateMap<Object>> entries = server.drainState();
        if (entries != null) client.applyState(server.pack(entries.values()), null);
    }

    private static UIElement childById(UIElement root, String id) {
        for (UIElement child : root.children()) if (child.id().equals(id)) return child;
        throw new AssertionError("no child " + id + " under " + root);
    }

    @Test
    public void aNodeTreeMirrorsPerfectly() {
        UIElement root = serverRootWith("first", "second");
        childById(root, "first").addClass("primary").set(Attribute.ENABLED, false);
        childById(root, "second").append(new UIElement().setId("grandchild"));
        open();

        assertEquals("crystalgui:document", clientRoot.name().toString());
        assertEquals(2, clientRoot.children().size());
        UIElement first = childById(clientRoot, "first");
        assertTrue(first.hasClass("primary"));
        assertFalse("a carried attribute travels", first.get(Attribute.ENABLED));
        assertEquals("grandchild", childById(clientRoot, "second").children().get(0).id());
    }

    @Test
    public void anInsertKeepsEverySiblingInstance() {
        UIElement root = serverRootWith("first", "second");
        open();
        UIElement firstBefore = childById(clientRoot, "first");
        UIElement secondBefore = childById(clientRoot, "second");

        root.insertAt(0, new UIElement().setId("inserted"));
        pumpStructure();

        assertEquals(List.of("inserted", "first", "second"),
                clientRoot.children().stream().map(UIElement::id).toList());
        assertSame("the existing nodes must survive a sibling insert", firstBefore, clientRoot.children().get(1));
        assertSame(secondBefore, clientRoot.children().get(2));
    }

    @Test
    public void aRemovalTakesTheSubtreeWithIt() {
        UIElement root = serverRootWith("keep", "gone");
        childById(root, "gone").append(new UIElement().setId("under"));
        open();
        assertEquals(2, clientRoot.children().size());

        root.remove(childById(root, "gone"));
        pumpStructure();

        assertEquals(List.of("keep"), clientRoot.children().stream().map(UIElement::id).toList());
        assertNull(clientTree.byId(serverTree.peekId(root) + 2));
    }

    @Test
    public void aReparentArrivesAsAMoveAndKeepsTheInstance() {
        UIElement root = serverRootWith("from", "to");
        UIElement moving = new UIElement().setId("moving");
        childById(root, "from").append(moving);
        open();
        UIElement clientMoving = childById(childById(clientRoot, "from"), "moving");

        childById(root, "to").append(moving);
        pumpStructure();

        assertTrue(childById(clientRoot, "from").children().isEmpty());
        assertSame("the same instance, moved", clientMoving, childById(childById(clientRoot, "to"), "moving"));
    }

    @Test
    public void anAttributeChangeArrivesAsAnAttribute() {
        UIElement root = serverRootWith("node");
        open();
        UIElement clientNode = childById(clientRoot, "node");

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
        UIElement root = serverRootWith("host");
        UIElement host = childById(root, "host");
        host.attachShadow().append(new UIElement().setId("part"));
        host.append(new UIElement().setId("content"));
        open();

        UIElement clientHost = childById(clientRoot, "host");
        assertNull("the far side's registered class rebuilds its own parts", clientHost.shadowRoot());
        assertEquals("the light content travels", List.of("content"),
                clientHost.children().stream().map(UIElement::id).toList());

        host.shadowRoot().append(new UIElement().setId("more-scaffolding"));
        assertNull("and a change inside the shadow tree produces no traffic", server.drainStructure());
    }

    @Test
    public void aLateViewerIsToldTheIds() {
        UIElement root = serverRootWith("a", "b");
        open();
        root.insertAt(0, new UIElement().setId("late"));
        pumpStructure();

        UIElement lateRoot = nodes.decodeLive(nodes.describeLive(root, serverTree::idOf), (node, id) -> { });
        assertEquals(List.of("late", "a", "b"), lateRoot.children().stream().map(UIElement::id).toList());
    }
}
