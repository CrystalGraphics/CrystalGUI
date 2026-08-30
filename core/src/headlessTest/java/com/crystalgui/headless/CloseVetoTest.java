package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.CloseReason;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * <b>Ask before closing</b> — D8, the one thing a server waits for an answer to.
 *
 * <p>Everything else in this protocol is the server stating and the client following. Here the client
 * knows something the server cannot: whether there is half-typed text or an unsaved edit behind the
 * window about to be taken away.</p>
 */
public class CloseVetoTest {

    /** Refuses on demand, and counts how often it was asked. */
    public static class GuardedPanel extends UIElement implements Networked<String> {
        static final AtomicBoolean REFUSE = new AtomicBoolean(false);
        static final List<String> ASKED = new ArrayList<>();

        @Override
        public void build(String model) {
            addChild(new UIText(model));
        }

        @Override
        public void serve(String model, ServerScope io) {
        }

        @Override
        public void client(ClientScope io) {
        }

        @Override
        public boolean mayClose() {
            ASKED.add("asked");
            return !REFUSE.get();
        }
    }

    private static final UiType<GuardedPanel, String> TYPE =
            UiType.of("test:guarded", GuardedPanel::new);

    /**
     * A panel that says nothing at all about closing — which is nearly every real one.
     *
     * <p>Deliberately overrides no close hook, because the bug this exists for is only reachable when
     * it does not: the framework's question went by a name {@code UIElement} already used for the
     * close-watcher, where {@code false} means "I did not handle this". A class method beats an
     * interface default, so every panel that had not overridden it vetoed its own close and the X
     * silently did nothing. Every test above happens to override, which is exactly why they all passed
     * against it.</p>
     */
    public static class PlainPanel extends UIElement implements Networked<String> {
        @Override
        public void build(String model) {
            addChild(new UIText(model));
        }
    }

    private static final UiType<PlainPanel, String> PLAIN =
            UiType.of("test:plain", PlainPanel::new);

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerWindow<GuardedPanel> window;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();
        ElementRegistry.bootstrapBuiltins();
        GuardedPanel.REFUSE.set(false);
        GuardedPanel.ASKED.clear();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientEnd).setMount(context -> new WindowMount.MountedWindow() {
            @Override public void closedByServer(String reason) { }
            @Override public void focus() { }
            @Override public void contentReplaced(UIElement newRoot) { }
        });
        window = ServerWindows.of(serverEnd).open(TYPE, "hello");
        settle();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    /**
     * <b>A panel that overrides nothing is closeable.</b> The regression test for the collision above.
     *
     * <p>Asserted through the guard the host actually consults — {@code WindowFrame.setDiscardGuard} is
     * wired to this, and a {@code false} there makes {@code requestClose()} return without closing, so
     * the window stays with no error anywhere.</p>
     */
    @Test
    public void aPanelThatOverridesNothingMayClose() {
        ServerWindow<PlainPanel> plain = ServerWindows.of(serverEnd).open(PLAIN, "hello");
        settle();

        ClientWindowContext shown = ClientWindows.of(clientEnd).windows().stream()
                .filter(w -> w.type().equals("test:plain")).findFirst().orElse(null);
        assertNotNull("the plain window mounted", shown);
        assertTrue("a panel with no opinion must not veto its own close", shown.mayClose());

        // THE TWO QUESTIONS, side by side, which is the whole point of the separate name. UIElement's
        // requestClose() is the close-watcher hook and answers FALSE for "I did not handle this"; the
        // framework's mayClose() answers TRUE for "yes, you may". One name for both meant every panel
        // that had not overridden it vetoed its own close, silently.
        PlainPanel panel = (PlainPanel) shown.root();
        assertFalse("UIElement's close-watcher hook says 'I did not handle it'", panel.requestClose());
        assertTrue("...and the framework's question says 'yes, you may'", panel.mayClose());

        Boolean[] decided = { null };
        plain.requestClose("done", answer -> decided[0] = answer);
        settle();
        assertEquals(Boolean.TRUE, decided[0]);
        assertFalse(plain.isLive());
    }

    @Test
    public void contentThatConsentsLetsTheWindowClose() {
        Boolean[] decided = { null };
        window.requestClose("done", answer -> decided[0] = answer);
        settle();

        assertEquals("the client was asked", 1, GuardedPanel.ASKED.size());
        assertEquals(Boolean.TRUE, decided[0]);
        assertFalse("and it really closed", window.isLive());
    }

    /**
     * <b>A refusal keeps the window.</b>
     *
     * <p>The point of the whole exchange: a server closing a window it owns must not discard work it
     * cannot see. Note the window is still live afterwards — refusing is not "close later", it is
     * "not now".</p>
     */
    @Test
    public void contentThatRefusesKeepsTheWindow() {
        GuardedPanel.REFUSE.set(true);
        Boolean[] decided = { null };
        window.requestClose("done", answer -> decided[0] = answer);
        settle();

        assertEquals(1, GuardedPanel.ASKED.size());
        assertEquals(Boolean.FALSE, decided[0]);
        assertTrue("a refused close leaves the window exactly as it was", window.isLive());
    }

    /** And it can be asked again — a refusal is about this moment, not about this window. */
    @Test
    public void aRefusalIsNotPermanent() {
        GuardedPanel.REFUSE.set(true);
        window.requestClose("first try", null);
        settle();
        assertTrue(window.isLive());

        GuardedPanel.REFUSE.set(false);
        Boolean[] decided = { null };
        window.requestClose("second try", answer -> decided[0] = answer);
        settle();

        assertEquals(2, GuardedPanel.ASKED.size());
        assertEquals(Boolean.TRUE, decided[0]);
        assertFalse(window.isLive());
    }

    /**
     * A plain {@code close()} does NOT ask, and that is deliberate.
     *
     * <p>The veto is for a close that is a request — the user pressing X, a server tidying up. A window
     * whose subject has ceased to exist (the block was broken, the player walked away) is not asking
     * anybody's permission, and a panel able to refuse that could keep a window open over a furnace
     * that is no longer there.</p>
     */
    @Test
    public void aPlainCloseDoesNotAsk() {
        window.close("the block was broken");
        settle();

        assertTrue("nobody was asked", GuardedPanel.ASKED.isEmpty());
        assertFalse(window.isLive());
    }
}
