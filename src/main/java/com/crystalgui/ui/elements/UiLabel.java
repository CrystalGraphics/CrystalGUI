package com.crystalgui.ui.elements;

import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.ui.UIElement;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import lombok.Getter;
import lombok.Setter;

/**
 * Simple text label element for exercising the real CrystalGUI text path.
 * Draws one line of text at the element's layout box origin.
 */
public class UiLabel extends UIElement {

    // Temporary layout hack: horizontal padding and approximate vertical centering.
    // These must be replaced with proper text measurement once CgFontFamily exposes metrics.
    private static final float TEXT_PADDING_LEFT = 8.0f;
    private static final float TEXT_BASELINE_FACTOR = 0.72f;

    @Getter @Setter
    private CgFontFamily fontFamily;

    @Getter @Setter
    private String text;

    @Getter @Setter
    private int color = 0xFFFFFFFF;

    public UiLabel(CgFontFamily fontFamily, String text, int color) {
        this.fontFamily = fontFamily;
        this.text = text;
        this.color = color;
    }

    @Override
    public void draw(CgUiPaintContext ctx) {
        if (fontFamily == null || text == null || text.isEmpty() || !ctx.hasTextServices()) {
            return;
        }

        UiRect box = getLayoutState().getLayoutBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0) {
            return;
        }

        ctx.drawText(fontFamily, text, box.getX() + TEXT_PADDING_LEFT,
                     box.getY() + box.getHeight() * TEXT_BASELINE_FACTOR, color);
    }
}
