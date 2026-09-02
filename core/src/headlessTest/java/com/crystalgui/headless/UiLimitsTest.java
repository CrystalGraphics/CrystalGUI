package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.UiLimits;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * <b>What each side will accept before it stops listening.</b>
 *
 * <p>The wire's own 8 MB reassembly ceiling bounds the transport, which is the wrong shape for
 * everything above it: a peer that stays under it can still open ten thousand windows or describe a
 * tree of a million elements. Each of those is a message the transport is happy to carry and the layer
 * above cannot survive.</p>
 */
public class UiLimitsTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerUiSession<UINode, Object> server;
    private ClientUiSession<UINode, Object> client;
    private UINode root;
    private Button button;
    private int presses;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ElementRegistry.bootstrapBuiltins();
        root = new UINode();
        button = new Button("Press");
        root.append(button);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        server = Sessions.serveOn(1, root, serverEnd);
        client = Sessions.viewOn(clientEnd);
        server.on(button, Button.ACTIVATE, ctx -> presses++);
        server.open();
        settle();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private void press() {
        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        out.putInt(UiMethods.WINDOW, 1);
        out.putInt("nid", 1);
        out.putString("kind", "activate");
        clientEnd.router().notify(UiMethods.EVENT, out.encode());
    }

    /**
     * A peer in a loop is stopped inside the second it starts.
     *
     * <p>Refused rather than queued: a rate limit that buffers is a slower way to run out of memory,
     * and the sender is by definition not waiting for any of these.</p>
     */
    @Test
    public void aFloodOfEventsIsCutOffWithinTheSecond() {
        int flood = UiLimits.MAX_INBOUND_PER_SECOND + 200;
        for (int i = 0; i < flood; i++) press();
        settle();

        assertTrue("some got through", presses > 0);
        assertTrue("but not all of them: " + presses, presses < flood);
        // TWICE the budget is the honest bound for a whole-second bucket: a burst landing either side
        // of a boundary gets one second's allowance on each. Asserting the budget exactly would be
        // asserting that the test never straddles a second, which is a fact about the machine.
        assertTrue("and not far over the budget: " + presses,
                presses <= 2 * UiLimits.MAX_INBOUND_PER_SECOND);
        assertTrue("and the excess was counted as refusals", server.refusalsFrom("alice") > 0);
    }

    /** The counter-assertion: ordinary interaction is nowhere near the cap. */
    @Test
    public void ordinaryInteractionIsUnaffected() {
        for (int i = 0; i < 60; i++) press();
        settle();

        assertEquals("sixty presses is a busy second, not a flood", 60, presses);
        assertEquals(0, server.refusalsFrom("alice"));
    }

    /** The numbers are only useful if they are above what a real UI does and below what breaks one. */
    @Test
    public void theCapsAreOrderedSensibly() {
        assertTrue(UiLimits.MAX_INBOUND_PER_SECOND > 100);
        assertTrue(UiLimits.MAX_ELEMENTS_PER_WINDOW > 1000);
        assertTrue(UiLimits.MAX_SHEET_BYTES < UiLimits.MAX_DESCRIPTION_BYTES);
        assertTrue(UiLimits.MAX_WINDOWS_PER_CONNECTION > 8);
    }
}
