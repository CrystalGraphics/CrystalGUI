package com.crystalgui.ui.contract;

import com.crystalgui.ui.UIElement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * Applies a widget's own {@link RatePolicy} to what it reports.
 *
 * <p>The rate belongs to the WIDGET rather than to whoever is listening, because the right answer is a
 * property of the interaction: a text field fires per keystroke and a slider per pixel of drag, so
 * anything that simply forwarded them would send a packet per keystroke — and the listener's author has
 * no way to know that without reading the widget. Phoenix LiveView puts {@code phx-debounce} /
 * {@code phx-throttle} in the markup for the same reason.</p>
 *
 * <h3>Dropping intermediate values is fine; dropping the LAST one is data loss</h3>
 *
 * <p>So a held value is never discarded — only replaced, and always eventually sent. That is what makes
 * a slider released between ticks report where it actually ended up rather than where it happened to be
 * passing through. {@link #commit()} is the same guarantee at teardown.</p>
 *
 * <h3>Who needs one</h3>
 *
 * <p>A {@code Networked} window gets this for free: its session owns a gate and drives it from the
 * connection's tick. It is public for the other shape — a <b>client-authored UI that still sends to the
 * server</b>, which has no session to inherit a gate from, and which otherwise sends a packet per frame
 * for the whole of a drag. Note that such a UI cannot inherit the other half of the contract:
 * {@link Event#sanitize} takes the WIDGET, so a server holding no tree has nothing to clamp a forged
 * value against and must validate against its own model by hand.</p>
 *
 * <pre>{@code
 * RateGate<Float> gate = new RateGate<>((widget, kind, value) ->
 *         connection.notify("mymod:throughput", args(value)));
 * gate.attach(throughput, Slider.VALUE_CHANGED);   // takes the event's own rate
 * connection.onTick(gate::flush);                  // a debounce still needs something to expire it
 * }</pre>
 *
 * <p><b>{@link #flush()} must be driven by something</b>, or a debounced value with no further input
 * behind it is held for good — the last keystroke of a search box would simply never be sent. A throttle
 * clears itself only while the user keeps moving.</p>
 *
 * <p>Not thread-safe, and deliberately: it is driven from the frame or tick thread that owns the widgets
 * it is keyed on.</p>
 *
 * @param <P> what the sink is handed — a decoded value for a local UI, an encoded payload for a session
 */
public final class RateGate<P> {

    /** Where a report goes once its policy has let it through. */
    @FunctionalInterface
    public interface Sink<P> {
        void send(UIElement widget, String kind, @Nullable P payload);
    }

    /**
     * A report waiting for its policy to let it go.
     *
     * <p><b>Both flags exist because the alternative is a sentinel over a value the CALLER chooses.</b>
     * {@code held} is not "is {@code at} set", because a payload is legitimately null — a button press
     * carries nothing — so nullness separates neither, and neither does a clock reading zero. And
     * {@code sentOnce} is not "is {@code sent} zero", for the same reason one step along: a host handing
     * over a tick counter starts near zero, so a throttled control would hold its FIRST report for a
     * full interval, once per session, on that host only.</p>
     */
    private static final class Pending<P> {
        @Nullable P payload;
        boolean held;
        long at;                                  // when the value arrived, for a debounce
        long sent;                                // when this slot last went, for a throttle
        boolean sentOnce;                         // ...and whether it ever has. @see #flush
        RatePolicy policy = RatePolicy.IMMEDIATE;
    }

    private final Sink<P> sink;
    private final Map<UIElement, Map<String, Pending<P>>> pending = new LinkedHashMap<>();

    /**
     * Where "now" comes from.
     *
     * <p>Wall clock, because this is rate limiting rather than animation — a held report leaving a few
     * milliseconds late is invisible, and nothing here interpolates. Replaceable so a test can step it
     * rather than sleep, and so a host that already has a tick clock can hand that over.</p>
     */
    private LongSupplier clock = System::currentTimeMillis;

    public RateGate(Sink<P> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** @see #clock */
    public RateGate<P> setClock(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Subscribes to an event and reports it at the rate the event itself declares.
     *
     * <p>The typed entry point, and the one to reach for: the payload reaches the sink undecoded, so
     * {@code P} is whatever the event carries. A caller that encodes first calls {@link #offer} instead.</p>
     */
    public <W extends UIElement> RateGate<P> attach(W widget, Event<W, P> event) {
        event.attach(widget, payload -> offer(widget, event.kind(), event.rate(), payload));
        return this;
    }

    /**
     * Offers a report, which leaves now or waits, depending on the policy.
     *
     * <p>A null or {@linkplain RatePolicy#isImmediate() immediate} policy goes straight through, so the
     * common case allocates nothing.</p>
     */
    public void offer(UIElement widget, String kind, @Nullable RatePolicy policy, @Nullable P payload) {
        if (policy == null || policy.isImmediate()) {
            sink.send(widget, kind, payload);
            return;
        }
        Pending<P> slot = pending.computeIfAbsent(widget, w -> new LinkedHashMap<>())
                .computeIfAbsent(kind, k -> new Pending<>());
        slot.payload = payload;
        slot.held = true;
        slot.at = clock.getAsLong();
        slot.policy = policy;
        flush();
    }

    /** Sends whatever has waited long enough. Drive this from a tick, or a debounce never expires. */
    public void flush() {
        if (pending.isEmpty()) return;
        long now = clock.getAsLong();
        for (Map.Entry<UIElement, Map<String, Pending<P>>> byWidget : pending.entrySet()) {
            for (Map.Entry<String, Pending<P>> entry : byWidget.getValue().entrySet()) {
                Pending<P> slot = entry.getValue();
                if (!slot.held) continue;
                boolean due = slot.policy.debounceMillis() > 0
                        ? now - slot.at >= slot.policy.debounceMillis()
                        : !slot.sentOnce || now - slot.sent >= slot.policy.throttleMillis();
                if (!due) continue;
                slot.sent = now;
                slot.sentOnce = true;
                release(byWidget.getKey(), entry.getKey(), slot);
            }
        }
    }

    /**
     * Sends everything held, whatever its policy says. What a teardown does, so nothing is lost.
     *
     * <p>Also drops the per-slot history, so a gate reused afterwards throttles from a clean slate rather
     * than against a timestamp belonging to the window before it.</p>
     */
    public void commit() {
        for (Map.Entry<UIElement, Map<String, Pending<P>>> byWidget : pending.entrySet()) {
            for (Map.Entry<String, Pending<P>> entry : byWidget.getValue().entrySet()) {
                Pending<P> slot = entry.getValue();
                if (slot.held) release(byWidget.getKey(), entry.getKey(), slot);
            }
        }
        pending.clear();
    }

    /**
     * Forgets a widget, sending anything it still holds.
     *
     * <p>For a widget leaving the tree. Sending rather than discarding is the rule {@link #commit}
     * follows too — a control's last value does not become an intermediate one just because the control
     * has gone.</p>
     */
    public void forget(UIElement widget) {
        Map<String, Pending<P>> slots = pending.remove(widget);
        if (slots == null) return;
        for (Map.Entry<String, Pending<P>> entry : slots.entrySet()) {
            Pending<P> slot = entry.getValue();
            if (slot.held) release(widget, entry.getKey(), slot);
        }
    }

    /** Whether anything is waiting. Diagnostics and tests. */
    public boolean isHolding() {
        for (Map<String, Pending<P>> slots : pending.values()) {
            for (Pending<P> slot : slots.values()) {
                if (slot.held) return true;
            }
        }
        return false;
    }

    private void release(UIElement widget, String kind, Pending<P> slot) {
        P payload = slot.payload;
        slot.payload = null;
        slot.held = false;
        // Cleared BEFORE the send, so a sink that reports back into this gate cannot find the slot it is
        // being called about still held and release it a second time.
        sink.send(widget, kind, payload);
    }
}
