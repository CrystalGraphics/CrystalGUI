package com.crystalgui.ui.box;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Where one box takes the pointer, frozen: its border box under the matrix it was composed with, and its rounded
 * corners. Tests a point exactly as {@link Box#hitTest} tests that box, with no tree to read.
 *
 * <pre>{@code
 * HitShape shape = box.hitShape();         // on the document's thread, after layout
 * boolean on = shape.contains(x, y);       // on any thread, any time after: world (surface) coordinates
 * }</pre>
 *
 * <p>Only the box itself: what it hosts is not in it. A copy, so a later layout does not move it.</p>
 */
public final class HitShape {

    private final Matrix4f worldToLocal;
    private final float width;
    private final float height;
    /** {@code rx, ry} per corner, from the top left clockwise; all zero for a square box. */
    private final float[] radii;

    HitShape(Matrix4f worldToLocal, float width, float height, float[] radii) {
        this.worldToLocal = new Matrix4f(worldToLocal);
        this.width = width;
        this.height = height;
        this.radii = radii;
    }

    /** Whether the world point falls on the box: inside its border box and its rounded corners. */
    public boolean contains(float worldX, float worldY) {
        Vector4f p = new Vector4f(worldX, worldY, 0f, 1f);
        worldToLocal.transform(p);
        if (p.x < 0f || p.y < 0f || p.x >= width || p.y >= height) return false;
        return Box.insideCorners(p.x, p.y, width, height, radii);
    }

    @Override
    public String toString() {
        return "HitShape(" + width + "x" + height + ")";
    }
}
