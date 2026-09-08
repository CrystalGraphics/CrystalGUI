package com.crystalgui.render.texture;

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

    public int getColorArgb() {
        return colorArgb;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        ctx.fillRect(x, y, width, height, ArgbMath.multiply(colorArgb, ctx.getColor()));
    }

    /**
     * <b>By its colour</b>, because that is all it is.
     *
     * <p>Without this two quads holding the same ARGB were different values, and that is load-bearing in
     * two places rather than merely tidy. {@code ElementStyle.replaceOrPutCandidate} discards a pushed
     * value equal to the one already there — which is what stops a repeated write re-entering the
     * cascade and retargeting any transition in flight — and it could never discard a background. And a
     * background written back out as CSS and read in again produced something that compared unequal to
     * what it came from, so nothing could check that the two halves agreed.</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof CgUiQuad other && colorArgb == other.colorArgb;
    }

    @Override
    public int hashCode() {
        return colorArgb;
    }

    @Override
    public String toString() {
        return "CgUiQuad(" + ArgbMath.toCss(colorArgb) + ")";
    }
}
