package com.crystalgui.render.texture;

import com.crystalgui.render.CgUiPaintContext;

/**
 * Generic, type-agnostic cross-fade between two {@link CgUiDrawable}s — used by
 * {@code TextureProperty}'s interpolator so texture-typed style values (background, etc.)
 * can transition smoothly.
 *
 * <p>Mirrors how browsers actually cross-fade images: not by touching either drawable's own
 * tint/alpha, but by stacking two layers and animating <i>both</i> simultaneously — outgoing
 * {@code from} fades {@code 1 → 0}, incoming {@code to} fades {@code 0 → 1} — via
 * {@link CgUiPaintContext#withLayerOpacity(float, Runnable)}, the standard technique behind web
 * carousels/image hover-swaps. (This does inherit that technique's well-known artifact: a slight
 * extra-transparency dip around the 50% mark, since two half-opaque layers stacked don't sum to
 * full opacity — real browsers have the same limitation with the plain two-layer trick; avoiding
 * it needs true per-pixel blending, which the non-standard CSS {@code cross-fade()} function does
 * and this doesn't.) Works uniformly for any drawable pair (quad, sprite, SDF rounded rect, ...)
 * since the mechanism lives on the paint context, not on any one material.</p>
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

    /** Exposed so callers that need to reproduce this blend through a different rendering path
     * (e.g. {@code UIElement}'s rounded-background wrapper, which draws each side through a shared
     * {@code CgUiRoundedRect} shape instead of raw) can inspect the two sides and blend factor. */
    public CgUiDrawable getFrom() {
        return from;
    }

    public CgUiDrawable getTo() {
        return to;
    }

    public float getT() {
        return t;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        ctx.withLayerOpacity(1f - t, () -> from.draw(ctx, mouseX, mouseY, x, y, width, height));
        ctx.withLayerOpacity(t, () -> to.draw(ctx, mouseX, mouseY, x, y, width, height));
    }

    /** Interpolates the two sides' natural sizes so a fitted layer ({@code overlay-fit: none} etc.)
     * animates smoothly between them instead of snapping. Reports -1 (unknown) unless BOTH sides
     * have a natural size — a fade between a sized icon and a plain colour has no meaningful
     * intermediate size, and degrading that whole transition to {@code fill} is steadier than
     * having it pop when the fade starts or ends. */
    @Override
    public float intrinsicWidth() {
        return lerpIntrinsic(from.intrinsicWidth(), to.intrinsicWidth());
    }

    @Override
    public float intrinsicHeight() {
        return lerpIntrinsic(from.intrinsicHeight(), to.intrinsicHeight());
    }

    private float lerpIntrinsic(float fromValue, float toValue) {
        if (fromValue <= 0f || toValue <= 0f) return -1f;
        return fromValue + (toValue - fromValue) * t;
    }
}
