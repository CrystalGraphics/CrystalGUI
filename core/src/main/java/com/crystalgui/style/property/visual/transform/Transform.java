package com.crystalgui.style.property.visual.transform;

import com.crystalgui.style.property.visual.border.LengthPercent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A per-element 2D affine — CSS's {@code transform} — applied on top of whatever layout computed,
 * without disturbing it.
 *
 * <p>Layout-free by construction: Taffy never sees this, so transforming an element cannot reflow its
 * siblings. That is what makes it usable for a zoomable canvas, and it is how LDLib2's node graph zooms
 * a whole subtree — by putting a scale on one container rather than rescaling the window.</p>
 *
 * <h3>Why an ordered list rather than translate/scale/rotate fields</h3>
 * <p>CSS composes transform functions left-to-right as matrix multiplication, so
 * {@code translate(10px) scale(2)} and {@code scale(2) translate(10px)} are different transforms — the
 * first translates by 10 and then scales the already-translated space, the second scales first so the
 * translate lands at 20. A fixed field-per-function decomposition cannot represent that distinction at
 * all. Storing the functions in the order they were written is the only faithful reading, and it is
 * what makes {@code transform:} in a stylesheet mean what an author expects.</p>
 *
 * <h3>Origin</h3>
 * <p>Deliberately NOT stored here. The origin is CSS's {@code transform-origin}, a separate cascading
 * property ({@code transform-origin-x}/{@code -y}), so it can be themed and transitioned on its own.
 * {@link #applyTo} takes it already resolved to pixels.</p>
 *
 * <h3>Known divergences from CSS</h3>
 * <ul>
 *   <li>{@code matrix()} is unsupported. Everything else 2D is: {@code translate}, {@code scale},
 *       {@code rotate}, {@code skew}, and the {@code X}/{@code Y} variants of each.</li>
 *   <li>The axis variants <b>collapse</b> into their two-argument form at parse time —
 *       {@code translateX(5px)} becomes {@code translate(5px, 0)}. CSS keeps them distinct so that
 *       interpolation only matches identical function names; collapsing means {@code translateX(10px)}
 *       can smoothly interpolate against {@code translate(20px, 5px)} instead of snapping, which is
 *       strictly the better behaviour and the only place this leans away from the spec on purpose.</li>
 * </ul>
 *
 * <p>Immutable. Build with {@link #IDENTITY} and {@link #then}, or the {@link #scale}/{@link #translate}
 * shorthands; {@link #isIdentity()} is the fast path callers check before doing any matrix work at all.</p>
 */
public final class Transform {

    /** No-op. The default for every element, and the value {@link #isIdentity()} short-circuits on. */
    public static final Transform IDENTITY = new Transform(Collections.emptyList());

    /** Which CSS function an {@link Op} is, and therefore which of its fields carry meaning. */
    public enum Kind {
        /** Uses {@link Op#lx}/{@link Op#ly}. Percentages resolve against the element's own box. */
        TRANSLATE,
        /** Uses {@link Op#fx}/{@link Op#fy} as unitless multipliers. */
        SCALE,
        /** Uses {@link Op#fx} as radians, clockwise. */
        ROTATE,
        /** Uses {@link Op#fx}/{@link Op#fy} as radians of shear along X and Y. */
        SKEW
    }

    /**
     * One CSS transform function. Only the fields its {@link Kind} documents are meaningful; the rest
     * hold the identity value for that slot so {@code equals} stays well-defined.
     */
    public record Op(Kind kind, LengthPercent lx, LengthPercent ly, float fx, float fy) {

        public static Op translate(LengthPercent x, LengthPercent y) {
            return new Op(Kind.TRANSLATE, x, y, 0f, 0f);
        }

        public static Op scale(float x, float y) {
            return new Op(Kind.SCALE, LengthPercent.ZERO, LengthPercent.ZERO, x, y);
        }

        /** @param radians clockwise, since Y grows downward here. */
        public static Op rotate(float radians) {
            return new Op(Kind.ROTATE, LengthPercent.ZERO, LengthPercent.ZERO, radians, 0f);
        }

        /** @param radiansX shear along X; {@code radiansY} along Y. */
        public static Op skew(float radiansX, float radiansY) {
            return new Op(Kind.SKEW, LengthPercent.ZERO, LengthPercent.ZERO, radiansX, radiansY);
        }
    }

    private final List<Op> ops;

    private Transform(List<Op> ops) {
        this.ops = ops;
    }

    /** Wraps an already-ordered function list. The list is defensively copied. */
    public static Transform of(List<Op> ops) {
        if (ops == null || ops.isEmpty()) return IDENTITY;
        return new Transform(Collections.unmodifiableList(new ArrayList<>(ops)));
    }

    public static Transform of(Op... ops) {
        return of(Arrays.asList(ops));
    }

    public static Transform scale(float uniform) {
        return of(Op.scale(uniform, uniform));
    }

    public static Transform scale(float x, float y) {
        return of(Op.scale(x, y));
    }

    public static Transform translate(float x, float y) {
        return of(Op.translate(LengthPercent.px(x), LengthPercent.px(y)));
    }

    /** @param radians clockwise, since Y grows downward here. */
    public static Transform rotate(float radians) {
        return of(Op.rotate(radians));
    }

    /** Appends {@code op} after everything already here — i.e. CSS's left-to-right order. */
    public Transform then(Op op) {
        List<Op> next = new ArrayList<>(ops.size() + 1);
        next.addAll(ops);
        next.add(op);
        return new Transform(Collections.unmodifiableList(next));
    }

    public Transform withTranslate(float x, float y) {
        return then(Op.translate(LengthPercent.px(x), LengthPercent.px(y)));
    }

    public Transform withScale(float x, float y) {
        return then(Op.scale(x, y));
    }

    /** @param radians clockwise, since Y grows downward here. */
    public Transform withRotation(float radians) {
        return then(Op.rotate(radians));
    }

    /** The functions in the order they were written. Unmodifiable. */
    public List<Op> ops() {
        return ops;
    }

    /**
     * True when this transform would change nothing — the check every caller makes before touching a
     * matrix or pushing a pose, so an untransformed tree (which is nearly all of them) pays nothing.
     */
    public boolean isIdentity() {
        return ops.isEmpty();
    }

    /**
     * Post-multiplies this transform into {@code target}, about the given already-resolved origin.
     *
     * <p><b>Single definition, used by both consumers.</b> {@code UIElement} calls this once for the
     * transform chain that hit-testing inverts, and once for the {@code PoseStack} that rendering
     * uses. They must produce the identical matrix or a click would land somewhere other than what
     * the user sees — which is exactly the class of bug that made the root transform authoritative in
     * {@code UIWindow} rather than letting the pose and the cache each derive their own scale.</p>
     *
     * <p>Each JOML call here post-multiplies ({@code M = M · T}), so walking {@link #ops} in order
     * <i>is</i> CSS's left-to-right composition — no reversal, no accumulation buffer.</p>
     *
     * @param x         the element's absolute x, in the space {@code target} is currently in
     * @param y         the element's absolute y
     * @param width     the element's width, for resolving percentage translations
     * @param height    the element's height, likewise
     * @param originPxX {@code transform-origin-x} resolved to pixels, relative to the element's own box
     * @param originPxY {@code transform-origin-y} resolved to pixels
     * @return {@code target}, for chaining
     */
    public Matrix4f applyTo(Matrix4f target, float x, float y, float width, float height,
                            float originPxX, float originPxY) {
        if (ops.isEmpty()) return target;
        float px = x + originPxX;
        float py = y + originPxY;
        target.translate(px, py, 0f);
        for (Op op : ops) {
            switch (op.kind()) {
                case TRANSLATE -> target.translate(op.lx().resolve(width), op.ly().resolve(height), 0f);
                case SCALE -> target.scale(op.fx(), op.fy(), 1f);
                case ROTATE -> target.rotateZ(op.fx());
                case SKEW -> target.mul(shear(op.fx(), op.fy()));
            }
        }
        target.translate(-px, -py, 0f);
        return target;
    }

    /**
     * CSS's {@code skew(ax, ay)} matrix: {@code [1 tan(ax); tan(ay) 1]}.
     *
     * <p>Written out by hand because JOML has no 2D shear. Note the cross assignment, and that JOML
     * indices are {@code m<column><row>}: {@code ax} — the angle "along X" — goes in {@code m10}, the
     * cell that feeds <b>x</b> from y, so {@code x' = x + tan(ax)·y}. Transposing the two produces a
     * shear that looks plausible and is wrong, which is why there is a test for it.</p>
     */
    private static Matrix4f shear(float radiansX, float radiansY) {
        Matrix4f m = new Matrix4f();
        m.m10((float) Math.tan(radiansX));
        m.m01((float) Math.tan(radiansY));
        return m;
    }

    /** Value equality — the cascade uses it to skip invalidating a whole subtree's matrices when
     * nothing actually changed. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transform other)) return false;
        return ops.equals(other.ops);
    }

    @Override
    public int hashCode() {
        return ops.hashCode();
    }

    @Override
    public String toString() {
        if (ops.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
            if (sb.length() > 0) sb.append(' ');
            switch (op.kind()) {
                case TRANSLATE -> sb.append("translate(").append(op.lx()).append(", ").append(op.ly()).append(')');
                case SCALE -> sb.append("scale(").append(op.fx()).append(", ").append(op.fy()).append(')');
                case ROTATE -> sb.append("rotate(").append(op.fx()).append("rad)");
                case SKEW -> sb.append("skew(").append(op.fx()).append("rad, ").append(op.fy()).append("rad)");
            }
        }
        return sb.toString();
    }
}
