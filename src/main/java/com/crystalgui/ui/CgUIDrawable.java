package com.crystalgui.ui;

import com.crystalgui.core.render.CgUIRenderContext;

/**
 * Paintable node contract for the UI draw traversal.
 *
 * <p>Elements that produce visual output implement this interface so that
 * {@link UIContainer}'s render traversal can invoke their painting logic.
 * The traversal walks the DOM tree in document order, calling
 * {@link #draw(CgUIRenderContext)} on each visible {@code Drawable} node.</p>
 *
 * <p>Implementors paint into the appropriate layer via the context's typed
 * getters (e.g. {@code ctx.solid()}, {@code ctx.panel()}).</p>
 */
public interface CgUIDrawable {

    /**
     * Paints this element into the given render context.
     *
     * @param ctx the UI render context providing typed layer access and scissor stack
     */
    void draw(CgUIRenderContext ctx);
}
