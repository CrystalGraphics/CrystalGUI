package com.crystalgui.ui;

import com.crystalgui.core.window.WindowState;

/**
 * Something that needs a callback every frame for a while — a press-and-hold repeat, a blinking
 * caret, a spinner.
 *
 * <p>Registered with {@link UIWindow#registerTicker}, which drops it as soon as it reports it's
 * finished, so an idle window ticks nothing. Deliberately not a general "update" hook on every
 * element: only the handful of things actually animating should cost anything per frame.</p>
 *
 * <h3>A ticker whose element has left the tree must return {@code false}</h3>
 *
 * <p><b>This is a contract, not advice.</b> Registration is one-way by design — there is no
 * unregister — so the only thing that stops a ticker is the ticker itself, and a window that is
 * hidden is <em>detached</em> rather than deleted ({@code WindowState.HIDDEN}). Everything else about
 * a hidden window genuinely stops: no selector matches it, no layout runs, nothing paints, and the
 * input handler has dropped every reference to it. A ticker is the one thing that keeps going, and
 * what it keeps doing is invisible — the hidden editor that goes on compiling, which is the exact
 * failure the whole hide-as-detach design exists to prevent.</p>
 *
 * <p>Most tickers already satisfy it by construction (a caret stops blinking on blur, and hiding
 * blurs), which is precisely why it needs stating: the ones that do not will look fine.</p>
 *
 * <pre>{@code
 * public boolean tickFrame(float delta) {
 *     if (element.getAttachedWindow() == null) return false;   // detached: stop
 *     ...
 * }
 * }</pre>
 */
public interface UIFrameTicker {
    /**
     * @param deltaSeconds wall-clock time since the last frame
     * @return {@code true} to keep being ticked, {@code false} to be dropped
     */
    boolean tickFrame(float deltaSeconds);
}
