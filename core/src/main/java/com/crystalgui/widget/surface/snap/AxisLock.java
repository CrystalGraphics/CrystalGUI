package com.crystalgui.widget.surface.snap;

import javax.annotation.Nullable;

/**
 * <b>Shift's axis constraint, latched.</b>
 *
 * <pre>{@code
 * AxisLock lock = new AxisLock();
 * // per drag update
 * SnapAxis free = lock.update(hasShift(modifiers), dx, dy);   // null while both are free
 * }</pre>
 *
 * <h3>It latches, and that is the point</h3>
 *
 * <p>Deciding the axis afresh every frame from {@code abs(dx) >= abs(dy)} makes it flip between the two
 * near the diagonal, which is what a slow diagonal drag reads as jitter. None of the tools this is
 * ported from re-decides: the axis is chosen once, when the hand has committed far enough to mean it,
 * and held for as long as Shift is.</p>
 *
 * <p>Releasing Shift unlocks and <b>re-arms</b>, so pressing it again latches from where the pointer is
 * now rather than from the press. That is what makes a wrong guess recoverable without letting go of
 * the mouse.</p>
 *
 * <h3>The free axis, not the locked one</h3>
 *
 * <p>{@link #update} answers which axis may still MOVE, because that is the question every caller has:
 * the other one is pinned to where the gesture started, and — the defect this exists for — must not be
 * offered to {@link Snapper} either. A solver handed a pinned axis will happily find it an alignment
 * and hand back a number, and whoever wrote the pin has already returned.</p>
 */
public final class AxisLock {

    /** How far the hand has to travel before it has committed, in the drag's own units. */
    private static final float THRESHOLD = 3f;

    @Nullable
    private SnapAxis latched;

    private boolean armed = true;

    /**
     * @param dx how far the pointer has come since the press, on each axis
     * @return the axis still free to move, or null while both are
     */
    @Nullable
    public SnapAxis update(boolean constrained, float dx, float dy) {
        if (!constrained) {
            latched = null;
            armed = true;
            return null;
        }
        if (latched == null && armed && Math.abs(dx) + Math.abs(dy) >= THRESHOLD) {
            latched = Math.abs(dx) >= Math.abs(dy) ? SnapAxis.HORIZONTAL : SnapAxis.VERTICAL;
            armed = false;
        }
        return latched;
    }

    /** What {@link #update} last answered, without advancing anything. */
    @Nullable
    public SnapAxis free() {
        return latched;
    }

    /** Whether {@code axis} is pinned to where the gesture started. */
    public boolean isPinned(SnapAxis axis) {
        return latched != null && latched != axis;
    }

    /** Between gestures. */
    public void reset() {
        latched = null;
        armed = true;
    }
}
