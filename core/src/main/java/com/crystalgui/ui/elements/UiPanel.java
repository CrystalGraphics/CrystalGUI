package com.crystalgui.ui.elements;

import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.ui.UIElement;
import lombok.Getter;
import lombok.Setter;

/**
 * A rectangular panel element that draws a filled rectangle using the draw-list path.
 *
 * <p>Reads its position and size from the Taffy layout result (via {@code layoutState.getLayoutBox()}).
 * The draw state must be provided externally and cached — it is never constructed in the draw hot path.</p>
 */
public class UiPanel extends UIElement {
    
    @Getter @Setter
    private int color;

    /**
     * Creates a panel using the global {@link com.crystalgui.core.render.CgUiRuntime#solidFill()} draw state.
     *
     * @param color packed RGBA color (0xRRGGBBAA)
     */
    public UiPanel(int color) {
        this.color = color;
    }

    @Override
    public void draw(CgUiPaintContext ctx) {
        UiRect box = getLayoutState().getLayoutBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0) return;
        ctx.fillRect(box.getX(), box.getY(), box.getWidth(), box.getHeight(), color);
    }
}
