package com.crystalgui.ui;

import com.crystalgui.core.render.CgUiPaintContext;

/**
 * Paintable node contract for the UI draw traversal.
 *
 * <p>Elements that produce visual output implement this interface so that
 * {@link UIContainer}'s render traversal can invoke their painting logic.
 * The traversal walks the DOM tree in document order, calling
 * {@link #draw(CgUiPaintContext)} on each visible {@code Drawable} node.</p>
 */
public interface CgUiDrawable {

    /**
     * Paints this element into the draw-list paint context.
     *
     * @param ctx the UI paint context providing draw-list recording access
     */
    void draw(CgUiPaintContext ctx);
}
