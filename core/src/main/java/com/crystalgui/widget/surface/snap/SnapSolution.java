package com.crystalgui.widget.surface.snap;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What {@link Snapper} settled one axis on, and <b>every</b> point that put it there.
 *
 * <pre>{@code
 * SnapSolution x = new Snapper(SnapAxis.HORIZONTAL, wantX, width, 8f, zoom).solve(targets);
 * write(x.value());               // the proposal itself when nothing was in range
 * for (SnapSolution.Hit hit : x.hits()) mark(hit.target());
 * }</pre>
 *
 * @param taken false when nothing was inside the tolerance; {@link #value} is then the unchanged
 *              proposal, so a caller writes it either way and never branches
 * @param hits  every (point, base) landing the box at {@link #value}, the tie-break's winner first.
 *              Several is the ordinary case — three things sharing an edge is a row
 */
public record SnapSolution(float value, boolean taken, List<Hit> hits) {

    /** One point, and which of the moving box's features landed on it. */
    public record Hit(SnapTarget target, SnapBase base) {
    }

    public static SnapSolution none(float wanted) {
        return new SnapSolution(wanted, false, List.of());
    }

    /** The tie-break's winner, or null when nothing was taken. */
    @Nullable
    public SnapTarget target() {
        return hits.isEmpty() ? null : hits.get(0).target();
    }

    /** @see #target */
    @Nullable
    public SnapBase base() {
        return hits.isEmpty() ? null : hits.get(0).base();
    }
}
