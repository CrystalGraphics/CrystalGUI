package com.crystalgui.ui;

import org.joml.Matrix4f;

/**
 * A per-element 2D affine — translate, scale and rotation about a normalised pivot — applied on top of
 * whatever layout computed, without disturbing it.
 *
 * <p>Layout-free by construction: Taffy never sees this, so transforming an element cannot reflow its
 * siblings. Matches CSS's {@code transform}/{@code transform-origin} semantics, and LDLib2's
 * {@code Transform2D}, which is how its node graph zooms a whole subtree by putting a scale on one
 * container rather than rescaling the window.</p>
 *
 * <h3>Why the pivot is normalised</h3>
 * <p>{@code (0.5, 0.5)} means "the middle of this element" whatever size it currently is, so a scale
 * or rotation stays centred as the element resizes — the same reason CSS's {@code transform-origin}
 * defaults to {@code 50% 50%}. Absolute pivots would need updating on every relayout.</p>
 *
 * <h3>Composition order</h3>
 * <p>{@code T(pivot) · T(translate) · R · S · T(-pivot)} — move the pivot to the origin, rotate and
 * scale there, move back. Scale is applied innermost so it never moves the pivot, and translate is
 * outermost so it is expressed in the parent's units rather than being scaled by this element's own
 * scale (CSS orders it the same way).</p>
 *
 * <p>Immutable. Build with {@link #IDENTITY} and the {@code with*} methods; {@link #isIdentity()} is
 * the fast path callers check before doing any matrix work at all.</p>
 */
public final class UITransform {

    /** No-op. The default for every element, and the value {@link #isIdentity()} short-circuits on. */
    public static final UITransform IDENTITY = new UITransform(0f, 0f, 1f, 1f, 0f, 0.5f, 0.5f);

    private final float translateX, translateY;
    private final float scaleX, scaleY;
    /** Radians, clockwise in this coordinate system (Y grows downward). */
    private final float rotation;
    /** 0..1 of the element's own box. {@code (0.5, 0.5)} is its centre. */
    private final float pivotX, pivotY;

    private UITransform(float translateX, float translateY, float scaleX, float scaleY,
                        float rotation, float pivotX, float pivotY) {
        this.translateX = translateX;
        this.translateY = translateY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotation = rotation;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
    }

    public static UITransform scale(float uniform) {
        return IDENTITY.withScale(uniform, uniform);
    }

    public static UITransform translate(float x, float y) {
        return IDENTITY.withTranslate(x, y);
    }

    public UITransform withTranslate(float x, float y) {
        return new UITransform(x, y, scaleX, scaleY, rotation, pivotX, pivotY);
    }

    public UITransform withScale(float x, float y) {
        return new UITransform(translateX, translateY, x, y, rotation, pivotX, pivotY);
    }

    /** @param radians clockwise, since Y grows downward here. */
    public UITransform withRotation(float radians) {
        return new UITransform(translateX, translateY, scaleX, scaleY, radians, pivotX, pivotY);
    }

    /** @param x 0..1 across the element's own width; {@code y} likewise down its height. */
    public UITransform withPivot(float x, float y) {
        return new UITransform(translateX, translateY, scaleX, scaleY, rotation, x, y);
    }

    public float translateX() { return translateX; }
    public float translateY() { return translateY; }
    public float scaleX()     { return scaleX; }
    public float scaleY()     { return scaleY; }
    public float rotation()   { return rotation; }
    public float pivotX()     { return pivotX; }
    public float pivotY()     { return pivotY; }

    /**
     * True when this transform would change nothing — the check every caller makes before touching a
     * matrix or pushing a pose, so an untransformed tree (which is nearly all of them) pays nothing.
     */
    public boolean isIdentity() {
        return translateX == 0f && translateY == 0f
                && scaleX == 1f && scaleY == 1f
                && rotation == 0f;
    }

    /**
     * Post-multiplies this transform into {@code target}, resolving the pivot against the given box.
     *
     * <p><b>Single definition, used by both consumers.</b> {@code UIElement} calls this once for the
     * transform chain that hit-testing inverts, and once for the {@code PoseStack} that rendering
     * uses. They must produce the identical matrix or a click would land somewhere other than what
     * the user sees — which is exactly the class of bug that made the root transform authoritative in
     * {@code UIWindow} rather than letting the pose and the cache each derive their own scale.</p>
     *
     * @param x      the element's absolute x, in the space {@code target} is currently in
     * @param y      the element's absolute y
     * @param width  the element's width, for resolving {@link #pivotX}
     * @param height the element's height, for resolving {@link #pivotY}
     * @return {@code target}, for chaining
     */
    public Matrix4f applyTo(Matrix4f target, float x, float y, float width, float height) {
        if (isIdentity()) return target;
        float px = x + width * pivotX;
        float py = y + height * pivotY;
        target.translate(px, py, 0f);
        if (translateX != 0f || translateY != 0f) target.translate(translateX, translateY, 0f);
        if (rotation != 0f) target.rotateZ(rotation);
        if (scaleX != 1f || scaleY != 1f) target.scale(scaleX, scaleY, 1f);
        target.translate(-px, -py, 0f);
        return target;
    }

    /** Value equality — {@code UIElement.setTransform} uses it to skip invalidating a whole subtree's
     * matrices when nothing actually changed. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UITransform other)) return false;
        return Float.compare(translateX, other.translateX) == 0
                && Float.compare(translateY, other.translateY) == 0
                && Float.compare(scaleX, other.scaleX) == 0
                && Float.compare(scaleY, other.scaleY) == 0
                && Float.compare(rotation, other.rotation) == 0
                && Float.compare(pivotX, other.pivotX) == 0
                && Float.compare(pivotY, other.pivotY) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(translateX);
        h = 31 * h + Float.hashCode(translateY);
        h = 31 * h + Float.hashCode(scaleX);
        h = 31 * h + Float.hashCode(scaleY);
        h = 31 * h + Float.hashCode(rotation);
        h = 31 * h + Float.hashCode(pivotX);
        h = 31 * h + Float.hashCode(pivotY);
        return h;
    }

    @Override
    public String toString() {
        return "UITransform[translate=(" + translateX + ", " + translateY + "), scale=(" + scaleX
                + ", " + scaleY + "), rotation=" + rotation + ", pivot=(" + pivotX + ", " + pivotY + ")]";
    }
}
