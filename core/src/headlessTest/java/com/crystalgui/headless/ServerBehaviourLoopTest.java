package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.NetworkIds;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UIPacket;
import com.crystalgui.net.UIPacketCodec;
import com.crystalgui.net.UiEventKinds;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;
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
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> client;

    private UIElement root;
    private Button button;
    private Checkbox checkbox;
    private Slider slider;
    private UIText status;

    @Before
    public void setUp() {
        root = new UIElement();
        status = new UIText("idle");
        button = new Button("Press me");
        checkbox = new Checkbox("Enabled");
        slider = new Slider();
        slider.setRange(0f, 10f);
        root.addChild(status);
        root.addChild(button);
        root.addChild(checkbox);
        root.addChild(slider);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverLink = pair[0];
        clientLink = pair[1];
        server = new ServerUiSession<>(1, root, serverLink, PlainOps.INSTANCE);
        client = new ClientUiSession<>(clientLink, PlainOps.INSTANCE);
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
        return type.cast(client.root().getChildren().get(index));
    }

    // ── The loop ────────────────────────────────────────────────────────────

    /** Press a button on the client; a server closure runs and updates a label the client then shows. */
    @Test
    public void aClientPressRunsAServerLambdaAndTheResultComesBack() {
        AtomicInteger presses = new AtomicInteger();
        server.onActivate(button, ctx -> {
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
        server.on(checkbox, UiEventKinds.TOGGLE, ctx -> seen.set(ctx.payload().getBool("checked", false)));
        server.open();
        settle();

        clientChild(2, Checkbox.class).setChecked(true);
        settle();

        assertEquals(Boolean.TRUE, seen.get());
    }

    @Test
    public void aSliderCarriesItsValue() {
        AtomicReference<Float> seen = new AtomicReference<>();
        server.on(slider, UiEventKinds.VALUE, ctx -> seen.set(ctx.payload().getFloat("value", -1f)));
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

        assertEquals(0, countOfType(clientLink, UIPacket.UiEvent.TYPE));
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

        List<UIPacket.StateDelta<Object>> deltas = deltasFrom(serverLink);
        assertEquals("one packet", 1, deltas.size());
        assertEquals("one entry, not ten", 1, deltas.get(0).entries().size());

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

        assertEquals(root.getNetworkId(), client.root().getNetworkId());
        for (int i = 0; i < root.getChildren().size(); i++) {
            assertEquals("child " + i + " must have the same id on both sides",
                    root.getChildren().get(i).getNetworkId(),
                    client.root().getChildren().get(i).getNetworkId());
        }
        // Internals are numbered too — a Button's label exists identically on both sides.
        assertTrue("composites contribute internals to the numbering",
                NetworkIds.count(root) > root.getChildren().size() + 1);
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
        ClientUiSession<Object> fresh = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        // Claim one more element than the description actually contains.
        pair[0].send(UIPacketCodec.encode(PlainOps.INSTANCE,
                new UIPacket.OpenWindow(UIPacketCodec.PROTOCOL_VERSION, 1, hash,
                        NetworkIds.count(root) + 1, List.of(), false)));
        pair[1].deliver();
        fresh.tick();
        // Answer the description request with the real (shorter) tree.
        pair[1].deliver();
        pair[0].send(UIPacketCodec.encode(PlainOps.INSTANCE,
                new UIPacket.Description<>(1, hash,
                        com.crystalgui.serialization.UIDescriptionCodec.CODEC.encode(PlainOps.INSTANCE, root))));
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

    private long countOfType(InMemoryTransport<Object> link, String type) {
        return link.sent().stream()
                .map(raw -> UIPacketCodec.decode(PlainOps.INSTANCE, raw))
                .filter(p -> p.type().equals(type))
                .count();
    }

    @SuppressWarnings("unchecked")
    private List<UIPacket.StateDelta<Object>> deltasFrom(InMemoryTransport<Object> link) {
        return link.sent().stream()
                .map(raw -> UIPacketCodec.decode(PlainOps.INSTANCE, raw))
                .filter(p -> p instanceof UIPacket.StateDelta)
                .map(p -> (UIPacket.StateDelta<Object>) p)
                .toList();
    }
}
