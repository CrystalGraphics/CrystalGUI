package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.net.protocol.UiMethods;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * <b>The server is suspicious of what a client tells it.</b> Network audit finding S5.
 *
 * <p>Event dispatch validated nothing: not whether the element was enabled, not whether the payload
 * was in range, not whether it was a number at all. Chromium states the rule this stack is built on —
 * the browser process must be maximally suspicious of its IPC inputs, because the renderer may be
 * compromised — and a Minecraft server has the same relationship with a client it does not control.</p>
 *
 * <p>The split under test: what a legal gesture <b>could have produced</b> is sanitized and delivered,
 * so the handler runs and the model stays sane; what it <b>could not</b> is refused and counted.</p>
 */
public class EventValidationTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerUiSession<UINode, Object> server;
    private ClientUiSession<UINode, Object> client;

    private UINode root;
    private Slider slider;
    private Button button;
    private TextField field;

    private final List<Float> values = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();
    private int presses;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ElementRegistry.bootstrapBuiltins();

        root = new UINode();
        slider = new Slider();
        slider.setRange(0f, 10f);
        button = new Button("Press");
        field = new TextField();
        field.setMaxLength(4);
        root.append(slider);
        root.append(button);
        root.append(field);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        server = Sessions.serveOn(1, root, serverEnd);
        client = Sessions.viewOn(clientEnd);

        server.on(slider, Slider.VALUE_CHANGED, (ctx, value) -> values.add(value));
        server.on(button, Button.ACTIVATE, ctx -> presses++);
        server.on(field, TextField.TEXT_CHANGED, (ctx, text) -> texts.add(text));
        server.open();
        settle();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    /** Sends a raw event, bypassing the client widgets — which is what a forged packet is. */
    private void forge(int nid, String kind, java.util.function.Consumer<StateMap<Object>> payload) {
        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        out.putInt(UiMethods.WINDOW, 1);
        out.putInt("nid", nid);
        out.putString("kind", kind);
        StateMap<Object> carried = new StateMap<>(PlainOps.INSTANCE);
        payload.accept(carried);
        out.putRaw("p", carried.encode());
        clientEnd.router().notify(UiMethods.EVENT, out.encode());
        settle();
    }

    private int nidOf(UINode element) {
        // The client's tree mirrors the server's numbering, so its index is the server's id.
        return 1 + root.children().indexOf(element);
    }

    // ── Sanitized: a value a legal gesture could have produced ───────────────

    /**
     * <b>The case the audit named.</b> {@code Math.max(0, Math.min(1, NaN))} is {@code NaN}, and the
     * {@code ==} guard in a model's setter is false against it — so the model is poisoned, cycle
     * progress never completes, and every viewer's bars read NaN forever.
     */
    @Test
    public void aForgedNaNNeverReachesTheHandler() {
        forge(nidOf(slider), "value", p -> p.putFloat("value", Float.NaN));

        assertEquals("the handler must still run -- a clamp is a value the user could have made",
                1, values.size());
        assertFalse("...but never with NaN in it", Float.isNaN(values.get(0)));
        assertEquals(0f, values.get(0), 1e-6f);
    }

    @Test
    public void aForgedOutOfRangeValueIsClampedToTheSlidersOwnBounds() {
        forge(nidOf(slider), "value", p -> p.putFloat("value", 9999f));
        assertEquals(1, values.size());
        assertEquals("clamped by the WIDGET, which is the only thing that knows its range",
                10f, values.get(0), 1e-6f);
    }

    @Test
    public void aForgedOverlongStringIsCutToWhatCouldHaveBeenTyped() {
        // Recorded from setUp: session.on refuses a duplicate (element, kind), deliberately -- a
        // second registration would be one handler reaching inside another.
        forge(nidOf(field), "text", p -> p.putString("text", "far too long to have been typed"));

        assertEquals(1, texts.size());
        assertEquals("maxLength is 4", 4, texts.get(0).length());
    }

    // ── Refused: what no legal gesture could have produced ───────────────────

    @Test
    public void aDisabledElementCannotBeActivated() {
        button.setEnabled(false);
        settle();
        forge(nidOf(button), "activate", p -> { });

        assertEquals("a disabled control cannot be pressed, so this did not come from a user",
                0, presses);
        assertEquals(1, server.refusalsFrom("alice"));
    }

    @Test
    public void anInertElementCannotBeActivated() {
        button.setInert(true);
        settle();
        forge(nidOf(button), "activate", p -> { });

        assertEquals(0, presses);
        assertEquals(1, server.refusalsFrom("alice"));
    }

    @Test
    public void aKindNobodyAskedForIsRefused() {
        forge(nidOf(button), "invented", p -> { });
        assertEquals(0, presses);
        assertEquals(1, server.refusalsFrom("alice"));
    }

    /**
     * An ordinary event still gets through — the counter-assertion, and it is not a formality.
     *
     * <p>A guard written as "refuse everything" passes every test above and makes every control in the
     * application dead. The same reasoning the `Button` left-button guard already records.</p>
     */
    @Test
    public void anOrdinaryEventIsStillDelivered() {
        forge(nidOf(button), "activate", p -> { });
        assertEquals(1, presses);
        assertEquals("and nothing was refused", 0, server.refusalsFrom("alice"));
    }

    /**
     * Past the threshold the session stops listening to that viewer — and only to that viewer.
     *
     * <p>Minecraft kicks on packet flood and Chromium's {@code ReportBadMessage} kills the sending
     * renderer; neither takes the document down, because the document belongs to everyone else
     * watching it.</p>
     */
    @Test
    public void aFloodOfRefusalsStopsTheViewerAndNotTheWindow() {
        server.setRefusalThreshold(3);
        for (int i = 0; i < 5; i++) forge(nidOf(button), "invented", p -> { });

        assertTrue("counted", server.refusalsFrom("alice") >= 3);
        assertTrue("the window is still open for anyone else", server.isOpen());

        // No longer listened to: even a legitimate event from this viewer is now ignored.
        forge(nidOf(button), "activate", p -> { });
        assertEquals(0, presses);
    }
}
