package com.crystalgui.widget.surface.overlay;

import java.util.Collection;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.widget.surface.Surface;

/**
 * The rectangles an overlay draws from, read after layout.
 *
 * <pre>{@code
 * WorldRect around = ctx.geometry().boundsOf(ctx.selection().items());
 * Vector2f corner = ctx.geometry().toViewport(around.x(), around.y());
 * }</pre>
 *
 * <p>Valid where geometry is: inside an overlay's paint and in an {@code afterLayout} hook. Asked before
 * this frame's layout it answers the last one, which for a selection ring is a frame of lag and for a
 * placement is a wrong answer.</p>
 */
public final class Geometry {

    private final Surface surface;

    public Geometry(Surface surface) {
        this.surface = surface;
    }

    /** Where one item is, in world units. */
    public WorldRect boundsOf(UIElement item) {
        return surface.boundsOf(item);
    }

    /** One rectangle around all of them, or null when there are none. */
    @Nullable
    public WorldRect boundsOf(Collection<UIElement> items) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (UIElement item : items) {
            WorldRect at = surface.boundsOf(item);
            minX = Math.min(minX, at.x());
            minY = Math.min(minY, at.y());
            maxX = Math.max(maxX, at.x() + at.width());
            maxY = Math.max(maxY, at.y() + at.height());
            any = true;
        }
        return any ? WorldRect.of(minX, minY, maxX, maxY) : null;
    }

    /** World units to the surface's own space — where a pinned overlay is positioned. */
    public Vector2f toViewport(float worldX, float worldY) {
        return surface.toViewport(worldX, worldY);
    }

    /** How many surface pixels one world unit is. */
    public float zoom() {
        return surface.zoom();
    }
}
