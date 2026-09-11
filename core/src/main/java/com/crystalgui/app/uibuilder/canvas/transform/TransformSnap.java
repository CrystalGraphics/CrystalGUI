package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector2f;

import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.widget.surface.snap.SnapAxis;
import com.crystalgui.widget.surface.snap.SnapIndicator;
import com.crystalgui.widget.surface.snap.SnapScene;
import com.crystalgui.widget.surface.snap.SnapSolver;
import com.crystalgui.widget.surface.snap.SnapSolver.AxisSnap;
import com.crystalgui.widget.surface.snap.SnapTargets;

/**
 * <b>Where a Free Transform's DRAWN box snaps</b> — numbers only, in the parent's layout space.
 *
 * <pre>{@code
 * TransformSnap.Result r = TransformSnap.move(gesture, box.x(), box.y(), false, false, 8f, zoom, scene);
 * gesture.nudgeTranslate(r.dx(), r.dy());
 * guides.show(node.parentElement(), r.indicators());
 * }</pre>
 *
 * <pre>{@code
 * TransformSnap.Result r = TransformSnap.scale(gesture, spot, aspect, aboutPivot, x, y, 8f, zoom, scene);
 * // ...move the pointer by (r.dx(), r.dy()) and scale again, then draw what it settled on:
 * guides.show(node.parentElement(), TransformSnap.settled(gesture, spot, aboutPivot, x, y, scene));
 * }</pre>
 *
 * <ul>
 *   <li><b>A move snaps the drawn box's bounds</b>, against points and gaps. Its line marks the drawn
 *       corners that lie on it, never the empty corners of the bounds.</li>
 *   <li><b>A handle snaps only while the box is square to the page</b> — tldraw's rule, asked of the
 *       drawing rather than of the rotation. @see #isSquare. At a quarter turn a side handle travels on
 *       the other axis and snaps there.</li>
 *   <li>{@code layoutX}/{@code layoutY} is the node's layout position in its parent's own space — its
 *       {@code Box.x()} less the parent's scroll, where the scene is — and the gesture's (0, 0).</li>
 * </ul>
 */
final class TransformSnap {

    /** A tolerance for round two, in solver units: the handle's point came back through two matrices. */
    static final float ROUND_TWO = 0.05f;

    /** How far a drawn edge may lean off its axis and still be a line an axis can land on. */
    private static final float SQUARE_DEGREES = 2f;

    private static final float SQUARE_SLOPE = (float) Math.tan(Math.toRadians(SQUARE_DEGREES));

    /** The four corners and the centre, as fractions of the box. */
    private static final float[] AT_X = {0f, 1f, 1f, 0f, 0.5f};
    private static final float[] AT_Y = {0f, 0f, 1f, 1f, 0.5f};

    /** What to add to the translate or the handle, in the node's own pixels, and what to draw. */
    record Result(float dx, float dy, List<SnapIndicator> indicators) {

        static final Result NONE = new Result(0f, 0f, List.of());
    }

    private TransformSnap() {
    }

    static Result move(TransformGesture g, float layoutX, float layoutY, boolean pinX, boolean pinY,
                       float tolerance, float scale, SnapScene scene) {
        float[] xs = new float[AT_X.length];
        float[] ys = new float[AT_X.length];
        drawn(g, layoutX, layoutY, xs, ys);
        SnapSolver.ShapeSnap snapped = SnapSolver.moveShape(xs, ys, pinX, pinY, tolerance, scale, scene);
        return new Result(snapped.dx(), snapped.dy(), snapped.indicators());
    }

    /**
     * @param aspect     Shift: the ratio is held, so the nearer snap leads and the other axis follows it
     * @param aboutPivot Alt: the handle travels from the pivot rather than from the opposite corner
     */
    static Result scale(TransformGesture g, Spot spot, boolean aspect, boolean aboutPivot,
                        float layoutX, float layoutY, float tolerance, float scale, SnapTargets targets) {
        if (!isSquare(g)) return Result.NONE;
        float[] xs = new float[AT_X.length];
        float[] ys = new float[AT_X.length];
        drawn(g, layoutX, layoutY, xs, ys);
        Vector2f corner = g.corner(spot);
        Vector2f handle = g.apply(corner.x, corner.y);
        AxisSnap byX = AxisSnap.none(0f);
        AxisSnap byY = AxisSnap.none(0f);
        float dx = 0f;
        float dy = 0f;
        for (int own = 0; own < 2; own++) {
            if ((own == 0 ? spot.xDirection() : spot.yDirection()) == 0) continue;
            boolean horizontal = runsHorizontally(g, corner, handle, own);
            float at = horizontal ? layoutX + handle.x : layoutY + handle.y;
            // Square to the page, so the dragged edge spans the whole drawn box on the other axis.
            float[] across = horizontal ? ys : xs;
            AxisSnap snap = SnapSolver.edge(horizontal ? SnapAxis.HORIZONTAL : SnapAxis.VERTICAL, at,
                    min(across), max(across), tolerance, scale, targets);
            if (!snap.taken()) continue;
            if (horizontal) {
                byX = snap;
                dx = snap.value() - at;
            } else {
                byY = snap;
                dy = snap.value() - at;
            }
        }

        // RATIO HELD, ONE AXIS LEADS: the nearer snap is taken and the other follows along the handle's
        // own diagonal, since landing both would change the shape. tldraw's snapResizeShapes.
        if (aspect && spot.isCorner() && (byX.taken() || byY.taken())) {
            Vector2f held = g.anchor(spot, aboutPivot);
            held = g.apply(held.x, held.y);
            float runX = handle.x - held.x;
            float runY = handle.y - held.y;
            if (byX.taken() && (!byY.taken() || Math.abs(dx) < Math.abs(dy))) {
                dy = Math.abs(runX) < 1e-4f ? 0f : dx * runY / runX;
                byY = AxisSnap.none(0f);
            } else {
                dx = Math.abs(runY) < 1e-4f ? 0f : dy * runX / runY;
                byX = AxisSnap.none(0f);
            }
        }
        List<SnapIndicator> found = new ArrayList<>(byX.indicators());
        found.addAll(byY.indicators());
        return new Result(dx, dy, List.copyOf(found));
    }

    /**
     * Round two of {@link #scale}: what the box is exactly on once the scale has settled.
     *
     * <p>And along an axis the handle drags, <b>both</b> of the box's edges when the box is back on its own
     * layout box there. A move marks both anyway, since both land at once; a handle holds the other edge
     * still, so that edge — and the corner the two held edges share — went unmarked while the box sat
     * exactly on its outline.</p>
     */
    static List<SnapIndicator> settled(TransformGesture g, Spot spot, boolean aboutPivot,
                                       float layoutX, float layoutY, SnapTargets targets) {
        List<SnapIndicator> found = new ArrayList<>(
                scale(g, spot, false, aboutPivot, layoutX, layoutY, ROUND_TWO, 1f, targets).indicators());
        if (!isSquare(g)) return List.copyOf(found);
        float[] xs = new float[AT_X.length];
        float[] ys = new float[AT_X.length];
        drawn(g, layoutX, layoutY, xs, ys);
        Vector2f corner = g.corner(spot);
        Vector2f handle = g.apply(corner.x, corner.y);
        for (int own = 0; own < 2; own++) {
            if ((own == 0 ? spot.xDirection() : spot.yDirection()) == 0) continue;
            boolean horizontal = runsHorizontally(g, corner, handle, own);
            float[] along = horizontal ? xs : ys;
            float[] across = horizontal ? ys : xs;
            float start = horizontal ? layoutX : layoutY;
            float end = start + (horizontal ? g.width() : g.height());
            if (Math.abs(min(along) - start) > ROUND_TWO || Math.abs(max(along) - end) > ROUND_TWO) continue;
            float crossStart = horizontal ? layoutY : layoutX;
            float crossEnd = crossStart + (horizontal ? g.height() : g.width());
            SnapAxis axis = horizontal ? SnapAxis.HORIZONTAL : SnapAxis.VERTICAL;
            for (float at : new float[] {start, end}) {
                if (hasLine(found, axis, at)) continue;
                found.add(new SnapIndicator.Points(axis, at,
                        sortedDistinct(crossStart, crossEnd, min(across), max(across))));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whether each drawn edge runs along an axis, to within {@link #SQUARE_DEGREES} — when an edge is a
     * line an axis can land on.
     *
     * <p><b>Asked of the drawing, and as an ANGLE.</b> tldraw asks its shape for a whole number of quarter
     * turns, which it can because its own rotate quantises to the degree; a gesture here leaves a fraction
     * of one behind, and a box half a degree off square is a box the eye calls square. A tolerance in
     * pixels cannot say that: the same half-degree crosses it as soon as the element is large or the canvas
     * zoomed, which is where a handle snap is wanted most.</p>
     */
    static boolean isSquare(TransformGesture g) {
        Vector2f origin = g.apply(0f, 0f);
        Vector2f alongX = g.apply(g.width(), 0f);
        Vector2f alongY = g.apply(0f, g.height());
        float runX = alongX.x - origin.x;
        float riseX = alongX.y - origin.y;
        float runY = alongY.x - origin.x;
        float riseY = alongY.y - origin.y;
        boolean upright = leansLittle(runX, riseX) && leansLittle(riseY, runY);
        boolean turned = leansLittle(riseX, runX) && leansLittle(runY, riseY);
        return upright || turned;
    }

    /** Whether an edge reaching {@code along} its axis and {@code off} it leans within the tolerance. */
    private static boolean leansLittle(float along, float off) {
        return Math.abs(off) <= SQUARE_SLOPE * Math.abs(along);
    }

    /** Which way a handle's own axis runs once drawn: at a quarter turn the right handle moves vertically. */
    private static boolean runsHorizontally(TransformGesture g, Vector2f corner, Vector2f handle, int own) {
        Vector2f step = g.apply(corner.x + (own == 0 ? 1f : 0f), corner.y + (own == 1 ? 1f : 0f));
        return Math.abs(step.x - handle.x) >= Math.abs(step.y - handle.y);
    }

    private static void drawn(TransformGesture g, float layoutX, float layoutY, float[] xs, float[] ys) {
        for (int i = 0; i < AT_X.length; i++) {
            Vector2f p = g.apply(g.width() * AT_X[i], g.height() * AT_Y[i]);
            xs[i] = layoutX + p.x;
            ys[i] = layoutY + p.y;
        }
    }

    private static boolean hasLine(List<SnapIndicator> indicators, SnapAxis axis, float at) {
        for (SnapIndicator indicator : indicators) {
            if (indicator instanceof SnapIndicator.Points line && line.axis() == axis
                    && Math.abs(line.at() - at) <= ROUND_TWO) {
                return true;
            }
        }
        return false;
    }

    private static float[] sortedDistinct(float... values) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = 0;
        for (float value : sorted) {
            if (n == 0 || value - sorted[n - 1] > ROUND_TWO) sorted[n++] = value;
        }
        return Arrays.copyOf(sorted, n);
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
}
