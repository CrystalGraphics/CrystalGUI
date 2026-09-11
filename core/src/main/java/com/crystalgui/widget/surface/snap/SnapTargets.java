package com.crystalgui.widget.surface.snap;

import java.util.function.Consumer;

/**
 * <b>Where a surface's snap points come from</b> — the seam that keeps {@link Snapper} ignorant of
 * what a sibling, a guide or a vertex is.
 *
 * <pre>{@code
 * SnapTargets rulerGuides = (axis, sink) -> {
 *     for (float at : axis == SnapAxis.HORIZONTAL ? verticalGuides : horizontalGuides) {
 *         sink.accept(new SnapTarget(at, 0f, SnapTarget.Kind.GRID));
 *     }
 * };
 * SnapSolution x = new Snapper(SnapAxis.HORIZONTAL, wantX, width, 8f, zoom).solve(rulerGuides);
 * }</pre>
 *
 * <p>Asked once per axis per solve. A laid-out box's come from {@link BoxTargets#sceneFor}, which also
 * carries the gaps {@link SnapSolver} snaps to.</p>
 *
 * <p>It is also the whole of what a 3D surface would replace. The expensive half of 3D snapping is
 * generating candidates from meshes — a BVH, backface culling, a visibility test — and none of it is
 * shared with "the siblings of this node", so it belongs behind a seam rather than inside the solver.</p>
 */
@FunctionalInterface
public interface SnapTargets {

    /** Offers every point worth considering on {@code axis}. */
    void collect(SnapAxis axis, Consumer<SnapTarget> sink);
}
