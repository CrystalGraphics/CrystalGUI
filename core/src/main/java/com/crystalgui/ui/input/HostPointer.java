package com.crystalgui.ui.input;

import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * <b>How a host builds a pointer event</b> — the three conventions that are one line each and invisible
 * when wrong.
 *
 * <pre>{@code
 * // GLFW: already top-left, deltas already in units
 * window.input().consumeMouseEvent(
 *         HostPointer.of(x, y, dx, dy, button, pressed, HostPointer.scroll(glfwDelta), millis));
 *
 * // LWJGL2: bottom-left origin and 120 units per notch, so the host converts first
 * window.input().consumeMouseEvent(HostPointer.of(
 *         Mouse.getEventX(), displayHeight - Mouse.getEventY(),
 *         Mouse.getEventDX(), -Mouse.getEventDY(),
 *         Mouse.getEventButton(), Mouse.getEventButtonState(),
 *         HostPointer.scroll(Mouse.getEventDWheel() / 120.0), nanos / 1_000_000L));
 * }</pre>
 *
 * <p>What a host still converts for itself is genuinely its own: which corner the origin is in, and how
 * many raw units its wheel reports per notch. What it must <b>not</b> decide for itself is below.</p>
 */
public final class HostPointer {

    /** No button — and what the engine reads as "this is a move". */
    public static final int NO_BUTTON = -1;

    /** @see #of */
    public static final long NO_CLICK_TIME = -1L;

    /**
     * The engine's scroll direction: a <b>positive</b> scroll means the wheel rolled <b>down</b>.
     *
     * <p>The only other statement of it is {@code ScrollerView.setScrollTop(before + delta)}. Every
     * platform this runs on reports the opposite sign, so a host that takes its own at face value
     * scrolls and zooms backwards — which {@code CanvasView} shipped, and no test caught because the
     * test was written from the implementation.</p>
     *
     * @param platformDelta the platform's own delta, already divided by its units per notch
     */
    public static float scroll(double platformDelta) {
        return (float) -platformDelta;
    }

    /**
     * One pointer event, with the click time dropped from anything that is not a button.
     *
     * <p>That last part is why this is a factory rather than a comment: a move carrying a click
     * timestamp drifts the multi-click counter, so a slow double-click arrives as a triple. Both hosts
     * had the rule as a ternary beside the constructor, which is one edit away from being lost.</p>
     *
     * @param x       top-left origin, device pixels
     * @param y       top-left origin, device pixels
     * @param button  the button, or {@link #NO_BUTTON} for a move or a scroll
     * @param scroll  already signed by {@link #scroll(double)}
     * @param millis  when the button changed; ignored unless {@code button} is a real one
     */
    public static CgSystemInput.Mouse.Event of(int x, int y, int dx, int dy, int button,
                                               boolean pressed, float scroll, long millis) {
        return new CgSystemInput.Mouse.Event(x, y, dx, dy, button, pressed, scroll,
                button == NO_BUTTON ? NO_CLICK_TIME : millis);
    }

    private HostPointer() {
    }
}
