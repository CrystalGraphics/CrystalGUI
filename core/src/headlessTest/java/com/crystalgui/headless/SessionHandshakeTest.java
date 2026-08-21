package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * A whole UI crossing a wire: server builds it, client rebuilds it, with no Minecraft and no GL.
 *
 * <p>Assertions are made about <b>traffic</b> as well as outcome. "Opening a cached UI transfers
 * nothing" is a claim about what went over the wire; checking only that the tree came out right
 * would pass whether the cache worked or not.</p>
 */
public class SessionHandshakeTest {

    private InMemoryTransport<Object> serverLink;
    private InMemoryTransport<Object> clientLink;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> client;

    private static UIElement buildUi() {
        UIElement root = new UIElement();
        root.setId("settings").addClass("panel");
        root.layout(l -> l.width(220).height(140));

        root.addChild(new UIText("Server Settings"));

        Checkbox pvp = new Checkbox("Enable PvP");
        pvp.setChecked(true);
        root.addChild(pvp);

        Slider difficulty = new Slider();
        difficulty.setRange(0f, 3f).setStep(1f).setValue(2f);
        root.addChild(difficulty);

        TextField motd = new TextField();
        motd.setPlaceholder("message of the day");
        motd.setText("Welcome!");
        root.addChild(motd);
        return root;
    }

    @Before
    public void setUp() {
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverLink = pair[0];
        clientLink = pair[1];
        server = new ServerUiSession<>(7, buildUi(), serverLink, PlainOps.INSTANCE);
        client = new ClientUiSession<>(clientLink, PlainOps.INSTANCE);
    }

    /** Runs the exchange to completion: deliver, tick, repeat until nothing is in flight. */
    private void settle() {
        for (int i = 0; i < 8; i++) {
            int moved = clientLink.deliver();
            client.tick();
            moved += serverLink.deliver();
            server.tick();
            if (moved == 0) return;
        }
        fail("the exchange never settled — something is looping");
    }



    /**
     * Counts messages by METHOD, which is what a message is addressed by now.
     *
     * <p>Replaces {@code countPacketsOfType}, which decoded a packet union and read
     * {@code packet.type()}. The assertions are the same questions — how many description requests, how
     * many bodies — asked of the vocabulary rather than of a union of record types.</p>
     */
    private long countMethod(InMemoryTransport<Object> link, String method) {
        return link.sent().stream()
                .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                .filter(e -> methodOf(e).equals(method))
                .count();
    }

    /** Counts answers to a request method — a RESPONSE carries an id, not a method. */
    private long countResponses(InMemoryTransport<Object> link) {
        return link.sent().stream()
                .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                .filter(e -> e instanceof Envelope.Response)
                .count();
    }

    private static String methodOf(Envelope envelope) {
        if (envelope instanceof Envelope.Request<?> request) return request.method();
        if (envelope instanceof Envelope.Notification<?> notification) return notification.method();
        return "";
    }
    // ── The handshake ───────────────────────────────────────────────────────

    @Test
    public void aCompleteUiCrossesTheWireAndIsRebuilt() {
        server.addSheet(SheetRef.ofResource("crystalgui:ore", "abc123"));
        server.open();
        settle();

        UIElement rebuilt = client.root();
        assertNotNull("the client should have a tree", rebuilt);
        assertEquals("settings", rebuilt.getId());
        assertTrue(rebuilt.hasClass("panel"));
        assertEquals(4, rebuilt.getChildren().size());

        // Concrete types, not just tags — the whole point of registry-based reconstruction.
        assertTrue(rebuilt.getChildren().get(0) instanceof UIText);
        assertTrue(rebuilt.getChildren().get(1) instanceof Checkbox);
        assertTrue(rebuilt.getChildren().get(2) instanceof Slider);
        assertTrue(rebuilt.getChildren().get(3) instanceof TextField);

        // ...and their state came with them.
        assertEquals("Server Settings", ((UIText) rebuilt.getChildren().get(0)).getText());
        assertTrue(((Checkbox) rebuilt.getChildren().get(1)).isChecked());
        assertEquals(2f, ((Slider) rebuilt.getChildren().get(2)).getValue(), 0.001f);
        assertEquals("Welcome!", ((TextField) rebuilt.getChildren().get(3)).getText());
    }

    @Test
    public void stylesheetReferencesArriveInOrder() {
        server.addSheet(SheetRef.ofResource("crystalgui:ore", "hash-ore"));
        server.addSheet(SheetRef.anonymous("hash-generated"));
        server.setUseUserAgentSheet(true);
        server.open();
        settle();

        List<SheetRef> sheets = client.sheets();
        assertEquals(2, sheets.size());
        assertEquals("order is load-bearing — cross-sheet ties fall back to registration order",
                "crystalgui:ore", sheets.get(0).id());
        assertTrue(sheets.get(0).hasResourceId());
        assertFalse("a generated sheet has no resource id", sheets.get(1).hasResourceId());
        assertEquals("hash-generated", sheets.get(1).hash());
        assertTrue(client.useUserAgentSheet());
    }

    // ── The cache ───────────────────────────────────────────────────────────

    /** A first open costs exactly one round trip: the client has never seen this description. */
    @Test
    public void aCacheMissCostsOneRoundTrip() {
        server.open();
        settle();

        assertEquals("one request for the body", 1,
                countMethod(clientLink, UiMethods.DESCRIPTION));
        assertEquals("one body sent", 1, countResponses(serverLink));
        assertEquals(1, client.cacheSize());
    }

    /**
     * The point of content-addressing: re-opening a UI the client has already seen sends
     * <b>no description at all</b>, however large the tree.
     *
     * <p>The same {@link ClientUiSession} is kept across both opens, because the cache lives on it —
     * that is what a player opening the same GUI twice in one session looks like.</p>
     */
    @Test
    public void aCacheHitTransfersNoDescription() {
        server.open();
        settle();
        assertEquals("precondition: the first open populated the cache", 1, client.cacheSize());
        server.close("done");
        settle();

        serverLink.clearSent();
        clientLink.clearSent();

        // A second server session over the same link, for an identical UI — identical content, so
        // identical hash. The client, and therefore its cache, is the same object.
        ServerUiSession<Object> second =
                new ServerUiSession<>(8, buildUi(), serverLink, PlainOps.INSTANCE);
        second.open();
        assertEquals("precondition: an identical UI must hash the same",
                server.descHash(), second.descHash());

        clientLink.deliver();
        client.tick();
        serverLink.deliver();
        second.tick();

        assertEquals("a cached description must not be requested again",
                0, countMethod(clientLink, UiMethods.DESCRIPTION));
        assertEquals("and must not be sent again", 0, countResponses(serverLink));
        assertNotNull("yet the client still rebuilt the tree", client.root());
        assertEquals(4, client.root().getChildren().size());
    }

    /** The same UI built twice hashes the same, which is what makes a cache hit possible at all. */
    @Test
    public void anIdenticalUiProducesAnIdenticalHash() {
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> other = new ServerUiSession<>(9, buildUi(), pair[0], PlainOps.INSTANCE);
        server.open();
        other.open();
        assertEquals(server.descHash(), other.descHash());
    }

    @Test
    public void aDifferentUiProducesADifferentHash() {
        UIElement changed = buildUi();
        ((Checkbox) changed.getChildren().get(1)).setChecked(false);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> other = new ServerUiSession<>(9, changed, pair[0], PlainOps.INSTANCE);
        server.open();
        other.open();
        assertNotEquals(server.descHash(), other.descHash());
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    /** A protocol mismatch must refuse the window rather than open one it will misread. */
    @Test
    public void aProtocolMismatchRefusesTheWindow() {
        StateMap<Object> open = new StateMap<>(PlainOps.INSTANCE);
        open.putInt("protocol", EnvelopeCodec.VERSION + 99);
        open.putInt(UiMethods.WINDOW, 7);
        open.putString("hash", "somehash");
        open.putInt("count", 1);
        Object bogus = EnvelopeCodec.encode(PlainOps.INSTANCE,
                new Envelope.Notification<>(UiMethods.OPEN_WINDOW, open.encode()));
        clientLink.setReceiver(raw -> { });
        ClientUiSession<Object> isolated = new ClientUiSession<>(clientLink, PlainOps.INSTANCE);
        serverLink.send(bogus);
        clientLink.deliver();
        isolated.tick();

        assertNull("a version-mismatched window must not open", isolated.root());
    }

    /** A packet for another window must be ignored, not applied to whatever session is current. */
    @Test
    public void packetsForAnotherWindowAreIgnored() {
        server.open();
        settle();

        StateMap<Object> ask = new StateMap<>(PlainOps.INSTANCE);
        ask.putInt(UiMethods.WINDOW, 999);
        ask.putString("hash", server.descHash());
        Object foreign = EnvelopeCodec.encode(PlainOps.INSTANCE,
                new Envelope.Request<>(1, UiMethods.DESCRIPTION, ask.encode()));
        serverLink.clearSent();
        clientLink.send(foreign);
        serverLink.deliver();
        server.tick();

        // It now answers with a REFUSAL rather than silence, which is the improvement: a client
        // asking about a window this session does not serve learns so instead of waiting out a timeout.
        // What must not happen is a description body going out.
        assertEquals("no description body for a different window", 0,
                serverLink.sent().stream()
                        .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                        .filter(e -> e instanceof Envelope.Response<?> r && r.ok())
                        .count());
    }

    /** A malformed packet must be dropped with a log, not take the session down. */
    @Test
    public void anUndecodablePacketDoesNotKillTheSession() {
        server.open();
        settle();

        clientLink.send("not a packet at all");
        serverLink.deliver();
        server.tick();

        assertTrue("the session should still be usable", server.isOpen());
    }

    @Test
    public void closingTellsTheClient() {
        server.open();
        settle();
        assertNotNull(client.root());

        server.close("player walked away");
        settle();

        assertNull("the client should drop its tree", client.root());
        assertFalse(server.isOpen());
    }

    // ── Delivery ────────────────────────────────────────────────────────────

    /** Nothing moves until asked — the property that makes these orderings reproducible. */
    @Test
    public void nothingIsDeliveredImplicitly() {
        server.open();
        assertNull("no delivery yet", client.root());
        assertTrue(clientLink.pending() > 0);

        clientLink.deliver();
        assertNull("delivered, but not yet processed — that is tick()'s job", client.root());
    }

}
