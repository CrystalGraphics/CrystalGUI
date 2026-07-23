package com.crystalgui.render.texture;

import com.crystalgui.render.CgUiPaintContext;

/**
 * Generic, type-agnostic cross-fade between two {@link CgUiDrawable}s — used by
 * {@code TextureProperty}'s interpolator so texture-typed style values (background, etc.)
 * can transition smoothly.
 *
 * <p>Mirrors how browsers actually cross-fade images: not by touching either drawable's own
 * tint/alpha, but by drawing {@code from} as a normal opaque base layer, then drawing
 * {@code to} on top at a fractional <i>layer</i> opacity via
 * {@link CgUiPaintContext#withLayerOpacity(float, Runnable)} — the same technique behind the
 * standard "two stacked layers, top one's opacity animated" web trick. Works uniformly for
 * any drawable pair (quad, sprite, SDF rounded rect, ...) since the mechanism lives on the
 * paint context, not on any one material.</p>
 */
public final class CgUiCrossFade implements CgUiDrawable {

    private final CgUiDrawable from;
    private final CgUiDrawable to;
    private final float t;

    public CgUiCrossFade(CgUiDrawable from, CgUiDrawable to, float t) {
        this.from = from;
        this.to = to;
        this.t = t;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        from.draw(ctx, mouseX, mouseY, x, y, width, height);
        ctx.withLayerOpacity(t, () -> to.draw(ctx, mouseX, mouseY, x, y, width, height));
    }
}
