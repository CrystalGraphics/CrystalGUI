package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgKeyCodes;

import javax.annotation.Nullable;

/**
 * Moving or resizing a window with the arrow keys — Win32's {@code Move}/{@code Size}, CrystalOS
 * <b>W13c</b>.
 *
 * <h3>A MODE, and the only genuinely new interaction in W13</h3>
 *
 * <p>Everything else in the window command set is a verb that happens and is over. This one is held
 * open: the arrows nudge, {@code Enter} commits, {@code Escape} puts the window back exactly where it
 * was. That is Win32's system-menu Move and Size, which is the only way to move a window whose title bar
 * has ended up off-screen — and the reason a system menu carries them at all.</p>
 *
 * <h3>It takes keys AHEAD of dispatch, like the switcher</h3>
 *
 * <p>A mode with no element of its own gets no keys: dispatch goes to whatever has focus, and a focused
 * editor moves its caret with an arrow. GNOME takes a real modal grab for the duration; here the rung is
 * the one a live drag already occupies — intercepted in {@code consumeKeyboardEvent} before anything is
 * dispatched.</p>
 *
 * <p><b>Only the keys it acts on.</b> Swallowing everything is much closer to a grab than the feature
 * needs, and it would take the invoking chord with it. Anything else ends the mode by committing, which
 * is Windows' behaviour and means a mode can never be left stuck: the next thing you do gets you out of
 * it.</p>
 */
public final class WindowKeyboardMove {

    /** One arrow press, in logical pixels — Win32 nudges by one and accelerates on repeat; this does not. */
    public static final float STEP = 8f;

    /** With the fine modifier held. A window is placed by eye, so a coarse step is the common case. */
    public static final float FINE_STEP = 1f;

    /** What the arrows are doing. */
    public enum Mode {
        MOVE,
        SIZE
    }

    @Nullable
    private WindowFrame frame;
    @Nullable
    private Mode mode;

    /** Where the window was when the mode opened — what Escape puts back. */
    private float originLeft, originTop, originWidth, originHeight;

    /** Whether a keyboard move or resize is currently running. */
    public boolean isActive() {
        return frame != null && mode != null;
    }

    /**
     * Begins a keyboard move or resize on {@code target}.
     *
     * <p>Refused for a window that is maximised or fullscreen: both are geometries the compositor owns,
     * and nudging one would leave a window that claims to be maximised and is not. Win32 greys its own
     * Move and Size rows in exactly that state.</p>
     */
    public boolean begin(@Nullable WindowFrame target, Mode wanted) {
        if (target == null || wanted == null) return false;
        if (target.state() != WindowState.VISIBLE) return false;
        if (target.isMaximized() || target.isFullscreen()) return false;

        frame = target;
        mode = wanted;
        originLeft = target.getWantedLeft();
        originTop = target.getWantedTop();
        originWidth = target.recordedWidth();
        originHeight = target.recordedHeight();
        target.addClass(ACTIVE_CLASS);
        return true;
    }

    /** On the window while it is being moved or sized from the keyboard, so a sheet can say so. */
    public static final String ACTIVE_CLASS = "__keyboard-moving__";

    /**
     * @return whether the key was consumed — {@code false} lets it carry on to ordinary dispatch
     */
    public boolean handleKey(int key, boolean fine) {
        if (!isActive()) return false;

        switch (key) {
            case CgKeyCodes.KEY_ESCAPE:
                // PUT BACK EXACTLY, which is the whole reason the origin is captured rather than the
                // deltas being undone: a nudge that hit the clamp moved the window less than it asked
                // for, so unwinding the requested steps would not land where it started.
                if (mode == Mode.SIZE) frame.resizeTo(originWidth, originHeight);
                frame.moveTo(originLeft, originTop);
                finish();
                return true;
            case CgKeyCodes.KEY_RETURN:
                finish();
                return true;
            case CgKeyCodes.KEY_LEFT:
                nudge(-step(fine), 0f);
                return true;
            case CgKeyCodes.KEY_RIGHT:
                nudge(step(fine), 0f);
                return true;
            case CgKeyCodes.KEY_UP:
                nudge(0f, -step(fine));
                return true;
            case CgKeyCodes.KEY_DOWN:
                nudge(0f, step(fine));
                return true;
            default:
                // ANYTHING ELSE COMMITS AND IS NOT EATEN. Windows ends the mode on the next unrelated
                // action, which is what stops a mode nobody remembers entering from swallowing the
                // keyboard. Returning false lets that keystroke do whatever it was going to do.
                finish();
                return false;
        }
    }

    private static float step(boolean fine) {
        return fine ? FINE_STEP : STEP;
    }

    private void nudge(float dx, float dy) {
        if (mode == Mode.MOVE) {
            frame.moveTo(frame.getWantedLeft() + dx, frame.getWantedTop() + dy);
            return;
        }
        // SIZED FROM THE MEASURED BOX, not from a running total: the frame clamps against its own CSS
        // minimum, so a total would keep shrinking on paper while the window stood still and the first
        // press back the other way would spend the difference before anything moved.
        frame.resizeTo(Math.max(1f, frame.recordedWidth() + dx),
                Math.max(1f, frame.recordedHeight() + dy));
    }

    /** Ends the mode, keeping whatever the window currently is. */
    public void finish() {
        if (frame != null) frame.removeClass(ACTIVE_CLASS);
        frame = null;
        mode = null;
    }
}
