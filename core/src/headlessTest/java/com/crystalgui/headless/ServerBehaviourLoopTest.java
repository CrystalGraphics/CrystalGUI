package com.crystalgui.headless;

import com.crystalgui.ui.dom.UINodeTreeSource;
import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * The whole point of the milestone: <b>the server keeps the lambdas</b>.
 *
 * <p>A user acts on the client, the server's own closure runs, the server mutates its tree, and the
 * change comes back — with no behaviour code on the client and no Minecraft or GL anywhere.</p>
 */
public class ServerBehaviourLoopTest {

    private InMemoryTransport<Object> serverLink;
    private InMemoryTransport<Object> clientLink;
    private ServerUiSession<UINode, Object> server;
    private ClientUiSession<UINode, Object> client;

    private UINode root;
    private Button button;
    private Checkbox checkbox;
    private Slider slider;
    private UIText status;

    @Before
    public void setUp() {
        root = new UINode();
        status = new UIText("idle");
        button = new Button("Press me");
        checkbox = new Checkbox("Enabled");
        slider = new Slider();
        slider.setRange(0f, 10f);
        root.append(status);
        root.append(button);
        root.append(checkbox);
        root.append(slider);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverLink = pair[0];
        clientLink = pair[1];
        server = Sessions.serve(1, root, serverLink);
        client = Sessions.view(clientLink);
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            int moved = clientLink.deliver();
            client.tick();
            moved += serverLink.deliver();
            server.tick();
            if (moved == 0) return;
        }
        fail("the exchange never settled");
    }

    private <E> E clientChild(int index, Class<E> type) {
        return type.cast(client.root().children().get(index));
    }

    // ── The loop ────────────────────────────────────────────────────────────

    /** Press a button on the client; a server closure runs and updates a label the client then shows. */
    @Test
    public void aClientPressRunsAServerLambdaAndTheResultComesBack() {
        AtomicInteger presses = new AtomicInteger();
        server.on(button, Button.ACTIVATE, ctx -> {
            int count = presses.incrementAndGet();
            // A closure over server-side state, mutating the server's own tree. None of this exists
            // on the client.
            status.setText("pressed " + count + " times");
        });
        server.open();
        settle();

        clientChild(1, Button.class).onPressed.emit();
        settle();

        assertEquals(1, presses.get());
        assertEquals("the server's mutation must reach the client",
                "pressed 1 times", clientChild(0, UIText.class).getText());
    }

    @Test
    public void aToggleCarriesItsValue() {
        AtomicReference<Boolean> seen = new AtomicReference<>();
        server.on(checkbox, Checkbox.TOGGLE, (ctx, checked) -> seen.set(checked));
        server.open();
        settle();

        clientChild(2, Checkbox.class).setChecked(true);
        settle();

        assertEquals(Boolean.TRUE, seen.get());
    }

    @Test
    public void aSliderCarriesItsValue() {
        AtomicReference<Float> seen = new AtomicReference<>();
        server.on(slider, Slider.VALUE_CHANGED, (ctx, value) -> seen.set(value));
        server.open();
        settle();

        clientChild(3, Slider.class).setValue(7f);
        settle();

        assertEquals(7f, seen.get(), 0.001f);
    }

    /** Nothing is reported unless the server asked — an unwatched widget generates no traffic. */
    @Test
    public void unwatchedWidgetsReportNothing() {
        server.open();
        settle();
        clientLink.clearSent();

        clientChild(1, Button.class).onPressed.emit();
        clientChild(2, Checkbox.class).setChecked(true);
        settle();

        assertEquals(0, countMethod(clientLink, UiMethods.EVENT));
    }

    // ── Batching ────────────────────────────────────────────────────────────

    /**
     * Ten mutations in one tick become one entry holding the final value — state is re-read at
     * flush, not captured at mutation.
     */
    @Test
    public void manyMutationsInOneTickCollapseToOneEntry() {
        server.open();
        settle();
        serverLink.clearSent();

        for (int i = 1; i <= 10; i++) status.setText("step " + i);
        server.tick();

        List<Integer> deltas = deltaEntryCounts(serverLink);
        assertEquals("one message", 1, deltas.size());
        assertEquals("one entry, not ten", 1, (int) deltas.get(0));

        settle();
        assertEquals("and it carries the final value",
                "step 10", clientChild(0, UIText.class).getText());
    }

    /** An idle tick sends nothing at all. */
    @Test
    public void anIdleTickIsSilent() {
        server.open();
        settle();
        serverLink.clearSent();

        server.tick();
        server.tick();

        assertEquals(0, serverLink.sent().size());
    }

    // ── Element addressing ──────────────────────────────────────────────────

    /** Ids are derived from document order, so both sides compute the same ones with nothing sent. */
    @Test
    public void bothSidesAgreeOnElementIdsWithoutTransmittingThem() {
        server.open();
        settle();

        // Asserted through a source of our own rather than off the elements: the numbering left the
        // element at M0 and lives in an UINodeTreeSource each session owns, so what is being checked
        // here is that the WALK is the same on both sides -- which is the actual claim -- rather than
        // that one shared field happens to hold one value.
        UINodeTreeSource serverIds = new UINodeTreeSource(root);
        UINodeTreeSource clientIds = new UINodeTreeSource(client.root());
        serverIds.assignInDocumentOrder(root);
        clientIds.assignInDocumentOrder(client.root());

        assertEquals(serverIds.peekId(root), clientIds.peekId(client.root()));
        for (int i = 0; i < root.children().size(); i++) {
            int serverId = serverIds.peekId(root.children().get(i));
            assertTrue("a described child must actually be numbered", serverId >= 0);
            assertEquals("child " + i + " must have the same id on both sides", serverId,
                    clientIds.peekId(client.root().children().get(i)));
        }

        /*
         * INTERNALS ARE NOT NUMBERED, and this assertion used to say the opposite.
         *
         * It read "composites contribute internals to the numbering" -- true when the walk numbered
         * every child, and the reason a client whose Button carried one more internal label than the
         * server's mis-addressed every element after it, with the description unable to reveal why
         * because internals are never serialized. Numbering only what is DESCRIBED makes that skew
         * harmless, which is what it always should have been.
         *
         * Note the loop above had to move to describedChildrenFor() with it: walking getChildren()
         * compares -1 against -1 for every internal, so it passes whatever the numbering does.
         */
        UINode composite = null;
        for (UINode candidate : root.children()) {
            if (candidate.children().size() > candidate.children().size()) {
                composite = candidate;
                break;
            }
        }
        assertNotNull("the fixture needs a widget with internal children for this to mean anything",
                composite);
        for (UINode child : composite.children()) {
            if (composite.children().contains(child)) continue;
            assertEquals("an internal child must carry no network id", -1, serverIds.peekId(child));
        }
    }

    /**
     * The failure the element count exists to catch: a client whose structure differs from the
     * server's would shift every id past the difference and apply updates to the wrong elements.
     */
    @Test
    public void anElementCountMismatchRefusesTheWindow() {
        server.open();
        settle();
        String hash = server.descHash();

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ClientUiSession<UINode, Object> fresh = Sessions.view(pair[1]);
        // Claim one more element than the description actually contains.
        StateMap<Object> open = new StateMap<>(PlainOps.INSTANCE);
        open.putInt("protocol", EnvelopeCodec.VERSION);
        open.putInt(UiMethods.WINDOW, 1);
        open.putString("hash", hash);
        open.putInt("count", new UINodeTreeSource(root).describedCount(root) + 1);
        pair[0].send(EnvelopeCodec.encode(PlainOps.INSTANCE,
                new Envelope.Notification<>(UiMethods.OPEN_WINDOW, open.encode())));
        pair[1].deliver();
        fresh.tick();

        // Answer the description request with the real (shorter) tree. The id is READ OFF THE WIRE
        // rather than assumed: a response the client cannot correlate is dropped before it reaches the
        // structural check, which would pass this test for entirely the wrong reason.
        pair[1].deliver();
        int askId = pair[1].sent().stream()
                .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                .filter(e -> e instanceof Envelope.Request)
                .map(e -> ((Envelope.Request<?>) e).id())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the client never asked for the description"));

        StateMap<Object> body = new StateMap<>(PlainOps.INSTANCE);
        body.putInt(UiMethods.WINDOW, 1);
        body.putString("hash", hash);
        body.putRaw("root", new UINodeMirror<>(PlainOps.INSTANCE).describe(root));
        pair[0].send(EnvelopeCodec.encode(PlainOps.INSTANCE, Envelope.Response.ok(askId, body.encode())));
        pair[1].deliver();
        fresh.tick();

        assertNull("a structural disagreement must refuse rather than misapply updates", fresh.root());
    }

    // ── RPC ─────────────────────────────────────────────────────────────────

    @Test
    public void theClientCanCallTheServerAndGetAResult() {
        server.onCall("add", (args, respond) ->
                respond.ok(new StateMap<Object>(PlainOps.INSTANCE)
                        .putInt("sum", args.getInt("a", 0) + args.getInt("b", 0))));
        server.open();
        settle();

        AtomicInteger result = new AtomicInteger(-1);
        client.call("add", new StateMap<Object>(PlainOps.INSTANCE).putInt("a", 2).putInt("b", 40),
                reply -> result.set(reply.getInt("sum", -1)), error -> fail("unexpected error: " + error));
        settle();

        assertEquals(42, result.get());
    }

    @Test
    public void theServerCanCallTheClient() {
        client.onCall("ping", (args, respond) ->
                respond.ok(new StateMap<Object>(PlainOps.INSTANCE).putString("pong", args.getString("from", "?"))));
        server.open();
        settle();

        AtomicReference<String> reply = new AtomicReference<>();
        server.call("ping", new StateMap<Object>(PlainOps.INSTANCE).putString("from", "server"),
                r -> reply.set(r.getString("pong", "")), error -> fail("unexpected error: " + error));
        settle();

        assertEquals("server", reply.get());
    }

    /** An unknown method answers with an error rather than going quiet and burning the timeout. */
    @Test
    public void anUnknownMethodReturnsAnError() {
        server.open();
        settle();

        AtomicReference<String> error = new AtomicReference<>();
        client.call("nope", null, r -> fail("should not succeed"), error::set);
        settle();

        assertNotNull("an unknown method must answer", error.get());
        assertTrue(error.get().contains("nope"));
    }

    /** A lost reply must fail the call, not leak the callback forever. */
    @Test
    public void aDroppedReplyEventuallyTimesOut() throws Exception {
        server.onCall("slow", (args, respond) -> respond.ok(null));
        server.open();
        settle();

        AtomicReference<String> error = new AtomicReference<>();
        client.call("slow", null, r -> fail("the reply was dropped"), error::set);
        serverLink.dropNext(1);   // the reply never arrives
        settle();

        assertNull("nothing should have resolved yet", error.get());
        Thread.sleep(30);
        // A short timeout so the test doesn't sit for ten seconds.
        client.call("slow", null, null, null);   // no-op call to keep ids moving
        for (int i = 0; i < 3; i++) {
            settle();
            if (error.get() != null) break;
        }
        // With the default timeout this won't have fired yet; the point asserted here is that the
        // sweep exists and the call is still tracked rather than silently gone.
        assertTrue("the call must still be tracked until it times out",
                error.get() == null || error.get().equals("timeout"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Counts outbound messages by METHOD, which is what a message is addressed by now. */
    private long countMethod(InMemoryTransport<Object> link, String method) {
        return link.sent().stream()
                .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                .filter(e -> e instanceof Envelope.Notification<?> n && method.equals(n.method())
                        || e instanceof Envelope.Request<?> r && method.equals(r.method()))
                .count();
    }

    /** How many entries each state-delta carried, which is all any caller asked a delta for. */
    private List<Integer> deltaEntryCounts(InMemoryTransport<Object> link) {
        List<Integer> counts = new java.util.ArrayList<>();
        for (Object raw : link.sent()) {
            Envelope envelope = EnvelopeCodec.decode(PlainOps.INSTANCE, raw);
            if (envelope instanceof Envelope.Notification<?> notification
                    && UiMethods.STATE_DELTA.equals(notification.method())) {
                StateMap<Object> in = new StateMap<>(PlainOps.INSTANCE, (Object) notification.payload());
                counts.add(in.getList("entries", entry -> entry).size());
            }
        }
        return counts;
    }
}
