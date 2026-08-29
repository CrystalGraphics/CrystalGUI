package com.crystalgui.ui.contract;

/**
 * How often an {@link Event} may reach the far side. {@code plan_ui_rewrite.md} M1.
 *
 * <h3>Why a widget declares this rather than a handler doing it</h3>
 *
 * <p>Because the right answer is a property of the <em>interaction</em>, not of the application. A
 * {@code TextField} fires per keystroke and a {@code Slider} fires per pixel of drag, so a server-side
 * panel that simply forwards them is one that sends a packet per keystroke — and the handler author
 * has no way to know that without reading the widget. Phoenix LiveView puts {@code phx-debounce} and
 * {@code phx-throttle} in the markup for exactly this reason: the element knows its own tempo.</p>
 *
 * <p><b>{@code commitOnRelease} is the half that makes throttling safe.</b> Dropping intermediate
 * values is fine; dropping the <em>last</em> one is data loss, and a throttle with no trailing edge
 * does precisely that — a slider released between ticks reports a value that is not where it ended up.
 * So a rate-limited event always delivers its final value, whatever the window says.</p>
 *
 * <p>M1 declares the policy and M3 enforces it. Stating it now means the widgets are described once
 * rather than twice.</p>
 */
public final class RatePolicy {

    /** Every occurrence travels. Right for anything discrete — a press, a selection, a commit. */
    public static final RatePolicy IMMEDIATE = new RatePolicy(0, 0, false);

    /** Typing: quiet for 150ms, and always on commit. VS Code and LiveView both land near this. */
    public static final RatePolicy TYPING = new RatePolicy(150, 0, true);

    /** Dragging: at most one every 50ms, and always the value it was released at. */
    public static final RatePolicy DRAGGING = new RatePolicy(0, 50, true);

    private final int debounceMillis;
    private final int throttleMillis;
    private final boolean commitOnRelease;

    private RatePolicy(int debounceMillis, int throttleMillis, boolean commitOnRelease) {
        this.debounceMillis = debounceMillis;
        this.throttleMillis = throttleMillis;
        this.commitOnRelease = commitOnRelease;
    }

    /** Waits for {@code millis} of quiet before sending, and always sends the final value. */
    public static RatePolicy debounce(int millis) {
        return new RatePolicy(millis, 0, true);
    }

    /** Sends at most one every {@code millis}, and always sends the final value. */
    public static RatePolicy throttle(int millis) {
        return new RatePolicy(0, millis, true);
    }

    public int debounceMillis() {
        return debounceMillis;
    }

    public int throttleMillis() {
        return throttleMillis;
    }

    /** Whether the last value always travels, however the window fell. */
    public boolean commitOnRelease() {
        return commitOnRelease;
    }

    public boolean isImmediate() {
        return debounceMillis == 0 && throttleMillis == 0;
    }

    @Override
    public String toString() {
        if (isImmediate()) return "immediate";
        return (debounceMillis > 0 ? "debounce " + debounceMillis + "ms" : "throttle " + throttleMillis + "ms")
                + (commitOnRelease ? " + commit" : "");
    }
}
