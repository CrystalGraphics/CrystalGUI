package com.crystalgui.ui.input;

import lombok.Getter;

/**
 * Per-button press state and the multi-click counter behind {@code MouseEvent#getDetail()}.
 *
 * <h3>A multi-click is close in space as well as in time</h3>
 * <p>This used to count on elapsed time alone, so two clicks <em>anywhere</em> on screen inside the
 * interval reported {@code detail == 2}. Clicking one word and then a different one made the second
 * click a double-click — not a subtle failure in anything that acts on it: the editor selected the
 * second word instead of placing a caret in it, and {@code TextField}'s double-click-to-select-word
 * behaved the same way.</p>
 *
 * <p>Browsers require proximity for exactly this reason. The counter now restarts when the pointer has
 * moved further than {@link #MULTI_CLICK_SLOP} since the previous press.</p>
 */
public final class ButtonState {

    /**
     * How far the pointer may move between presses and still continue a multi-click run, in the same
     * physical pixels the raw events carry.
     *
     * <p>Not zero: a real mouse drifts a pixel or two under a finger, and demanding an exact repeat
     * would make double-click unreliable in the other direction.</p>
     */
    public static final float MULTI_CLICK_SLOP = 4f;

    @Getter
    private boolean pressed = false;
    private long lastPressedMillis = 0L;
    private float lastPressedX = Float.NaN;
    private float lastPressedY = Float.NaN;
    @Getter
    private int detail = 0;

    public void setState(boolean newPressedState, long millis, float x, float y) {
        if (newPressedState) {
            setPressed(millis, x, y);
        } else {
            this.pressed = false;
        }
    }

    private void setPressed(long millis, float x, float y) {
        pressed = true;
        final long millisDiff = millis - lastPressedMillis;
        final boolean nearby = isNearLastPress(x, y);
        lastPressedMillis = millis;
        lastPressedX = x;
        lastPressedY = y;
        if (millisDiff <= UIInputHandler.multiClickInterval && nearby) {
            detail++;
        } else {
            detail = 1;
        }
    }

    /** Whether this press is close enough to the previous one to continue a multi-click run. */
    private boolean isNearLastPress(float x, float y) {
        // The very first press has no previous position. NaN comparisons are always false, so the
        // distance test below would answer "not nearby" anyway — but this engine has already been bitten
        // by a NaN sentinel that made a guard silently never fire, so the case is stated rather than
        // left to fall out of the arithmetic.
        if (Float.isNaN(lastPressedX) || Float.isNaN(lastPressedY)) return false;
        float dx = x - lastPressedX;
        float dy = y - lastPressedY;
        return dx * dx + dy * dy <= MULTI_CLICK_SLOP * MULTI_CLICK_SLOP;
    }

    public void resetDetail() {
        this.detail = 0;
    }
}
