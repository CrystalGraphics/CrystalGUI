package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A widget declares its own reporting rate, and the client obeys it.
 *
 * <p>Declared since M1 and read by nothing until now: a {@code TextField} fires per keystroke and a
 * {@code Slider} per pixel of drag, so a panel that simply forwarded them sent a packet per keystroke —
 * and the handler's author had no way to know that without reading the widget. Phoenix LiveView puts
 * {@code phx-debounce} in the markup for the same reason.</p>
 *
 * <p>The clock is stepped rather than slept, so these assert the policy and not the machine's mood.</p>
 */
public class RatePolicyTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerUiSession<UIElement, Object> server;
    private ClientUiSession<UIElement, Object> client;
    private UIElement root;
    private TextField field;
    private Button button;
    private final List<String> typed = new ArrayList<>();
    private int presses;
    private long now = 1_000L;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        UIElementRegistry.bootstrap();
        root = new UIElement();
        field = new TextField();
        button = new Button("Press");
        root.append(field);
        root.append(button);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        server = Sessions.serveOn(1, root, serverEnd);
        client = Sessions.viewOn(clientEnd).setClock(() -> now);

        server.on(field, TextField.TEXT_CHANGED, (ctx, text) -> typed.add(text));
        server.on(button, Button.ACTIVATE, ctx -> presses++);
        server.open();
        settle();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private TextField clientField() {
        return (TextField) client.root().children().get(0);
    }

    /** IMMEDIATE is the default and must stay instant: a press is not a stream. */
    @Test
    public void aPressIsReportedAtOnce() {
        ((Button) client.root().children().get(1)).onPressed.emit();
        settle();
        assertEquals(1, presses);
    }

    /**
     * Typing is debounced: the intermediate keystrokes are dropped and <b>the last one is not</b>.
     *
     * <p>Dropping intermediate values is the point; dropping the final one would be data loss, and is
     * the reason a held value is always eventually sent rather than discarded when a newer one lands.</p>
     */
    @Test
    public void typingIsDebouncedAndTheLastValueStillArrives() {
        clientField().setText("h");
        clientField().setText("he");
        clientField().setText("hel");
        settle();
        assertEquals("nothing may leave inside the debounce window", 0, typed.size());

        now += 200;                 // past TYPING's 150ms
        settle();

        assertEquals("exactly one report, not three", 1, typed.size());
        assertEquals("and it is the LAST value, not the first", "hel", typed.get(0));
    }

    @Test
    public void afterTheWindowTheNextKeystrokeIsHeldAgain() {
        clientField().setText("a");
        now += 200;
        settle();
        assertEquals(1, typed.size());

        clientField().setText("ab");
        settle();
        assertEquals("held again -- the policy is per report, not a one-off", 1, typed.size());

        now += 200;
        settle();
        assertEquals(2, typed.size());
        assertTrue(typed.get(1).equals("ab"));
    }
}
