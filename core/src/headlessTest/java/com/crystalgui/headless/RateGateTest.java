package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.contract.RateGate;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.TextField;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * The rate gate on its own — which is the <b>client-authored UI</b> shape.
 *
 * <p>{@code RatePolicyTest} covers the same policies reached through a session and a transport. This
 * covers them reached by a UI that owns its own data and only sends ACTIONS to the server, the way
 * vanilla's inventory does: opened locally with no round trip, every interaction a packet. Such a UI has
 * no session to inherit a gate from, and without one a slider drag sends a packet per frame.</p>
 *
 * <p>The clock is stepped rather than slept, so these assert the policy and not the machine's mood.</p>
 */
public class RateGateTest {

    private final List<String> sent = new ArrayList<>();
    private long now = 1_000L;

    private Slider slider;
    private TextField field;
    private Button button;

    @Before
    public void setUp() {
        UINodeRegistry.bootstrap();
        slider = new Slider();
        field = new TextField();
        button = new Button("Press");
        sent.clear();
        now = 1_000L;
    }

    private <P> RateGate<UINode, P> gate() {
        return new RateGate<UINode, P>((widget, kind, payload) -> sent.add(kind + "=" + payload))
                .setClock(() -> now);
    }

    @Test
    public void anImmediatePolicyGoesStraightThrough() {
        RateGate<UINode, Float> gate = gate();
        gate.offer(slider, "value", RatePolicy.IMMEDIATE, 0.5f);
        gate.offer(slider, "value", null, 0.6f);
        assertEquals(List.of("value=0.5", "value=0.6"), sent);
        assertFalse(gate.isHolding());
    }

    @Test
    public void attachTakesTheEventsOwnRate() {
        RateGate<UINode, Float> gate = gate();
        gate.attach(slider, Slider.VALUE_CHANGED);   // DRAGGING: throttled to 50ms

        slider.setValue(0.1f);
        slider.setValue(0.2f);
        slider.setValue(0.3f);

        // The first passes (nothing has been sent, so the throttle window is already open); the rest are
        // held. A gate that ignored the event's declared rate would have sent all three.
        assertEquals(List.of("value=0.1"), sent);
        assertTrue(gate.isHolding());
    }

    @Test
    public void aThrottleSendsTheLastValueAndNotTheOnesPassedThrough() {
        RateGate<UINode, Float> gate = gate();
        gate.attach(slider, Slider.VALUE_CHANGED);

        slider.setValue(0.1f);            // through
        slider.setValue(0.2f);            // held
        slider.setValue(0.3f);            // replaces it
        now += 50;
        gate.flush();

        // DROPPING INTERMEDIATE VALUES IS FINE; DROPPING THE LAST ONE IS DATA LOSS. 0.2 was passed
        // through on the way to 0.3 and is rightly gone; 0.3 is where the control actually is.
        assertEquals(List.of("value=0.1", "value=0.3"), sent);
        assertFalse(gate.isHolding());
    }

    @Test
    public void aDebounceHoldsUntilTypingStops() {
        RateGate<UINode, String> gate = gate();
        gate.attach(field, TextField.TEXT_CHANGED);   // TYPING: 150ms debounce

        field.setText("a");
        now += 100;
        gate.flush();
        assertEquals("still typing", List.of(), sent);

        field.setText("ab");                          // resets the window
        now += 100;
        gate.flush();
        assertEquals("the window restarted", List.of(), sent);

        now += 150;
        gate.flush();
        assertEquals(List.of("text=ab"), sent);
    }

    @Test
    public void aDebouncedValueNeedsSomethingToDriveTheFlush() {
        RateGate<UINode, String> gate = gate();
        gate.attach(field, TextField.TEXT_CHANGED);

        field.setText("query");
        now += 10_000;                    // time passes and nothing calls flush

        // The whole reason flush() must be driven by a tick: with no further input behind it, the last
        // keystroke of a search box would otherwise be held for good.
        assertEquals(List.of(), sent);
        assertTrue(gate.isHolding());
        gate.flush();
        assertEquals(List.of("text=query"), sent);
    }

    @Test
    public void commitSendsWhatIsHeldWhateverThePolicySays() {
        RateGate<UINode, String> gate = gate();
        gate.attach(field, TextField.TEXT_CHANGED);

        field.setText("half-typed");
        gate.commit();

        assertEquals("a teardown loses nothing", List.of("text=half-typed"), sent);
        assertFalse(gate.isHolding());
    }

    @Test
    public void forgettingAWidgetSendsWhatItStillHeld() {
        RateGate<UINode, Float> gate = gate();
        gate.attach(slider, Slider.VALUE_CHANGED);

        slider.setValue(0.1f);            // through
        slider.setValue(0.9f);            // held
        gate.forget(slider);

        assertEquals(List.of("value=0.1", "value=0.9"), sent);
        assertFalse(gate.isHolding());
    }

    /**
     * A held report carrying NO payload still leaves, even when the clock reads zero.
     *
     * <p>The gate this was extracted from marked a slot empty with {@code payload == null && at == 0},
     * which conflates three different things: a signal event genuinely carries no payload (a button
     * press is the whole message), and a clock legitimately reads zero — a host handing over a tick
     * counter starts at one. Both together and the report is silently dropped and never retried. An
     * explicit flag cannot be reached that way.</p>
     */
    @Test
    public void aSignalHeldAtClockZeroIsNotSilentlyDropped() {
        now = 0L;
        RateGate<UINode, Void> gate = gate();
        gate.offer(button, "activate", RatePolicy.throttle(50), null);
        assertEquals("the first one is due immediately", List.of("activate=null"), sent);

        gate.offer(button, "activate", RatePolicy.throttle(50), null);
        assertTrue("held by the throttle", gate.isHolding());
        now += 50;
        gate.flush();
        assertEquals(List.of("activate=null", "activate=null"), sent);
    }

    @Test
    public void twoWidgetsAreThrottledIndependently() {
        RateGate<UINode, Float> gate = gate();
        Slider other = new Slider();
        gate.attach(slider, Slider.VALUE_CHANGED);
        gate.attach(other, Slider.VALUE_CHANGED);

        slider.setValue(0.1f);
        other.setValue(0.2f);

        // One control's traffic must not spend another's budget -- the slot is keyed on (widget, kind).
        assertEquals(List.of("value=0.1", "value=0.2"), sent);
    }

    @Test
    public void aWidgetsTwoEventsAreThrottledIndependently() {
        RateGate<UINode, String> gate = gate();
        gate.attach(field, TextField.TEXT_CHANGED);
        gate.attach(field, TextField.COMMITTED);

        field.setText("a");
        gate.offer(field, "commit", RatePolicy.IMMEDIATE, "a");

        assertEquals("a commit is not held behind the typing debounce", List.of("commit=a"), sent);
        assertTrue(gate.isHolding());
    }
}
