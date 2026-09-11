package com.crystalgui.app.uibuilder.canvas.transform;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;

/**
 * The state and the arithmetic of a Free Transform, with no element and no document in it.
 *
 * <p>Everything here is in the node's own pixels and radians, so it can be asserted without a window, a
 * font stack or a pointer. {@code TransformBox} draws it and {@code FreeTransformTool} drives it;
 * neither owns a number.</p>
 *
 * <pre>{@code
 * TransformGesture g = new TransformGesture();
 * g.reset(box.width(), box.height(), current, originX, originY);
 * g.press(new Grip(Kind.SCALE, Spot.BOTTOM_RIGHT));
 * g.scaleTo(pointInOuterSpace, false, false);
 * Transform t = g.toTransform();
 * }</pre>
 *
 * <h3>The op order is translate, rotate, skew, scale — not CSS's usual one</h3>
 *
 * <p>CSS composes left to right, so the LAST op is applied to a point first. Putting scale last means the
 * box scales along its own edges: drag the right handle of a box rotated 30 degrees and it grows the way
 * it looks. Written scale-first, the same drag grows it sideways across the rotation — correct CSS, and
 * it reads as a broken handle.</p>
 *
 * <h3>An anchor is held by compensating the translate</h3>
 *
 * <p>A transform scales about {@code transform-origin}, but a handle drag holds the OPPOSITE edge still.
 * Those are two different points, and the pivot is the designer's to place. So the scale is written about
 * the origin as CSS requires and the translate absorbs the difference.</p>
 */
public final class TransformGesture {

    /** What a press on the box means. */
    public enum Kind { NONE, MOVE, PIVOT, SCALE, ROTATE, SKEW }

    /** A press target: a kind, and which of the eight it is on when the kind has one. */
    public record Grip(Kind kind, @Nullable Spot spot) {

        public static final Grip NONE = new Grip(Kind.NONE, null);

        public boolean is(Kind other) {
            return kind == other;
        }
    }

    /** Shift's rotation step, and Photoshop's. */
    private static final float ANGLE_SNAP = (float) Math.toRadians(15);

    /** A scale may approach zero but never reach it: a zero matrix cannot be inverted. */
    private static final float MIN_SCALE = 0.01f;

    private float width;
    private float height;

    private float tx;
    private float ty;
    private float sx = 1f;
    private float sy = 1f;
    private float rotation;
    private float skewX;
    private float skewY;
    private float originX;
    private float originY;

    private Grip grip = Grip.NONE;
    private float pressTx;
    private float pressTy;
    private float pressSx = 1f;
    private float pressSy = 1f;
    private float pressRotation;
    private float pressSkewX;
    private float pressSkewY;

    /**
     * Starts from what the node already carries.
     *
     * <p>An existing transform is read back only when it has the shape this class writes — at most one of
     * each op, in this class's order. Anything else cannot be decomposed into these seven numbers without
     * lying about it, so the gesture starts from identity and REPLACES it on commit, which is what
     * Photoshop does with a layer it cannot describe.</p>
     *
     * @return whether the existing transform was understood; false means committing replaces it
     */
    public boolean reset(float width, float height, @Nullable Transform existing,
                         float originX, float originY) {
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        clearOps();
        grip = Grip.NONE;
        if (existing == null || existing.isIdentity()) return true;

        // THE ORDERED WALK IS THE FAST PATH, and it is exact: ops already in the order this gesture
        // composes them are read straight into the fields, so a value written by this tool comes back
        // as the numbers that were typed rather than as their matrix rounded off.
        if (!inCanonicalOrder(existing) || carriesBothShears(existing)) {
            return decomposeMatrix(existing);
        }

        for (Transform.Op op : existing.ops()) {
            switch (op.kind()) {
                case TRANSLATE -> {
                    tx = op.lx().resolve(width);
                    ty = op.ly().resolve(height);
                }
                case ROTATE -> rotation = op.fx();
                case SKEW -> {
                    skewX = op.fx();
                    skewY = op.fy();
                }
                case SCALE -> {
                    sx = op.fx();
                    sy = op.fy();
                }
            }
        }
        return true;
    }

    /**
     * Reads any transform by <b>composing it to a matrix and taking that apart</b>.
     *
     * <p>Ported from CSS Transforms Level 1, §"Decomposing a 2D matrix" — the {@code unmatrix} routine
     * from <i>Graphics Gems II</i> that every browser uses to interpolate between two {@code matrix()}
     * values. It exists here for the same reason: the five fields describe
     * {@code translate → rotate → skew → scale} composed in that order, and a transform written in any
     * other order is a matrix those fields cannot be read off directly. Composing first makes the order
     * irrelevant — {@code scale(2) rotate(1rad)} and {@code rotate(1rad) scale(2)} are different
     * matrices, and each decomposes to the fields that reproduce it.</p>
     *
     * <p><b>Origin-relative</b>, because the fields are: {@code applyTo} builds
     * {@code T(origin) · ops · T(-origin)}, so the ops themselves are recovered by undoing that
     * sandwich. Skipping it folds the origin's own shift into {@code tx}/{@code ty}.</p>
     *
     * <p>A 2D affine has six degrees of freedom and this gesture carries seven — {@code skewY} is the
     * spare, so a decomposed transform always reports {@code skewY = 0} and puts all the shear in
     * {@code skewX}. The composed matrix is identical either way.</p>
     */
    private boolean decomposeMatrix(Transform existing) {
        Matrix4f m = matrixOf(existing);
        Matrix4f local = new Matrix4f()
                .translation(-originX, -originY, 0f)
                .mul(m)
                .translate(originX, originY, 0f);

        float a = local.m00(), b = local.m01();
        float c = local.m10(), d = local.m11();
        if (Math.abs(a * d - b * c) < 1e-6f) {
            // NOT INVERTIBLE -- a zero scale has collapsed the box to a line or a point, and there is
            // no rotation or shear left in it to show. Nothing to open on.
            clearOps();
            return false;
        }

        tx = local.m30();
        ty = local.m31();

        sx = (float) Math.hypot(a, b);
        if (sx != 0f) {
            a /= sx;
            b /= sx;
        }
        float shear = a * c + b * d;
        c -= a * shear;
        d -= b * shear;
        sy = (float) Math.hypot(c, d);
        if (sy != 0f) {
            c /= sy;
            d /= sy;
            shear /= sy;
        }
        if (a * d - b * c < 0f) {
            sx = -sx;
            sy = -sy;
            a = -a;
            b = -b;
        }
        rotation = (float) Math.atan2(b, a);
        skewX = (float) Math.atan(shear);
        skewY = 0f;
        return true;
    }

    /**
     * Whether a {@code skew} carries BOTH shears, which is a parameterisation the handles cannot drive.
     *
     * <p>The fields hold seven numbers for a six-degree-of-freedom matrix and {@code skewY} is the spare,
     * so a canonical reading always puts the shear in {@code skewX} alone. That is not a tidiness rule.
     * Changing {@code skewX} moves the dragged edge along the ROTATION's axis and nothing else — it falls
     * out of the derivative, {@code R · dK/d(tanX) · S}, whatever the pointer is measured through — so the
     * handle only tracks the hand while the rotation op IS the box's apparent orientation. Two large
     * shears cancel part of the rotation, and then it is not.</p>
     *
     * <p>This tool writes {@code skewY} when a side edge is dragged, so it can wind its own output up over
     * several gestures. The scratch document's {@code #hint} was one: {@code rotate(-1.199)
     * skew(-1.189, 1.213) scale(0.344, 0.361)}, which draws square while its rotation op is −68°, so a
     * 30px drag moved the edge 4px — {@code cos²} of the error. Decomposing gives the same matrix back
     * with the rotation the box is really at.</p>
     */
    private static boolean carriesBothShears(Transform transform) {
        for (Transform.Op op : transform.ops()) {
            if (op.kind() == Transform.Kind.SKEW && op.fx() != 0f && op.fy() != 0f) return true;
        }
        return false;
    }

    /** Whether the ops are already in the order the fields compose them in. @see #decomposeMatrix */
    private static boolean inCanonicalOrder(Transform transform) {
        int seen = -1;
        for (Transform.Op op : transform.ops()) {
            int rank = rankOf(op.kind());
            if (rank <= seen) return false;
            seen = rank;
        }
        return true;
    }

    /**
     * Whether {@code transform} is one this gesture can open on — <b>ask before entering the tool</b>.
     *
     * <pre>{@code
     * if (!TransformGesture.canDecompose(node.getStyle().computed().get(TRANSFORM))) return false;
     * }</pre>
     *
     * <p>The five fields describe {@code translate → rotate → skew → scale} composed in that order and
     * nothing else, because a transform is an ORDERED list and {@code rotate scale} is not
     * {@code scale rotate}. Anything out of that order, or repeating a kind either side of another, is a
     * matrix these fields cannot represent — {@link #reset} answers false for it and opens at identity.
     * </p>
     *
     * <p><b>Ask here rather than acting on that false.</b> By the time the box opens, the tool's mode is
     * already on the stack, so refusing then leaves it live with no target and every click on the canvas
     * goes nowhere. @see com.crystalgui.app.uibuilder.BuilderCommands</p>
     */
    public static boolean canDecompose(@Nullable Transform transform) {
        if (transform == null || transform.isIdentity()) return true;
        for (Transform.Op op : transform.ops()) {
            // A ZERO SCALE is the one thing that cannot be opened on: it collapses the box to a line or
            // a point, and no rotation or shear survives in the matrix to be read back. Every other
            // transform decomposes, whatever order it was written in. @see #decomposeMatrix
            if (op.kind() == Transform.Kind.SCALE && (op.fx() == 0f || op.fy() == 0f)) return false;
        }
        return true;
    }

    private static int rankOf(Transform.Kind kind) {
        return switch (kind) {
            case TRANSLATE -> 0;
            case ROTATE -> 1;
            case SKEW -> 2;
            case SCALE -> 3;
        };
    }

    private void clearOps() {
        tx = 0f;
        ty = 0f;
        rotation = 0f;
        skewX = 0f;
        skewY = 0f;
        sx = 1f;
        sy = 1f;
    }

    /** What the gesture currently means, ready to preview or commit. */
    public Transform toTransform() {
        return build(true);
    }

    /**
     * Everything but the scale, as a matrix about the origin.
     *
     * <p>The space a scale handle is measured in: mapping a pointer through its inverse gives the point
     * the scale itself has to produce, with the rotation and skew already accounted for.</p>
     */
    public Matrix4f outer() {
        return matrixOf(build(false));
    }

    /**
     * The rotation alone, about the origin.
     *
     * <p>The frame a skew's pointer delta has to be measured in: a lean runs along the box's OWN axes,
     * so on a rotated box a screen delta has to come back through the rotation before it means anything.
     * Scale and skew are deliberately left out — the lever already carries the scale, and a skew
     * measured through itself is a feedback loop.</p>
     */
    public Matrix4f rotationMatrix() {
        Transform t = rotation == 0f ? Transform.IDENTITY : Transform.IDENTITY.then(
                Transform.Op.rotate(rotation));
        return matrixOf(t);
    }

    /** The whole transform as a matrix about the origin. */
    public Matrix4f matrix() {
        return matrixOf(toTransform());
    }

    private Transform build(boolean withScale) {
        Transform t = Transform.IDENTITY;
        if (tx != 0f || ty != 0f) {
            t = t.then(Transform.Op.translate(LengthPercent.px(tx), LengthPercent.px(ty)));
        }
        if (rotation != 0f) t = t.then(Transform.Op.rotate(rotation));
        if (skewX != 0f || skewY != 0f) t = t.then(Transform.Op.skew(skewX, skewY));
        if (withScale && (sx != 1f || sy != 1f)) t = t.then(Transform.Op.scale(sx, sy));
        return t;
    }

    private Matrix4f matrixOf(Transform t) {
        Matrix4f m = new Matrix4f();
        t.applyTo(m, 0f, 0f, width, height, originX, originY);
        return m;
    }

    /** Where a corner or edge midpoint sits before any transform, in the node's own pixels. */
    public Vector2f corner(Spot spot) {
        return new Vector2f(
                width * (spot.xDirection() + 1) * 0.5f,
                height * (spot.yDirection() + 1) * 0.5f);
    }

    /** The point a scale drag holds still: the opposite corner, or the pivot when scaling about it. */
    public Vector2f anchor(Spot spot, boolean aboutPivot) {
        if (aboutPivot) return new Vector2f(originX, originY);
        return new Vector2f(
                width * (1 - spot.xDirection()) * 0.5f,
                height * (1 - spot.yDirection()) * 0.5f);
    }

    /**
     * Every number this class holds, so a step of the gesture can be put back.
     *
     * <p>A record, so two states compare by value — which is how a drag that moved nothing is told from
     * one that did, without anybody having to remember to say so.</p>
     */
    public record State(float tx, float ty, float sx, float sy, float rotation,
                        float skewX, float skewY, float originX, float originY) {
    }

    public State snapshot() {
        return new State(tx, ty, sx, sy, rotation, skewX, skewY, originX, originY);
    }

    /** Puts a snapshot back, and ends any grip with it: the next drag measures from here. */
    public void restore(State state) {
        tx = state.tx();
        ty = state.ty();
        sx = state.sx();
        sy = state.sy();
        rotation = state.rotation();
        skewX = state.skewX();
        skewY = state.skewY();
        originX = state.originX();
        originY = state.originY();
        grip = Grip.NONE;
    }

    /**
     * Notes what is being dragged and the values every later call measures from.
     *
     * <p>No pointer position: each gesture below states the space it wants and takes either a delta or a
     * point already in that space, so there is nothing here for a press to remember.</p>
     */
    public void press(Grip grip) {
        this.grip = grip;
        pressTx = tx;
        pressTy = ty;
        pressSx = sx;
        pressSy = sy;
        pressRotation = rotation;
        pressSkewX = skewX;
        pressSkewY = skewY;
    }

    public Grip grip() {
        return grip;
    }

    public void release() {
        grip = Grip.NONE;
    }

    /**
     * Scales so the dragged handle follows {@code point}, given in the space {@link #outer()} produces.
     *
     * @param aspect     hold the ratio — Shift
     * @param aboutPivot scale about the pivot rather than the opposite edge — Alt
     */
    public void scaleTo(Vector2f point, boolean aspect, boolean aboutPivot) {
        Spot spot = grip.spot();
        if (spot == null) return;
        Vector2f handle = corner(spot);
        Vector2f anchor = anchor(spot, aboutPivot);

        float newSx = pressSx;
        float newSy = pressSy;
        // SOLVED FOR THE HANDLE LANDING ON THE POINTER *AFTER* THE ANCHOR IS PUT BACK, which is why the
        // press scale appears in it. Scaling about the origin moves the anchor by (pressScale - scale) *
        // (anchor - origin), and holdAnchor undoes exactly that; a formula that ignores the compensation
        // it is about to apply leaves the handle trailing the pointer by that amount.
        if (spot.xDirection() != 0) {
            float span = handle.x - anchor.x;
            if (Math.abs(span) > 1e-4f) {
                newSx = (point.x - originX - (anchor.x - originX) * pressSx) / span;
            }
        }
        if (spot.yDirection() != 0) {
            float span = handle.y - anchor.y;
            if (Math.abs(span) > 1e-4f) {
                newSy = (point.y - originY - (anchor.y - originY) * pressSy) / span;
            }
        }

        // A PROJECTION, not a comparison. Taking the larger of the two factors is discontinuous wherever
        // they cross, so the box jumps between following one axis and the other -- the same defect the
        // resize handles' aspect lock had, and the same fix: average the relative change, which is linear
        // in the pointer and so cannot burst.
        if (aspect && spot.isCorner()) {
            float relativeX = Math.abs(pressSx) < 1e-4f ? 1f : newSx / pressSx;
            float relativeY = Math.abs(pressSy) < 1e-4f ? 1f : newSy / pressSy;
            float uniform = (relativeX + relativeY) * 0.5f;
            newSx = pressSx * uniform;
            newSy = pressSy * uniform;
        }

        sx = clampScale(newSx);
        sy = clampScale(newSy);
        holdAnchor(anchor);
    }

    /**
     * Puts the translate back so {@code anchor} has not moved since the press.
     *
     * @see TransformGesture the note on why the anchor is not the origin
     */
    private void holdAnchor(Vector2f anchor) {
        Vector2f was = scaledPoint(anchor, pressSx, pressSy);
        Vector2f now = scaledPoint(anchor, sx, sy);
        // Carried out through the rotation as a DIRECTION: the translate sits outside it, so a correction
        // measured in the scaled frame has to be rotated before it is added.
        Vector2f delta = transformDirection(rotateSkewMatrix(), was.x - now.x, was.y - now.y);
        tx = pressTx + delta.x;
        ty = pressTy + delta.y;
    }

    private Vector2f scaledPoint(Vector2f point, float scaleX, float scaleY) {
        return new Vector2f(originX + (point.x - originX) * scaleX,
                originY + (point.y - originY) * scaleY);
    }

    private Matrix4f rotateSkewMatrix() {
        Transform t = Transform.IDENTITY;
        if (rotation != 0f) t = t.then(Transform.Op.rotate(rotation));
        if (skewX != 0f || skewY != 0f) t = t.then(Transform.Op.skew(skewX, skewY));
        return matrixOf(t);
    }

    private static Vector2f transformDirection(Matrix4f m, float x, float y) {
        return new Vector2f(m.m00() * x + m.m10() * y, m.m01() * x + m.m11() * y);
    }

    private static float clampScale(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return MIN_SCALE;
        if (Math.abs(value) < MIN_SCALE) return value < 0f ? -MIN_SCALE : MIN_SCALE;
        return value;
    }

    /**
     * Rotates by an angle the caller measured about the pivot; Shift snaps the RESULT to 15 degrees.
     *
     * <p>A delta rather than a point, because the angle is the one quantity that survives the trip from
     * the screen unchanged: the pointer would have to come back through the rotation being edited, and a
     * value that feeds its own input is the feedback loop the resize handles already paid for. Snapping
     * the result and not the delta is what makes a snapped drag hold each step instead of drifting.</p>
     */
    public void rotateBy(float deltaRadians, boolean snap) {
        float next = pressRotation + deltaRadians;
        if (snap) next = Math.round(next / ANGLE_SNAP) * ANGLE_SNAP;
        rotation = next;
    }

    /**
     * Skews along the dragged edge, by a pointer delta in the node's own pixels.
     *
     * <h3>Photoshop's rule, which is also Paint.NET's and Illustrator's</h3>
     *
     * <p><b>The dragged edge follows the pointer and the OPPOSITE edge stays where it is.</b> Everything
     * below falls out of that one sentence, and none of it is what a naive reading of CSS
     * {@code skew()} gives you:</p>
     *
     * <ul>
     *   <li>the lever is the box's FULL extent, not the half from the pivot to the edge. Anchoring the
     *       far edge means the near one carries the whole displacement, so a half lever moved the box
     *       twice as fast as the hand;</li>
     *   <li>{@code skew(ax)} shifts x by {@code tan(ax)·(y - originY)}, so dragging the TOP edge right
     *       needs a NEGATIVE angle — points above the origin move right only when the tangent is
     *       negative. The sign therefore follows which edge is held, which is exactly
     *       {@code spot.yDirection()};</li>
     *   <li>tangents add, angles do not. Continuing a skew already in progress means
     *       {@code atan(tan(was) + delta)}, and adding the angles instead makes the lean creep away from
     *       the pointer as it steepens;</li>
     *   <li>a skew about the origin moves BOTH edges apart, so the translate absorbs the far one — the
     *       same compensation a scale needs, and for the same reason.</li>
     * </ul>
     *
     * <p>A corner is refused by the tool: a free corner is a non-affine distort and {@code Transform} is
     * a matrix.</p>
     *
     * @param aboutPivot Alt — lean about the pivot instead, so both edges travel and neither is held
     */
    public void skewBy(float dx, float dy, boolean aboutPivot) {
        Spot spot = grip.spot();
        if (spot == null) return;
        // SCALED, because the skew is applied to already-scaled coordinates -- scale is the innermost op,
        // so the lever a lean acts over is the box as DRAWN, not as laid out. Left unscaled, a 2x box
        // moved its edge twice as fast as the pointer.
        if (spot.yDirection() != 0 && height > 1e-4f) {
            float extent = height * Math.max(1e-3f, Math.abs(sy));
            float lever = aboutPivot
                    ? Math.max(1f, Math.abs(corner(spot).y - originY) * Math.abs(sy)) : extent;
            skewX = (float) Math.atan(Math.tan(pressSkewX) + spot.yDirection() * dx / lever);
        }
        if (spot.xDirection() != 0 && width > 1e-4f) {
            float extent = width * Math.max(1e-3f, Math.abs(sx));
            float lever = aboutPivot
                    ? Math.max(1f, Math.abs(corner(spot).x - originX) * Math.abs(sx)) : extent;
            skewY = (float) Math.atan(Math.tan(pressSkewY) + spot.xDirection() * dy / lever);
        }
        if (!aboutPivot) holdStill(anchor(spot, false));
    }

    /**
     * Puts the translate back so one point has not moved since the press.
     *
     * <p>{@link #anchor} answers the opposite corner for a corner spot and the opposite EDGE'S MIDPOINT
     * for an edge spot, which is what a skew has to hold: the far edge stays parallel to itself, so
     * holding its centre holds all of it.</p>
     */
    private void holdStill(Vector2f point) {
        Vector2f was = withoutTranslate(pressSx, pressSy, pressRotation, pressSkewX, pressSkewY, point);
        Vector2f now = withoutTranslate(sx, sy, rotation, skewX, skewY, point);
        tx = pressTx + (was.x - now.x);
        ty = pressTy + (was.y - now.y);
    }

    /** Where a point lands under everything but the translate, which is what the translate then fixes. */
    private Vector2f withoutTranslate(float scaleX, float scaleY, float rotate,
                                      float leanX, float leanY, Vector2f point) {
        Transform t = Transform.IDENTITY;
        if (rotate != 0f) t = t.then(Transform.Op.rotate(rotate));
        if (leanX != 0f || leanY != 0f) t = t.then(Transform.Op.skew(leanX, leanY));
        if (scaleX != 1f || scaleY != 1f) t = t.then(Transform.Op.scale(scaleX, scaleY));
        return transformPoint(matrixOf(t), point.x, point.y);
    }

    /**
     * Moves the whole box by a delta from the press, in the node's own pixels.
     *
     * <p>Shift's axis is the caller's to decide — {@code TransformBox} latches it with an
     * {@code AxisLock}, as the out-of-flow move does — so a constrained move arrives with one delta zeroed.</p>
     */
    public void moveBy(float dx, float dy) {
        tx = pressTx + dx;
        ty = pressTy + dy;
    }

    /**
     * Puts the pivot at {@code point}, in the node's own pixels, without moving the box.
     *
     * <p>Changing {@code transform-origin} alone slides everything the transform touches, because the
     * same matrix about a different point is a different matrix. The translate absorbs it, which is what
     * makes the crosshair feel like it is being placed rather than dragging the box along with it.</p>
     */
    public void pivotTo(Vector2f point, boolean snapToAnchors) {
        Vector2f wanted = new Vector2f(point);
        if (snapToAnchors) snapToAnchor(wanted);
        Matrix4f before = matrix();
        originX = wanted.x;
        originY = wanted.y;
        Matrix4f after = matrix();
        Vector2f was = transformPoint(before, wanted.x, wanted.y);
        Vector2f now = transformPoint(after, wanted.x, wanted.y);
        tx += was.x - now.x;
        ty += was.y - now.y;
    }

    /** The nine anchor points, which is where a pivot most often wants to be. */
    private void snapToAnchor(Vector2f point) {
        float tolerance = Math.max(4f, Math.min(width, height) * 0.08f);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                float ax = width * (i + 1) * 0.5f;
                float ay = height * (j + 1) * 0.5f;
                if (Math.abs(point.x - ax) <= tolerance && Math.abs(point.y - ay) <= tolerance) {
                    point.set(ax, ay);
                    return;
                }
            }
        }
    }

    private static Vector2f transformPoint(Matrix4f m, float x, float y) {
        return new Vector2f(m.m00() * x + m.m10() * y + m.m30(),
                m.m01() * x + m.m11() * y + m.m31());
    }

    // ---------------------------------------------------------------- typed in, rather than dragged

    /**
     * Setters for the options bar, which states a value instead of arriving at one.
     *
     * <p>None of them holds anything still. A drag knows which edge it grabbed and can hold the opposite
     * one; a typed number does not, so <b>which point stays put is the reference widget's answer</b> and
     * the caller applies it — see {@code TransformOptionsBar}. Putting an anchor rule in here would give
     * two of them, disagreeing.</p>
     */
    public void setScale(float scaleX, float scaleY) {
        sx = clampScale(scaleX);
        sy = clampScale(scaleY);
    }

    /** @see #setScale */
    public void setRotation(float radians) {
        rotation = radians;
    }

    /** @see #setScale */
    public void setSkew(float radiansX, float radiansY) {
        skewX = radiansX;
        skewY = radiansY;
    }

    /** @see #setScale */
    public void setTranslate(float x, float y) {
        tx = x;
        ty = y;
    }

    /** Moves by a delta, which is how a caller puts a reference point back. @see #setScale */
    public void nudgeTranslate(float dx, float dy) {
        tx += dx;
        ty += dy;
    }

    /** Places the pivot, compensating so the box does not slide. @see #pivotTo */
    public void setOrigin(float x, float y) {
        pivotTo(new Vector2f(x, y), false);
    }

    /** Where a point in the node's own pixels lands under the whole gesture. */
    public Vector2f apply(float x, float y) {
        return transformPoint(matrix(), x, y);
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public float originX() {
        return originX;
    }

    public float originY() {
        return originY;
    }

    public float rotation() {
        return rotation;
    }

    public float scaleX() {
        return sx;
    }

    public float scaleY() {
        return sy;
    }

    public float translateX() {
        return tx;
    }

    public float translateY() {
        return ty;
    }

    public float skewXRadians() {
        return skewX;
    }

    public float skewYRadians() {
        return skewY;
    }

    /** Whether anything would actually be written. */
    public boolean isIdentity() {
        return toTransform().isIdentity();
    }
}
