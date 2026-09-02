package com.crystalgui.headless;

import com.crystalgui.ui.dom.UINodeTreeSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import org.junit.Before;
import org.junit.Test;

/**
 * <b>M2's acceptance</b> — identity is not position, and structure travels as an edit script.
 * {@code plan_ui_rewrite.md} M2, network audit Appendix A.
 *
 * <p>Every assertion here is <b>false on the pre-M2 code</b>, and each names a defect that was silent:
 * the server's own tree was always correct, nothing threw, and what went wrong was only visible as a
 * client that had quietly lost something.</p>
 */
public class MirrorIdentityTest {

    private UINode root;
    private ServerUiSession<UINode, Object> server;
    private ClientUiSession<UINode, Object> client;
    private InMemoryTransport<Object> serverLink;
    private InMemoryTransport<Object> clientLink;

    @Before
    public void setUp() {
        ElementRegistry.bootstrapBuiltins();
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverLink = pair[0];
        clientLink = pair[1];

        root = new UINode();
        root.append(new UIText("first"));
        root.append(new Button("press"));

        server = Sessions.serve(1, root, serverLink);
        client = Sessions.view(clientLink);
    }

    private void settle() {
        for (int i = 0; i < 12; i++) {
            clientLink.deliver();
            serverLink.deliver();
            client.tick();
            server.tick();
        }
    }

    private UINode clientChild(int index) {
        return client.root().children().get(index);
    }

    // ── The headline: a sibling insert does not destroy its siblings ─────────

    /**
     * <b>The assertion the whole milestone turns on.</b>
     *
     * <p>{@code ui/treeDelta} re-described the anchor's <em>whole child list</em>, so the client cleared
     * its children and decoded them again — every sibling came back a <b>different object</b>, losing
     * its listeners, anything local to it, and anything a nested panel was holding. It looked correct
     * on screen, which is why it survived: the tree drawn afterwards is the right tree, made of the
     * wrong objects.</p>
     */
    @Test
    public void addingARowKeepsEverySiblingInstance() {
        server.open();
        settle();
        assertNotNull(client.root());

        UINode firstBefore = clientChild(0);
        UINode buttonBefore = clientChild(1);

        root.insertAt(0, new UIText("inserted"));
        settle();

        assertEquals(3, client.root().children().size());
        assertSame("the existing text must be the SAME OBJECT after a sibling was inserted",
                firstBefore, clientChild(1));
        assertSame("and so must the button", buttonBefore, clientChild(2));
    }

    @Test
    public void anInsertGoesWhereTheServerPutIt() {
        server.open();
        settle();

        root.insertAt(1, new UIText("inserted"));
        settle();

        // Appending would be invisible in a size check and wrong on screen, which is why the op carries
        // an index at all.
        assertEquals("inserted", ((UIText) clientChild(1)).getText());
        assertEquals(3, client.root().children().size());
    }

    // ── A move is a move ────────────────────────────────────────────────────

    /**
     * A reparent within one tick arrives as ONE op and keeps the instance.
     *
     * <p>Told "removed, and here is an identical one", a receiver rebuilds the subtree — so a panel a
     * user had typed into came back empty. The distinction is the entire reason identity had to stop
     * being positional first: you cannot say "this one moved" without a name for "this one".</p>
     */
    @Test
    public void aReparentKeepsTheInstance() {
        UINode from = new UINode();
        UINode to = new UINode();
        UIText moving = new UIText("moving");
        from.append(moving);
        root.append(from);
        root.append(to);

        server.open();
        settle();

        UINode movingOnClient = clientChild(2).children().get(0);
        assertEquals("moving", ((UIText) movingOnClient).getText());

        to.append(moving);          // one tick: detach + attach of the same object
        settle();

        assertTrue("it left the old parent", clientChild(2).children().isEmpty());
        assertSame("and arrived at the new one as the SAME OBJECT",
                movingOnClient, clientChild(3).children().get(0));
    }

    // ── Ids are stable ──────────────────────────────────────────────────────

    /**
     * An event still reaches its handler after the tree in front of it has been reshaped.
     *
     * <p>Under positional ids, inserting a sibling renumbered everything after it — so the button the
     * client reported about was a different element than the one the server looked up, and the press
     * landed on the wrong handler or on none.</p>
     */
    @Test
    public void anEventLandsOnTheRightHandlerAfterAnInsert() {
        Button button = (Button) root.children().get(1);
        final int[] presses = { 0 };
        server.on(button, Button.ACTIVATE, ctx -> presses[0]++);

        server.open();
        settle();

        root.insertAt(0, new UIText("inserted"));
        settle();

        ((Button) clientChild(2)).onPressed.emit();
        settle();

        assertEquals("the press must reach the handler the server registered for THAT button",
                1, presses[0]);
    }

    // ── Identity and inline style travel ────────────────────────────────────

    /**
     * <b>Disabling a button after the window opened now reaches the far side.</b>
     *
     * <p>It never did. {@code onIdentityDirty} fired, the session collected the element into
     * {@code dirtyIdentity}, and the flush <em>cleared that set without encoding it</em> — so the
     * change was correct on the server, absent on the client, and produced no error anywhere. The same
     * hole swallowed every class change and every inline style write.</p>
     */
    @Test
    public void disablingAWidgetAfterOpenReachesTheClient() {
        Button button = (Button) root.children().get(1);
        server.open();
        settle();
        assertTrue(clientChild(1).isEnabled());

        button.setEnabled(false);
        settle();

        assertTrue("a disabled button must arrive disabled", !clientChild(1).isEnabled());
    }

    @Test
    public void aClassAddedAfterOpenTravels() {
        UINode text = root.children().get(0);
        server.open();
        settle();

        text.addClass("highlighted");
        settle();
        assertTrue("an added class must travel", clientChild(0).hasClass("highlighted"));

        text.removeClass("highlighted");
        settle();
        assertTrue("and a REMOVED one must too -- a delta carrying only what is present cannot say "
                        + "'this is gone' unless classes are replaced wholesale",
                !clientChild(0).hasClass("highlighted"));
    }

    // ── Constructor skew is now harmless ────────────────────────────────────

    /**
     * A composite's internal children are not numbered, so a client whose widget builds a different
     * number of them still routes everything.
     *
     * <p>The old numbering walked every child, internal ones included — coupling the id space to widget
     * constructors. A client whose {@code Button} carried one more internal label than the server's
     * mis-addressed every element after it, and the description could not reveal why, because internals
     * are never serialized. Asserted here as: a tree containing composites round-trips and its
     * described count is what both sides agree on.</p>
     */
    @Test
    public void internalChildrenAreNotNumbered() {
        server.open();
        settle();

        // A Button builds its parts in a SHADOW TREE (its label among them). If those were numbered,
        // the described count and the id space would disagree.
        assertEquals("only described elements are numbered", 3,
                new UINodeTreeSource(root).describedCount(root));
        UINode button = root.children().get(1);
        assertNotNull("the button really does have a shadow tree", button.shadowRoot());
        assertTrue("...with parts in it", !button.shadowRoot().children().isEmpty());
    }

    // ── Nothing is sent when nothing happens ────────────────────────────────

    @Test
    public void anIdleWindowIsSilent() {
        server.open();
        settle();
        serverLink.clearSent();

        server.tick();
        server.tick();
        settle();

        assertEquals("an idle window must send nothing at all", 0, serverLink.sent().size());
    }

    @Test
    public void aSubtreeInsertedAndRemovedInOneTickNeverReachesTheWire() {
        server.open();
        settle();
        serverLink.clearSent();

        UIText transient_ = new UIText("gone");
        root.append(transient_);
        root.remove(transient_);
        settle();

        assertEquals("nothing happened as far as the far side is concerned",
                0, serverLink.sent().size());
    }

    // ── Removal ─────────────────────────────────────────────────────────────

    @Test
    public void aRemovedSubtreeGoesAndItsSiblingsSurvive() {
        server.open();
        settle();
        UINode buttonBefore = clientChild(1);

        root.remove(root.children().get(0));
        settle();

        assertEquals(1, client.root().children().size());
        assertSame("removing one child must not rebuild the other", buttonBefore, clientChild(0));
    }

    /** An element the server re-adds later is a NEW insert with a fresh id — what the DOM does too. */
    @Test
    public void aReAddedElementInALaterTickIsANewInsert() {
        server.open();
        settle();

        UINode text = root.children().get(0);
        UINode clientTextBefore = clientChild(0);
        root.remove(text);
        settle();

        root.append(text);
        settle();

        assertEquals(2, client.root().children().size());
        assertNotSame("a re-add in a LATER tick is not a move -- the element genuinely left",
                clientTextBefore, clientChild(1));
    }
}
