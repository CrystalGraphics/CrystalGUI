package com.crystalgui.ui.elements.canvas;

/**
 * An axis-aligned rectangle in a {@link CanvasView}'s <b>world</b> space — the untransformed
 * coordinate system nodes are placed in, before pan and zoom.
 *
 * <p>A value type rather than a JOML {@code Vector4f} because every consumer here reads it by name:
 * culling asks {@link #intersects}, fit-to-content asks {@link #union}, and 6.2.4's marquee will ask
 * {@link #contains}. Four anonymous floats would make each of those a comment.</p>
 *
 * <p>Width and height are never negative — {@link #of} normalises, so a rect built from a drag that
 * went up-and-left is still a rect. That is the one thing a caller reliably gets wrong when a record
 * merely stores what it was handed.</p>
 */
public record WorldRect(float x, float y, float width, float height) {

    /** From two corners, in either order. */
    public static WorldRect of(float x0, float y0, float x1, float y1) {
        return new WorldRect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public float centerX() {
        return x + width * 0.5f;
    }

    public float centerY() {
        return y + height * 0.5f;
    }

    public boolean isEmpty() {
        return width <= 0f || height <= 0f;
    }

    /**
     * Overlap test, <b>inclusive of touching edges</b>.
     *
     * <p>Inclusive on purpose: this decides culling, and a node whose edge lands exactly on the
     * viewport boundary has a visible pixel column. Erring toward "visible" costs one node's worth of
     * paint; erring the other way makes something vanish a pixel early, which reads as a rendering
     * bug rather than as an off-by-one.</p>
     */
    public boolean intersects(WorldRect other) {
        return x <= other.right() && other.x <= right()
                && y <= other.bottom() && other.y <= bottom();
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= right() && py >= y && py <= bottom();
    }

    /** True when {@code other} lies entirely inside this one. */
    public boolean contains(WorldRect other) {
        return other.x >= x && other.right() <= right() && other.y >= y && other.bottom() <= bottom();
    }

    /** Grown by {@code amount} on all four sides. Negative shrinks, clamped at zero size. */
    public WorldRect expand(float amount) {
        float w = Math.max(0f, width + 2f * amount);
        float h = Math.max(0f, height + 2f * amount);
        return new WorldRect(x - amount, y - amount, w, h);
    }

    /** The smallest rect containing both. */
    public WorldRect union(WorldRect other) {
        float minX = Math.min(x, other.x), minY = Math.min(y, other.y);
        float maxX = Math.max(right(), other.right()), maxY = Math.max(bottom(), other.bottom());
        return new WorldRect(minX, minY, maxX - minX, maxY - minY);
    }
}
