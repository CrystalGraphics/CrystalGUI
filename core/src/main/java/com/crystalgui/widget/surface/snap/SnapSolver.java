package com.crystalgui.widget.surface.snap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>One axis, solved against points and gaps together</b>, with what it landed on ready to draw.
 *
 * <pre>{@code
 * SnapScene scene = BoxTargets.sceneFor(node);   // once per gesture, if nothing else moves
 * float tol = SnapSolver.SCREEN_TOLERANCE;
 * AxisSnap x = SnapSolver.move(HORIZONTAL, wantX, width, wantY, wantY + height, tol, zoom, scene);
 * AxisSnap y = SnapSolver.move(VERTICAL, wantY, height, x.value(), x.value() + width, tol, zoom, scene);
 * x = SnapSolver.remeasure(x, HORIZONTAL, width, y.value(), y.value() + height, scene);
 * write(x.value(), y.value());
 * draw(x.indicators(), y.indicators());
 * }</pre>
 *
 * <pre>{@code
 * // A resize snaps the dragged edge alone, against points.
 * AxisSnap right = SnapSolver.edge(HORIZONTAL, x + width, y, y + height, tol, zoom, scene);
 * // Something drawn -- turned, sheared -- snaps by its bounds and marks its real points.
 * AxisSnap x = SnapSolver.move(HORIZONTAL, minX, maxX - minX, minY, maxY, features, tol, zoom, scene);
 * }</pre>
 *
 * <ul>
 *   <li><b>The nearest wins, point or gap.</b> When both land the box in the same place both are drawn:
 *       it is aligned and evenly spaced at once.</li>
 *   <li><b>{@link #remeasure} is round two</b>, as both references take it. x is solved before y moves
 *       the box, so without it x's line marks corners where the box no longer is.</li>
 *   <li><b>{@code crossFrom}/{@code crossTo} is where the box sits on the OTHER axis</b>: the row a gap
 *       must share with it.</li>
 * </ul>
 *
 * <h3>Gaps, ported from Excalidraw's {@code getGapSnaps}</h3>
 *
 * <p>The box has to share the gap's row. It may then sit <b>centred</b> in the gap when it fits, or
 * <b>continue</b> it — past the far box, or ahead of the near one — at the same length. Each draws two
 * bars at the middle of where the box and the row overlap: the two halves it split, or the gap it matched
 * and the one it made.</p>
 */
public final class SnapSolver {

    /** How close counts, in screen pixels — tldraw's and Excalidraw's eight. */
    public static final float SCREEN_TOLERANCE = 8f;

    /** Takes only what the box is already on. @see #remeasure */
    private static final float EXACT = 0.01f;

    /** Two origins closer than this are the same place. */
    private static final float SAME = 0.01f;

    /** How far a feature may sit from a line and still be marked on it: its point came through a matrix. */
    private static final float ON_LINE = 0.05f;

    /** Two bars closer than this are one bar. */
    private static final float SAME_BAR = 0.5f;

    /** Where one axis went and what to draw for it; no indicators when nothing was taken. */
    public record AxisSnap(float value, boolean taken, List<SnapIndicator> indicators) {

        public static AxisSnap none(float wanted) {
            return new AxisSnap(wanted, false, List.of());
        }
    }

    /**
     * <b>The moving thing's own points on one axis</b>, which a line marks on the moving side: each an
     * offset from the leading edge of its bounds, and where it sits on the other axis.
     *
     * <pre>{@code
     * Features.box(width, y, y + height)        // four corners and the centre -- the default
     * new Features(offsetsFromMinX, pointYs)    // a drawn shape, whose bounds are not its outline
     * }</pre>
     *
     * <p>They decide only what is marked; the solve is by the bounds either way. A turned box's line then
     * passes through its corner rather than through the empty corner of its bounds.</p>
     */
    public record Features(float[] offsets, float[] crosses) {

        public static Features box(float extent, float crossFrom, float crossTo) {
            float middle = (crossFrom + crossTo) * 0.5f;
            return new Features(new float[] {0f, 0f, extent, extent, extent * 0.5f},
                    new float[] {crossFrom, crossTo, crossFrom, crossTo, middle});
        }

        /** An edge's two ends. */
        static Features edge(float crossFrom, float crossTo) {
            return new Features(new float[] {0f, 0f}, new float[] {crossFrom, crossTo});
        }
    }

    private record GapSnap(float origin, float distance, SnapIndicator.Gap first, SnapIndicator.Gap second) {
    }

    private SnapSolver() {
    }

    /** A box, against points and gaps; its corners and centre are what a line marks. */
    public static AxisSnap move(SnapAxis axis, float wanted, float extent, float crossFrom, float crossTo,
                                float screenTolerance, float scale, SnapScene scene) {
        return move(axis, wanted, extent, crossFrom, crossTo, Features.box(extent, crossFrom, crossTo),
                screenTolerance, scale, scene);
    }

    /**
     * Anything with bounds, against points and gaps.
     *
     * @param wanted   where the bounds' leading edge would go unsnapped
     * @param extent   the bounds' width or height on this axis
     * @param features which of its points a line marks. @see Features
     * @param scale    world units to screen pixels — the zoom, in 2D
     */
    public static AxisSnap move(SnapAxis axis, float wanted, float extent, float crossFrom, float crossTo,
                                Features features, float screenTolerance, float scale, SnapScene scene) {
        float s = scale <= 0f ? 1f : scale;
        SnapSolution points = new Snapper(axis, wanted, extent, screenTolerance, s).solve(scene);
        List<GapSnap> gaps = gapSnaps(axis, wanted, extent, crossFrom, crossTo, screenTolerance, s, scene);
        if (!points.taken() && gaps.isEmpty()) return AxisSnap.none(wanted);

        float value = points.value();
        float nearest = points.taken() ? Math.abs(points.value() - wanted) * s : Float.MAX_VALUE;
        for (GapSnap gap : gaps) {
            // Strictly: a point at the same distance keeps the box, and the gap is drawn beside it.
            if (gap.distance() < nearest) {
                nearest = gap.distance();
                value = gap.origin();
            }
        }

        List<SnapIndicator> indicators = new ArrayList<>();
        if (points.taken() && Math.abs(points.value() - value) < SAME) {
            lines(axis, points, extent, features, indicators);
        }
        for (GapSnap gap : gaps) {
            if (Math.abs(gap.origin() - value) >= SAME) continue;
            addBar(indicators, gap.first());
            addBar(indicators, gap.second());
        }
        return new AxisSnap(value, true, List.copyOf(indicators));
    }

    /**
     * One edge alone, against points — what a resize snaps.
     *
     * @param crossFrom where the edge runs on the other axis; both of its ends are marked on the line
     */
    public static AxisSnap edge(SnapAxis axis, float wanted, float crossFrom, float crossTo,
                                float screenTolerance, float scale, SnapTargets targets) {
        SnapSolution points = Snapper.edge(axis, wanted, screenTolerance, scale).solve(targets);
        if (!points.taken()) return AxisSnap.none(wanted);
        List<SnapIndicator> indicators = new ArrayList<>();
        lines(axis, points, 0f, Features.edge(crossFrom, crossTo), indicators);
        return new AxisSnap(points.value(), true, List.copyOf(indicators));
    }

    /** How far a rigid shape has to go to snap, and what to draw for it. @see #moveShape */
    public record ShapeSnap(float dx, float dy, List<SnapIndicator> indicators) {
    }

    /**
     * <b>A shape moved rigidly</b>, by its points as drawn: its bounds against points and gaps, its own
     * points marked, both axes and round two in one call.
     *
     * <pre>{@code
     * ShapeSnap s = SnapSolver.moveShape(xs, ys, false, false, SCREEN_TOLERANCE, zoom, scene);
     * position.add(s.dx(), s.dy());
     * }</pre>
     *
     * @param xs   the shape's points where the move would put them, in the scene's space
     * @param pinX Shift's pinned axis, which is never offered to the solver
     */
    public static ShapeSnap moveShape(float[] xs, float[] ys, boolean pinX, boolean pinY,
                                      float screenTolerance, float scale, SnapScene scene) {
        float minX = min(xs);
        float minY = min(ys);
        float width = max(xs) - minX;
        float height = max(ys) - minY;

        AxisSnap x = pinX ? AxisSnap.none(minX) : move(SnapAxis.HORIZONTAL, minX, width, minY,
                minY + height, shapeFeatures(xs, minX, ys, 0f), screenTolerance, scale, scene);
        float dx = x.value() - minX;
        AxisSnap y = pinY ? AxisSnap.none(minY) : move(SnapAxis.VERTICAL, minY, height, minX + dx,
                minX + dx + width, shapeFeatures(ys, minY, xs, dx), screenTolerance, scale, scene);
        float dy = y.value() - minY;
        // Round two: x's lines measured from where y put the shape.
        x = remeasure(x, SnapAxis.HORIZONTAL, width, minY + dy, minY + dy + height,
                shapeFeatures(xs, minX, ys, dy), scene);

        List<SnapIndicator> found = new ArrayList<>(x.indicators());
        found.addAll(y.indicators());
        return new ShapeSnap(dx, dy, List.copyOf(found));
    }

    /** Offsets from the bounds' leading edge on one axis, and each point's place on the other, shifted. */
    private static Features shapeFeatures(float[] along, float min, float[] across, float shift) {
        float[] offsets = new float[along.length];
        float[] crosses = new float[along.length];
        for (int i = 0; i < along.length; i++) {
            offsets[i] = along[i] - min;
            crosses[i] = across[i] + shift;
        }
        return new Features(offsets, crosses);
    }

    private static float min(float[] values) {
        float min = values[0];
        for (float value : values) min = Math.min(min, value);
        return min;
    }

    private static float max(float[] values) {
        float max = values[0];
        for (float value : values) max = Math.max(max, value);
        return max;
    }

    /** Round two of {@link #move}: the value kept, what it is on measured from the box's final cross. */
    public static AxisSnap remeasure(AxisSnap snap, SnapAxis axis, float extent,
                                     float crossFrom, float crossTo, SnapScene scene) {
        return remeasure(snap, axis, extent, crossFrom, crossTo, Features.box(extent, crossFrom, crossTo),
                scene);
    }

    /** As above, for something whose features are not a box's. */
    public static AxisSnap remeasure(AxisSnap snap, SnapAxis axis, float extent, float crossFrom,
                                     float crossTo, Features features, SnapScene scene) {
        if (!snap.taken()) return snap;
        AxisSnap again = move(axis, snap.value(), extent, crossFrom, crossTo, features, EXACT, 1f, scene);
        return new AxisSnap(snap.value(), true, again.indicators());
    }

    /** Round two of {@link #edge}. */
    public static AxisSnap remeasureEdge(AxisSnap snap, SnapAxis axis, float crossFrom, float crossTo,
                                         SnapTargets targets) {
        if (!snap.taken()) return snap;
        AxisSnap again = edge(axis, snap.value(), crossFrom, crossTo, EXACT, 1f, targets);
        return new AxisSnap(snap.value(), true, again.indicators());
    }

    /**
     * <b>One line per coordinate, through every point on it</b> — Excalidraw keys its point lines by
     * coordinate for this. The moving side's own points on the line go in too, so the line reaches it.
     */
    private static void lines(SnapAxis axis, SnapSolution solution, float extent, Features features,
                              List<SnapIndicator> into) {
        Map<Float, List<Float>> byLine = new LinkedHashMap<>();
        List<SnapScene.Outline> owners = new ArrayList<>();
        float[] offsets = features.offsets();
        float[] crosses = features.crosses();
        for (SnapSolution.Hit hit : solution.hits()) {
            float at = Math.round(hit.target().at() * 100f) / 100f;
            List<Float> line = byLine.computeIfAbsent(at, key -> new ArrayList<>());
            line.add(hit.target().cross());
            // Where on the bounds this base sits: 0, the extent, or half of it.
            float offset = -hit.base().originFor(0f, extent);
            for (int i = 0; i < offsets.length; i++) {
                if (Math.abs(offsets[i] - offset) <= ON_LINE) line.add(crosses[i]);
            }
            SnapScene.Outline owner = hit.target().owner();
            if (owner != null && !containsSame(owners, owner)) owners.add(owner);
        }
        for (Map.Entry<Float, List<Float>> line : byLine.entrySet()) {
            into.add(new SnapIndicator.Points(axis, line.getKey(), sortedDistinct(line.getValue())));
        }
        // AND WHOSE EACH POINT IS: a mark on a corner nothing else draws says nothing on its own.
        for (SnapScene.Outline owner : owners) into.add(new SnapIndicator.Owner(owner.xs(), owner.ys()));
    }

    private static boolean containsSame(List<SnapScene.Outline> outlines, SnapScene.Outline outline) {
        for (SnapScene.Outline kept : outlines) {
            if (kept == outline) return true;
        }
        return false;
    }

    private static float[] sortedDistinct(List<Float> values) {
        float[] sorted = new float[values.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = values.get(i);
        Arrays.sort(sorted);
        int n = 0;
        for (float value : sorted) {
            if (n == 0 || value - sorted[n - 1] >= SAME) sorted[n++] = value;
        }
        return Arrays.copyOf(sorted, n);
    }

    private static List<GapSnap> gapSnaps(SnapAxis axis, float wanted, float extent, float crossFrom,
                                          float crossTo, float tolerance, float scale, SnapScene scene) {
        List<GapSnap> found = new ArrayList<>();
        for (SnapScene.Gap gap : scene.gaps(axis)) {
            // Excalidraw's one condition for all three: the box shares the gap's row.
            if (gap.crossTo() <= crossFrom || gap.crossFrom() >= crossTo) continue;
            float start = gap.before().end(axis);
            float end = gap.after().start(axis);
            float length = end - start;
            float cross = (Math.max(gap.crossFrom(), crossFrom) + Math.min(gap.crossTo(), crossTo)) * 0.5f;

            if (length > extent) {
                float origin = start + (length - extent) * 0.5f;
                float distance = Math.abs(origin - wanted) * scale;
                if (distance <= tolerance) {
                    found.add(new GapSnap(origin, distance,
                            new SnapIndicator.Gap(axis, start, origin, cross),
                            new SnapIndicator.Gap(axis, origin + extent, end, cross)));
                }
            }
            float past = gap.after().end(axis) + length;
            float pastDistance = Math.abs(past - wanted) * scale;
            if (pastDistance <= tolerance) {
                found.add(new GapSnap(past, pastDistance,
                        new SnapIndicator.Gap(axis, start, end, cross),
                        new SnapIndicator.Gap(axis, gap.after().end(axis), past, cross)));
            }
            float ahead = gap.before().start(axis) - length - extent;
            float aheadDistance = Math.abs(ahead - wanted) * scale;
            if (aheadDistance <= tolerance) {
                found.add(new GapSnap(ahead, aheadDistance,
                        new SnapIndicator.Gap(axis, start, end, cross),
                        new SnapIndicator.Gap(axis, ahead + extent, gap.before().start(axis), cross)));
            }
        }
        return found;
    }

    /** A bar two snaps both report is one bar, as Excalidraw dedupes its gap lines. */
    private static void addBar(List<SnapIndicator> into, SnapIndicator.Gap bar) {
        for (SnapIndicator kept : into) {
            if (kept instanceof SnapIndicator.Gap other
                    && Math.abs(other.from() - bar.from()) < SAME_BAR
                    && Math.abs(other.to() - bar.to()) < SAME_BAR
                    && Math.abs(other.cross() - bar.cross()) < SAME_BAR) {
                return;
            }
        }
        into.add(bar);
    }
}
