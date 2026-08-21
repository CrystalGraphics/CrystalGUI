package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ClientUiSessions;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UiWindowMux;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Slider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 5 <b>5.7</b> — two windows on one connection.
 *
 * <h3>The limit, and why it was the right failure</h3>
 *
 * <p>{@code MessageRouter} keys handlers by method name and refuses a duplicate, so a second
 * {@link ServerUiSession} on one connection threw <i>"a request handler for 'ui/description' is already
 * registered"</i>. That was correct rather than a bug: silently keeping the last registration means
 * whichever subsystem initialised second wins, which is unfindable. Lifting it means dispatching on the
 * <b>window id as well as the method</b> — {@link UiWindowMux}.</p>
 *
 * <h3>What these tests are actually for</h3>
 *
 * <p>Not that two windows open — that is the easy half and would pass against a router that simply
 * replaced its handler. The load-bearing claims are that the two windows stay <b>separated</b> (a delta,
 * an event and an RPC each reach exactly one), and that a closed window <b>gives its id back</b> without
 * leaving the other one broken. Both are the failure modes a name-keyed router produces, and both look
 * like the feature working right up until the moment they do not.</p>
 */
public class TwoWindowsOnOneConnectionTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;

    private UIElement rootOne;
    private UIElement rootTwo;
    private Slider sliderOne;
    private Slider sliderTwo;
    private Button buttonOne;
    private Button buttonTwo;

    private ServerUiSession<Object> windowOne;
    private ServerUiSession<Object> windowTwo;

    private ClientUiSessions<Object> client;
    private final List<ClientUiSession<Object>> created = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ElementRegistry.bootstrapBuiltins();

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        rootOne = new UIElement();
        buttonOne = new Button("one");
        sliderOne = new Slider();
        sliderOne.setRange(0f, 10f);
        rootOne.addChild(buttonOne);
        rootOne.addChild(sliderOne);

        rootTwo = new UIElement();
        buttonTwo = new Button("two");
        sliderTwo = new Slider();
        sliderTwo.setRange(0f, 10f);
        rootTwo.addChild(buttonTwo);
        rootTwo.addChild(sliderTwo);

        // THE LINE THAT USED TO THROW.
        windowOne = new ServerUiSession<>(1, rootOne, serverSide);
        windowTwo = new ServerUiSession<>(2, rootTwo, serverSide);

        client = ClientUiSessions.forConnection(clientSide);
        client.onSession(created::add);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
            windowOne.tick();
            windowTwo.tick();
        }
    }

    private ClientUiSession<Object> clientWindow(int id) {
        ClientUiSession<Object> session = client.session(id);
        assertNotNull("no client session for window " + id, session);
        return session;
    }

    // ── The claim ───────────────────────────────────────────────────────────────────────────────

    /** Both windows open, and each client session holds its own tree. */
    @Test
    public void twoWindowsOpenOnOneConnection() {
        windowOne.open();
        windowTwo.open();
        settle();

        assertEquals("two client sessions", 2, client.sessionCount());
        assertEquals("and onSession heard about both", 2, created.size());

        UIElement one = clientWindow(1).root();
        UIElement two = clientWindow(2).root();
        assertNotNull("window 1 must have a tree", one);
        assertNotNull("window 2 must have a tree", two);
        assertNotSame("and they must not be the same tree", one, two);

        assertEquals("one", ((Button) one.getChildren().get(0)).getText());
        assertEquals("two", ((Button) two.getChildren().get(0)).getText());
    }

    /**
     * A state delta reaches exactly one window.
     *
     * <p>The assertion that separates a real fix from a router that merely stopped complaining: with one
     * handler per method name, whichever session registered last would receive <em>both</em> windows'
     * deltas and drop the other's on its own {@code != windowId} check — so window 1 would go dead and
     * window 2 would look perfect.</p>
     */
    @Test
    public void aStateDeltaReachesOnlyItsOwnWindow() {
        windowOne.open();
        windowTwo.open();
        settle();

        sliderOne.setValue(7f);
        settle();

        assertEquals("window 1 moved", 7f,
                ((Slider) clientWindow(1).root().getChildren().get(1)).getValue(), 0.001f);
        assertEquals("window 2 must not have", 0f,
                ((Slider) clientWindow(2).root().getChildren().get(1)).getValue(), 0.001f);

        sliderTwo.setValue(3f);
        settle();

        assertEquals("window 2 moved", 3f,
                ((Slider) clientWindow(2).root().getChildren().get(1)).getValue(), 0.001f);
        assertEquals("and window 1 stayed where it was", 7f,
                ((Slider) clientWindow(1).root().getChildren().get(1)).getValue(), 0.001f);
    }

    /** An event goes back to the session that described the element, not to whichever registered last. */
    @Test
    public void anEventReachesOnlyItsOwnSession() {
        AtomicInteger pressesOne = new AtomicInteger();
        AtomicInteger pressesTwo = new AtomicInteger();
        windowOne.onActivate(buttonOne, ctx -> pressesOne.incrementAndGet());
        windowTwo.onActivate(buttonTwo, ctx -> pressesTwo.incrementAndGet());
        windowOne.open();
        windowTwo.open();
        settle();

        ((Button) clientWindow(2).root().getChildren().get(0)).onPressed.emit();
        settle();

        assertEquals("window 2's button", 1, pressesTwo.get());
        assertEquals("window 1 heard nothing", 0, pressesOne.get());
    }

    /**
     * The same method name on two windows is not a conflict.
     *
     * <p>Session-scoped RPC used to collide exactly as {@code ui/description} did, and the collision was
     * worse: an application registering {@code app/ping} on its second window threw from inside the
     * router with a message about a method it had every right to name twice.</p>
     */
    @Test
    public void theSameMethodNameOnTwoWindowsIsNotAConflict() {
        windowOne.open();
        windowTwo.open();
        settle();

        clientWindow(1).onCall("app/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("from", "one");
            respond.ok(out);
        });
        clientWindow(2).onCall("app/ping", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("from", "two");
            respond.ok(out);
        });

        AtomicReference<String> answeredOne = new AtomicReference<>();
        AtomicReference<String> answeredTwo = new AtomicReference<>();
        windowOne.call("app/ping", null, r -> answeredOne.set(r.getString("from", "")), null);
        windowTwo.call("app/ping", null, r -> answeredTwo.set(r.getString("from", "")), null);
        settle();

        assertEquals("one", answeredOne.get());
        assertEquals("two", answeredTwo.get());
    }

    /** Closing one window leaves the other serving. */
    @Test
    public void closingOneWindowLeavesTheOtherServing() {
        windowOne.open();
        windowTwo.open();
        settle();

        windowOne.close("done");
        settle();

        assertNull("window 1 is gone from the client", client.session(1));
        assertEquals("and only window 2 is left", 1, client.sessionCount());

        sliderTwo.setValue(5f);
        settle();
        assertEquals("window 2 still receives deltas", 5f,
                ((Slider) clientWindow(2).root().getChildren().get(1)).getValue(), 0.001f);
    }

    /**
     * A closed window gives its id back.
     *
     * <p>The half that is easy to leave out and impossible to notice: {@code release} on close is what
     * lets an id be reused. Without it, opening a window in the slot a closed one used throws
     * <i>"window 1 already serves 'ui/description'"</i> — for a window nobody is watching, on a
     * connection that has been up for hours. On a client that reopens the same editor, that is every
     * second open.</p>
     */
    @Test
    public void aWindowIdMayBeReusedAfterItCloses() {
        windowOne.open();
        windowTwo.open();
        settle();
        windowOne.close("done");
        settle();

        UIElement replacementRoot = new UIElement();
        Button replacementButton = new Button("reopened");
        replacementRoot.addChild(replacementButton);

        ServerUiSession<Object> replacement = new ServerUiSession<>(1, replacementRoot, serverSide);
        replacement.open();
        for (int i = 0; i < 24; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
            replacement.tick();
            windowTwo.tick();
        }

        assertNotNull("window 1 must open again in the id its predecessor released",
                client.session(1));
        assertEquals("reopened",
                ((Button) clientWindow(1).root().getChildren().get(0)).getText());
        assertNotNull("and window 2 is untouched", client.session(2));
    }

    /**
     * Removing a viewer releases its slot, so the same viewer may be added back.
     *
     * <p>A reconnect is exactly this shape, and the leak is silent: the viewer is gone from the fan-out
     * list while its {@code (method, window)} pairs are still claimed, so re-adding throws for a window
     * that is genuinely open and genuinely wants that viewer.</p>
     */
    @Test
    public void aRemovedViewerMayBeAddedBack() {
        windowOne.open();
        settle();

        assertTrue(windowOne.removeViewer(serverSide));
        windowOne.addViewer(serverSide);
        settle();

        assertNotNull("the re-added viewer still has the window", client.session(1));
    }

    /**
     * A message for a window nobody serves is <b>refused</b>, never delivered to the only one left.
     *
     * <p>Falling back to "the one window" is correct while there is one and silently wrong with two —
     * which is to say it breaks exactly when the feature starts being used. And a request must be
     * answered rather than dropped, or the caller waits out its deadline and reports a timeout: a slow
     * peer and a closed window are different problems and must not look alike.</p>
     */
    @Test
    public void aRequestForAnUnknownWindowIsRefused() {
        windowOne.open();
        settle();

        AtomicReference<String> error = new AtomicReference<>();
        StateMap<Object> ask = new StateMap<>(PlainOps.INSTANCE);
        ask.putInt("w", 99);
        ask.putString("hash", "whatever");
        clientSide.call("ui/description", ask, r -> fail("a window that does not exist answered"),
                error::set);
        settle();

        assertNotNull("the caller must be told, not left to time out", error.get());
        assertTrue("and told which window: " + error.get(), error.get().contains("99"));
    }

    /**
     * The single-window shape is unchanged.
     *
     * <p>A plain {@link ClientUiSession} riding a connection still owns {@code ui/openWindow} and
     * registers straight on the router — no mux, no lookup. This is the common case and the one every
     * existing test and the 1.7.10 client take, so it is asserted here rather than assumed.</p>
     */
    @Test
    public void oneWindowStillNeedsNoDemultiplexer() {
        InMemoryTransport<Object>[] solo = InMemoryTransport.pair();
        ProtocolConnection<Object> soloServer =
                Protocols.open(solo[0], PlainOps.INSTANCE, () -> { }, "bob");
        ProtocolConnection<Object> soloClient =
                Protocols.open(solo[1], PlainOps.INSTANCE, () -> { }, null);

        UIElement root = new UIElement();
        root.addChild(new Button("solo"));
        ServerUiSession<Object> server = new ServerUiSession<>(7, root, soloServer);
        ClientUiSession<Object> view = new ClientUiSession<>(soloClient);

        server.open();
        for (int i = 0; i < 24; i++) {
            solo[0].deliver();
            solo[1].deliver();
            soloServer.tick();
            soloClient.tick();
            server.tick();
        }

        assertNotNull(view.root());
        assertEquals("solo", ((Button) view.root().getChildren().get(0)).getText());
        assertEquals(7, view.windowId());
    }

    /**
     * A plain session and the host are mutually exclusive on one connection, and it says so.
     *
     * <p>Both want {@code ui/openWindow}, which is the one message that cannot be window-scoped. The
     * refusal comes from {@code MessageRouter}'s own duplicate check rather than a second check here, so
     * there is one statement of the rule.</p>
     */
    @Test
    public void aPlainSessionAndTheHostCannotShareAConnection() {
        try {
            new ClientUiSession<>(clientSide);
            fail("a plain session must not be able to join a connection the host already owns");
        } catch (IllegalStateException expected) {
            assertTrue("the message must name the method: " + expected.getMessage(),
                    expected.getMessage().contains("ui/openWindow"));
        }
    }
}
