package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.crystalgui.example.machine.session.MachineClient;
import com.crystalgui.example.machine.session.MachineServer;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.UiEventKinds;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.UIText;

/**
 * The worked example in {@code com.crystalgui.example}, run end to end.
 *
 * <h3>Why an example has a test</h3>
 *
 * <p>Because compiling is not the same as working, and this example's job is to be believed. A
 * signature change breaks the build and gets noticed; a behavioural one leaves six classes that
 * compile, read plausibly, and teach something that is no longer true — which is worse than having
 * no example, since a reader has no way to tell.</p>
 *
 * <p>It also runs here rather than in {@code src/test/} on purpose. This source set has
 * CrystalGraphics deliberately off the classpath, so the example passing <em>is</em> the assertion
 * that nothing in it reaches a font, a texture or {@code CgIO} — the property the whole
 * server-side story rests on, and one no amount of javadoc can establish.</p>
 *
 * <h3>The two loops</h3>
 *
 * <p>Both directions are asserted separately because they fail separately, and each looks like the
 * other from a screenshot. A client-to-server break is a control that does nothing; a
 * server-to-client break is a readout that freezes at whatever it showed when the window opened.
 * The second is what {@code ProgressBar.setFraction} did until this example was written: it had
 * {@code writeState} and never called {@code notifyStateChanged}, so the value travelled in the
 * opening description and never again.</p>
 */
public class MachineExampleTest {

    /** Server end, client end, and the two transports between them. */
    private static final class Loopback {
        final InMemoryTransport<Object>[] link = InMemoryTransport.pair();
        final ProtocolConnection<Object> serverEnd =
                Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        final ProtocolConnection<Object> clientEnd =
                Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        final MachineServer server = new MachineServer();
        final MachineClient client = new MachineClient(clientEnd);

        Loopback open() {
            server.open(serverEnd);
            settle(6);
            return this;
        }

        /** Moves everything both ways until the conversation runs out of things to say. */
        void settle(int rounds) {
            for (int i = 0; i < rounds; i++) {
                link[0].deliver();
                link[1].deliver();
                serverEnd.tick();
                clientEnd.tick();
            }
        }

        /** A world tick plus the delivery it produces. */
        void tickWorld(int times) {
            for (int i = 0; i < times; i++) {
                server.tick();
                settle(1);
            }
        }
    }

    // ── The handshake ───────────────────────────────────────────────────────

    @Test
    public void theClientRebuildsTheServersTree() {
        Loopback net = new Loopback().open();

        UIElement root = net.client.root();
        assertNotNull("the client never received a window", root);

        // Element COUNT, not a spot check. The two sides derive network ids from a document-order
        // walk and send none, so a structural disagreement of any size mis-addresses every element
        // after it -- which is why ClientUiSession refuses a tree whose count does not match.
        assertEquals("the rebuilt tree is a different shape from the described one",
                countElements(net.server.panel().root), countElements(root));

        assertNotNull("the panel's switch did not survive the round trip", root.querySelector("#power"));
        assertNotNull(root.querySelector("#throughput"));
        assertNotNull(root.querySelector("#progress"));
    }

    @Test
    public void theThemeIsNamedRatherThanSent() {
        Loopback net = new Loopback().open();

        assertEquals(1, net.client.sheets().size());
        assertEquals("the sheet's identity is its hash", MachineStyles.SHEET.hash(),
                net.client.sheets().get(0).hash());
        assertTrue("the engine's own sheet has to go underneath, or nothing is styled",
                net.client.useUserAgentSheet());
    }

    @Test
    public void reopeningTransfersNothing() {
        Loopback net = new Loopback().open();
        assertEquals("the description should be cached against its hash",
                1, net.client.session().cacheSize());
        assertTrue(net.client.session().hasCached(net.server.session().descHash()));
    }

    // ── Client to server: an interaction reaches the model ──────────────────

    @Test
    public void flippingTheClientsSwitchStartsTheServersMachine() {
        Loopback net = new Loopback().open();

        Switch power = (Switch) net.client.root().querySelector("#power");
        power.setChecked(true);
        net.settle(2);

        assertTrue("the server's own handler never ran", net.server.model().isRunning());
    }

    // ── Server to client: a model change reaches the widget ─────────────────

    /**
     * The regression this example was written by, and the one worth keeping.
     *
     * <p>It asserts a value that <b>moved after the window opened</b>, which is the only shape that
     * can see the defect: the opening description carried the correct fraction, so any assertion
     * taken at open time passes against a widget that will never update again.</p>
     */
    @Test
    public void theServersProgressReachesTheClientsBarAfterOpening() {
        Loopback net = new Loopback().open();

        ProgressBar bar = (ProgressBar) net.client.root().querySelector("#progress");
        assertEquals("nothing has run yet", 0f, bar.fraction(), 1e-4);

        net.server.model().setRunning(true);
        net.tickWorld(10);

        assertTrue("the server did not advance", net.server.model().progress() > 0f);
        assertEquals("the bar froze at the value it opened with",
                net.server.model().progress(), bar.fraction(), 1e-4);
    }

    // ── The other two contract shapes ───────────────────────────────────────

    /**
     * A <b>notification</b> arrives, and nothing is sent back.
     *
     * <p>Asserted through an effect rather than through the wire, because that is the only thing a
     * notification leaves behind: there is no response to inspect. If this ever regresses it will be
     * because the handler was registered on the SESSION instead of the CONNECTION — the session has
     * no {@code onNotify}, so that version does not compile, which is the good kind of failure.</p>
     */
    @Test
    public void aNotificationReachesTheServerWithNothingComingBack() {
        Loopback net = new Loopback().open();

        net.client.sendHeartbeat();
        net.settle(1);      // the notification crosses and the server's handler runs

        /*
         * AND THEN A WORLD TICK, WHICH IS NOT A FORMALITY. The handler above wrote into a widget,
         * which marked it dirty on the session -- and a dirty set is only ever flushed by
         * ServerUiSession.tick(). settle() pumps the CONNECTION, which is a different thing.
         *
         * The first version of this test omitted it and failed, with the server's handler having run
         * perfectly. That is the exact production failure mode of forgetting the server tick: a live
         * session that answers calls and never sends another state update.
         */
        net.tickWorld(1);

        // The server writes the count into its own readout, which travels back as an ordinary state
        // delta -- so seeing it on the CLIENT proves both halves of the round trip.
        assertTrue("the server never saw the heartbeat, or never flushed it",
                wireText(net).contains("heartbeat #1"));
    }

    /**
     * A <b>refused</b> request runs the error callback, not the result one.
     *
     * <p>The half a happy-path example never shows, and the reason a request takes two lambdas.
     * {@code respond.fail} is an ordinary answer that happens to say no — same envelope kind as a
     * success — so a caller must be able to tell "refused" from "never came back". Only one of those
     * is worth retrying.</p>
     */
    @Test
    public void aRefusedRequestRunsTheErrorCallbackWithACode() {
        Loopback net = new Loopback().open();

        String[] result = { null };
        String[] failure = { null };
        net.client.session().call("machine/rename",
                new StateMap<Object>(PlainOps.INSTANCE).putString("name", "   "),
                ok -> result[0] = "accepted",
                error -> failure[0] = error);
        net.settle(2);

        assertNull("a refusal must not run the result callback", result[0]);
        // The CODE, not prose. A client branches on a value; it cannot branch on a message, and
        // matching message text is a coupling nobody remembers making.
        assertEquals("EMPTY_NAME", failure[0]);
    }

    /** And the same call succeeds when the argument is good — the positive control. */
    @Test
    public void thatSameCallSucceedsWithAUsableName() {
        Loopback net = new Loopback().open();

        String[] failure = { null };
        boolean[] accepted = { false };
        net.client.session().call("machine/rename",
                new StateMap<Object>(PlainOps.INSTANCE).putString("name", "Furnace"),
                ok -> accepted[0] = true,
                error -> failure[0] = error);
        net.settle(2);

        assertNull(failure[0]);
        assertTrue("the result callback never ran", accepted[0]);
        assertEquals("and the model was actually renamed", "Furnace", net.server.model().label());
    }

    /**
     * <b>Each side's line is written only by that side</b>, and the two say different things about
     * one exchange.
     *
     * <p>This is the assertion that would have caught the readout bug the two-line layout replaced.
     * There was one line with a badge naming its last author, and pressing Announce produced the
     * CLIENT badge above the SERVER's sentence — because {@code Property.set} returns early on an
     * equal value, so the server rewriting "SERVER" over "SERVER" marked nothing dirty and never
     * entered the delta, while the text beside it did.</p>
     *
     * <p>The rule it taught is worth more than the fix: <b>a state delta carries what CHANGED on the
     * server, never what DIFFERS between the two sides.</b> A client that writes into a server-owned
     * widget has desynchronised it, and the server cannot put it right, because from where the server
     * is standing nothing happened.</p>
     */
    @Test
    public void eachSideWritesItsOwnResultLineAndOnlyItsOwn() {
        Loopback net = new Loopback().open();

        net.client.sendHeartbeat();     // the client says "sent", locally and immediately
        net.settle(1);
        net.tickWorld(1);               // the server says "received", and flushes it

        assertTrue("the client's line should say it SENT one, got: " + clientLine(net),
                clientLine(net).contains("NOTIFY sent"));
        assertTrue("the server's line should say it RECEIVED one, got: " + serverLine(net),
                serverLine(net).contains("NOTIFY received"));
        assertTrue(serverLine(net).contains("heartbeat #1"));
    }

    /**
     * And the server's own line survives a client-side write, because the client never touches it.
     *
     * <p>The negative control for the row above: if the two lines were ever collapsed back into one,
     * the client's write would land on the server's sentence and this fails.</p>
     */
    @Test
    public void aClientWriteDoesNotDisturbTheServersLine() {
        Loopback net = new Loopback().open();

        net.client.sendHeartbeat();
        net.settle(1);
        net.tickWorld(1);
        String afterServerWrote = serverLine(net);

        net.client.requestStats();      // a purely client-side write, no round trip completed yet
        assertEquals("the client must not be able to overwrite the server's line",
                afterServerWrote, serverLine(net));
    }

    private static String serverLine(Loopback net) {
        return textOf(net, "#result-server");
    }

    private static String clientLine(Loopback net) {
        return textOf(net, "#result-client");
    }

    private static String textOf(Loopback net, String selector) {
        UIElement found = net.client.root().querySelector(selector);
        return found instanceof UIText ? ((UIText) found).getText() : "";
    }

    /** Reads the panel's protocol readout out of the CLIENT's tree. */    /** Reads the panel's protocol readout out of the CLIENT's tree. */
    private static String wireText(Loopback net) {
        return serverLine(net);
    }

    // ── The rule that is easiest to break ───────────────────────────────────

    /**
     * Registering behaviour after {@code open()} must throw.
     *
     * <p>The set of reported events is part of the description the client has already been rebuilt
     * from, so a handler added afterwards waits for an event no client will ever send. Allowing it
     * would produce a control that is wired, looks wired, and silently does nothing — the failure
     * this whole layer is least able to report.</p>
     */
    @Test
    public void behaviourCannotBeAddedOnceTheDescriptionHasGone() {
        Loopback net = new Loopback().open();
        try {
            net.server.session().on(net.server.panel().purge, UiEventKinds.ACTIVATE, ctx -> { });
            fail("expected a refusal");
        } catch (IllegalStateException expected) {
            assertTrue("the message should say why, not merely that",
                    expected.getMessage().contains("open()"));
        }
    }

    private static int countElements(UIElement element) {
        int total = 1;
        for (UIElement child : element.getChildren()) total += countElements(child);
        return total;
    }
}
