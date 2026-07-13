package com.crystalgui.rewrite.texture;

import com.crystalgui.rewrite.render.CgUiPaintContext;

/**
 * Pluggable "how do I paint myself into a rect" strategy.
 *
 * <p>Assigned to {@code UIElement.background}/{@code overlay}. Each implementation
 * routes to CgGui's Track A immediate-mode primitives (see {@link CgUiPaintContext})
 * — there is no batching or deferred submission anywhere in this call path. A call
 * to {@link #draw} is expected to issue exactly one GPU draw call (or zero, for a
 * fully-transparent tint) before returning.</p>
 */
public interface ICgUiDrawable {

    /**
     * Paints this texture into the given rect, immediately.
     *
     * @param ctx      the active paint context for this frame
     * @param x        left edge, in screen pixels
     * @param y        top edge, in screen pixels
     * @param width    rect width, in screen pixels
     * @param height   rect height, in screen pixels
     * @param tintArgb packed 0xAARRGGBB tint (already includes the element's opacity
     *                 multiplied in — see {@code UIElement.paintSelf}); implementations
     *                 should multiply this into their own color rather than ignoring it
     */
    void draw(CgUiPaintContext ctx, float x, float y, float width, float height, int tintArgb);
}
