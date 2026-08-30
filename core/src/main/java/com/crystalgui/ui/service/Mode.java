package com.crystalgui.ui.service;

/**
 * A live interaction that gets the input before anything on the tree does — a drag, a window
 * switcher held open by a modifier, keyboard move/size, a modal grab.
 *
 * <p>The old engine spelled these as a ladder of {@code if} branches at the top of
 * {@code consumeKeyboardEvent}, each naming a widget: cancel the drag, then
 * {@code routeKeyToWindowSwitcher}, then {@code routeKeyToKeyboardMove}, then the close watcher.
 * Every rung is correct and every rung is the input handler knowing about a feature — which is why
 * adding one meant editing the handler, and why the ORDER between them lived nowhere but the order
 * of the branches.</p>
 *
 * <p>Here the order is the stack: the most recently pushed mode is asked first, so the INNERMOST
 * live interaction wins, which is what every one of those rungs was reaching for. A drag begun
 * while a switcher is open is above it; the switcher is above a modal grab pushed before either.
 * {@link Input} names none of them.</p>
 *
 * <p><b>Take only the keys the mode acts on.</b> Swallowing everything is much closer to a modal
 * grab than most gestures need, and a mode nobody remembers entering then eats the keyboard with no
 * way out. Windows ends its keyboard-move mode on the next unrelated action and lets that keystroke
 * through; so should anything written here.</p>
 */
public interface Mode {

    /** What this mode is, for a diagnostic. Never matched on. */
    String name();

    /** @return whether this mode consumed the press. */
    default boolean keyPressed(int key, int modifiers, boolean repeat) {
        return false;
    }

    /** @return whether this mode consumed the release. */
    default boolean keyReleased(int key, int modifiers) {
        return false;
    }

    /** @return whether this mode consumed the movement (a drag does; a switcher does not). */
    default boolean pointerMoved(float x, float y) {
        return false;
    }

    /** @return whether this mode consumed the button. */
    default boolean pointerButton(int button, boolean pressed, float x, float y) {
        return false;
    }

    /** Runs when the mode leaves the stack, however it ended — popped, cancelled, or torn down. */
    default void ended() {
    }
}
