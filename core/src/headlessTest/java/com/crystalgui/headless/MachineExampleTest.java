package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Switch;

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
