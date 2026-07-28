package com.crystalgui.ui;

/**
 * Something that needs a callback every frame for a while — a press-and-hold repeat, a blinking
 * caret, a spinner.
 *
 * <p>Registered with {@link UIWindow#registerTicker}, which drops it as soon as it reports it's
 * finished, so an idle window ticks nothing. Deliberately not a general "update" hook on every
 * element: only the handful of things actually animating should cost anything per frame.</p>
 */
public interface UIFrameTicker {
    /**
     * @param deltaSeconds wall-clock time since the last frame
     * @return {@code true} to keep being ticked, {@code false} to be dropped
     */
    boolean tickFrame(float deltaSeconds);
}
