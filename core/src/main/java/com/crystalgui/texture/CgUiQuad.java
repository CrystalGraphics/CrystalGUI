package com.crystalgui.texture;

import com.crystalgui.render.CgUiPaintContext;

/**
 * Flat solid-color fill.
 *
 * <p>Draws through {@link CgUiPaintContext#fillRect}, which binds the shared 1x1 white
 * texture — so a run of {@code CguiColorRectTexture} elements never triggers a texture
 * rebind against each other, only against the element before/after them that uses a
 * different texture.</p>
 */
public final class CgUiQuad implements CgUiDrawable {

    private final int colorArgb;

    public CgUiQuad(int colorArgb) {
        this.colorArgb = colorArgb;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        ctx.fillRect(x, y, width, height,
                //CgUiPaintContext.multiplyArgb(colorArgb, tintArgb)
                colorArgb // TODO replace with multiplication code?
        );
    }
}
