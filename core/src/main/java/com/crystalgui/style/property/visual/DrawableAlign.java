package com.crystalgui.style.property.visual;

/**
 * Where a fitted drawable sits within its layer box when it doesn't exactly fill it — a keyword
 * subset of CSS {@code object-position}.
 *
 * <p>Inert under {@link DrawableFit#FILL} (nothing left over to distribute). Relevant for
 * {@code CONTAIN}/{@code NONE} (drawable smaller than the box) and {@code COVER} (larger — the
 * factors then choose which part overflows).</p>
 */
public enum DrawableAlign {
    TOP_LEFT(0f, 0f),
    TOP(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    LEFT(0f, 0.5f),
    /** CSS's own {@code object-position} default. */
    CENTER(0.5f, 0.5f),
    RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f);

    private final float xFactor;
    private final float yFactor;

    DrawableAlign(float xFactor, float yFactor) {
        this.xFactor = xFactor;
        this.yFactor = yFactor;
    }

    /** Fraction of the horizontal leftover space placed before the drawable. */
    public float xFactor() {
        return xFactor;
    }

    /** Fraction of the vertical leftover space placed before the drawable. */
    public float yFactor() {
        return yFactor;
    }
}
