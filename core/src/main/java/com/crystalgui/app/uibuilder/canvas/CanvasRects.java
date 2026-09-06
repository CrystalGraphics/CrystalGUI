package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * One node's border box, in an overlay's own space.
 *
 * <pre>{@code
 * float[] r = CanvasRects.of(node, overlay);
 * if (r != null) ctx.fillRect(r[0], r[1], r[2], r[3], colour);
 * }</pre>
 *
 * <p><b>Through the matrices, never by subtracting positions.</b> {@code Box.x()} is parent-relative, so
 * {@code target.x() - overlay.x()} is meaningful only when the two share a parent and is silently wrong
 * otherwise — wrong by an amount that depends on how deep in the tree they are. Between a canvas overlay
 * and a node inside an artboard there are a pan, a zoom and the artboard's own scale, and this carries
 * all three because it transforms two corners rather than translating one.</p>
 *
 * <p>Null whenever there is nothing laid out to measure. That is the ordinary state, not a failure: a
 * node that is hidden, frozen, {@code display: none} or momentarily out of the tree has no box, and a
 * canvas overlay is pointed at exactly the kind of tree that does all four while being looked at.</p>
 */
public final class CanvasRects {

    private CanvasRects() {
    }

    /** {@code target}'s border box as {x, y, width, height} in {@code space}'s own coordinates. */
    @Nullable
    public static float[] of(@Nullable UIElement target, @Nullable UIElement space) {
        return of(target == null ? null : target.box(), space == null ? null : space.box());
    }

    /** @see #of(UIElement, UIElement) */
    @Nullable
    public static float[] of(@Nullable Box target, @Nullable Box space) {
        if (target == null || space == null) return null;
        Vector2f topLeft = corner(target, space, 0f, 0f);
        Vector2f bottomRight = corner(target, space, target.width(), target.height());
        return new float[]{
                topLeft.x, topLeft.y,
                Math.max(0f, bottomRight.x - topLeft.x),
                Math.max(0f, bottomRight.y - topLeft.y)};
    }

    private static Vector2f corner(Box target, Box space, float localX, float localY) {
        Vector2f world = Transform2D.apply(target.localToWorld(), localX, localY);
        return Transform2D.apply(space.worldToLocal(), world.x, world.y);
    }

    /** Draws {@code thickness} px of outline just inside a rectangle, as four fills. */
    public static void outline(CgUiPaintContext ctx, float[] rect, float thickness, int argb) {
        if (rect == null || rect[2] <= 0f || rect[3] <= 0f) return;
        float x = rect[0];
        float y = rect[1];
        float width = rect[2];
        float height = rect[3];
        float t = Math.min(thickness, Math.min(width, height) * 0.5f);
        ctx.fillRect(x, y, width, t, argb);
        ctx.fillRect(x, y + height - t, width, t, argb);
        ctx.fillRect(x, y + t, t, height - t - t, argb);
        ctx.fillRect(x + width - t, y + t, t, height - t - t, argb);
    }
}
