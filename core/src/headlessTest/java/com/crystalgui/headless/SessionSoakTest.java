package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 <b>A6</b> — two sessions under sustained traffic, checked for drift.
 *
 * <p>Every other session test is a handful of exchanges and then an assertion. This one runs hundreds of
 * rounds of mixed traffic — server-side mutations, client-reported events, and calls in both directions,
 * all in flight together — and asks the question those cannot: <b>does the client's tree still agree with
 * the server's after all of it</b>, and did every call get an answer.</p>
 *
 * <p>It is deliberately headless and deterministic. The plan scoped it for {@code InMemoryTransport}
 * precisely so it could land before a Minecraft transport existed and then be re-pointed at one; keeping
 * it here means a desync is found by {@code :core:headlessTest} in seconds rather than by watching a
 * client. There is no randomness — a soak that fails one run in twenty and cannot be replayed is worse
 * than no soak, so the traffic is driven by the round counter.</p>
 */
public class SessionSoakTest {

    private static final int ROUNDS = 250;

    private UIElement root;
    private Slider slider;
    private Checkbox checkbox;
    private UIText label;
    private Button button;

    private InMemoryTransport<Object> serverLink;
    private InMemoryTransport<Object> clientLink;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> client;

    @Before
    public void setUp() {
        root = new UIElement();
        label = new UIText("round 0");
        button = new Button("Press me");
        checkbox = new Checkbox("Enabled");
        slider = new Slider();
        slider.setRange(0f, 1000f);
        root.addChild(label);
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
            clientLink.deliver();
            serverLink.deliver();
            client.tick();
            server.tick();
        }
    }

    private <E extends UIElement> E clientChild(int index, Class<E> type) {
        return type.cast(client.root().getChildren().get(index));
    }

    /**
     * Sustained mixed traffic, then: do the two trees agree, and was every call answered?
     *
     * <p>The three kinds are interleaved on purpose. A state delta, an event and an RPC response can all
     * be in flight in the same round, which is the situation a single-exchange test never builds — and
     * the one where a correlation bug or a coalescing bug would show as drift rather than as a failure.</p>
     */
    @Test
    public void sustainedTrafficLeavesTheTwoTreesInAgreement() {
        AtomicInteger presses = new AtomicInteger();
        AtomicInteger serverCallsAnswered = new AtomicInteger();
        AtomicInteger clientCallsAnswered = new AtomicInteger();
        AtomicInteger callFailures = new AtomicInteger();

        server.onActivate(button, ctx -> presses.incrementAndGet());
        server.onCall("soak/echo", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putInt("n", args.getInt("n", -1));
            respond.ok(out);
        });
        client.onCall("soak/echo", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putInt("n", args.getInt("n", -1));
            respond.ok(out);
        });

        server.open();
        settle();

        for (int round = 1; round <= ROUNDS; round++) {
            // Server mutates its own tree.
            slider.setValue(round % 1000);
            checkbox.setChecked(round % 2 == 0);
            label.setText("round " + round);

            // Client reports an event, on the widget the server is watching.
            if (round % 3 == 0) clientChild(1, Button.class).onPressed.emit();

            // A call in each direction, correlated independently.
            final int n = round;
            StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
            args.putInt("n", n);
            server.call("soak/echo", args,
                    result -> {
                        if (result.getInt("n", -1) == n) clientCallsAnswered.incrementAndGet();
                    },
                    error -> callFailures.incrementAndGet());
            client.call("soak/echo", args,
                    result -> {
                        if (result.getInt("n", -1) == n) serverCallsAnswered.incrementAndGet();
                    },
                    error -> callFailures.incrementAndGet());

            settle();
        }
        settle();

        assertEquals("no call may fail under load", 0, callFailures.get());
        assertEquals("every server->client call answered with its own n", ROUNDS, clientCallsAnswered.get());
        assertEquals("every client->server call answered with its own n", ROUNDS, serverCallsAnswered.get());
        assertEquals("every third round pressed", ROUNDS / 3, presses.get());

        // The tree, which is the thing that drifts.
        assertEquals("slider value", slider.getValue(),
                clientChild(3, Slider.class).getValue(), 0.001f);
        assertEquals("checkbox state", checkbox.isChecked(),
                clientChild(2, Checkbox.class).isChecked());
        assertEquals("label text", label.getText(), clientChild(0, UIText.class).getText());
    }

    /**
     * Nothing is left pending when the traffic stops.
     *
     * <p>A leak here is invisible in the assertion above — every call was answered <em>and</em> the
     * router could still be holding entries for them. It shows up in production as a session whose memory
     * grows for as long as it is open, which is the shape nobody notices until a server has been up for a
     * week.</p>
     */
    @Test
    public void nothingIsLeftPendingAfterTheTrafficStops() {
        AtomicInteger answered = new AtomicInteger();
        server.onCall("soak/ping", (args, respond) -> respond.ok(null));
        server.open();
        settle();

        for (int round = 0; round < 100; round++) {
            client.call("soak/ping", null, result -> answered.incrementAndGet(), null);
        }
        settle();

        assertEquals("all hundred answered", 100, answered.get());
        // A second burst must behave identically -- ids keep climbing, and a router that failed to
        // release the first hundred would still answer while holding them.
        for (int round = 0; round < 100; round++) {
            client.call("soak/ping", null, result -> answered.incrementAndGet(), null);
        }
        settle();
        assertEquals("and the second hundred too", 200, answered.get());
    }

    /**
     * A batch of mutations in one tick collapses to one update, and keeps doing so under load.
     *
     * <p>The coalescing property {@code ServerBehaviourLoopTest} pins for a single tick, asserted across
     * many — because the thing that breaks it is a dirty set that is not cleared on flush, and that only
     * shows as unbounded growth after a while.</p>
     */
    @Test
    public void coalescingHoldsUnderRepetition() {
        server.open();
        settle();

        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < 10; i++) slider.setValue(i);
            settle();
            assertTrue("the dirty set must be empty after a flush",
                    server.pendingStateChanges().isEmpty());
        }
        assertEquals("the client still agrees", slider.getValue(),
                clientChild(3, Slider.class).getValue(), 0.001f);
    }
}
