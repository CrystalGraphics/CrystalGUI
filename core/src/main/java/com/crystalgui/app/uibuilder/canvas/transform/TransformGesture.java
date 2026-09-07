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

        int seen = -1;
        for (Transform.Op op : existing.ops()) {
            int rank = rankOf(op.kind());
            if (rank <= seen) {
                clearOps();
                return false;
            }
            seen = rank;
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
     * Moves the whole box by a pointer delta in the node's own pixels.
     *
     * @param constrainToAxis Shift — keep the move on whichever axis the hand committed to
     */
    public void moveBy(float dx, float dy, boolean constrainToAxis) {
        if (constrainToAxis) {
            // MEASURED FROM THE WHOLE GESTURE, not the last frame: on a slow diagonal the per-frame
            // delta crosses back and forth over the diagonal and the box flickers between the two axes.
            // The same rule the out-of-flow move already uses.
            if (Math.abs(dx) >= Math.abs(dy)) dy = 0f;
            else dx = 0f;
        }
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
