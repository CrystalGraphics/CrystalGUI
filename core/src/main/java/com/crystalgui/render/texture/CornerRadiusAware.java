package com.crystalgui.render.texture;

/**
 * A drawable that clips itself, and therefore needs the element's resolved {@code border-radius}.
 *
 * <h3>Why this exists rather than the usual wrap</h3>
 *
 * <p>{@code UIElement.paintSelf} normally wraps whatever {@code background} resolves to in a
 * {@link CgUiRoundedRect} when the element has a radius or a border — rounding is orthogonal to what a
 * background <em>is</em>, exactly as it is in CSS, so one layer handles it for every fill type.</p>
 *
 * <p>That is right for a fill and impossible for a <b>material</b>. {@link CgUiGlass} carries its own
 * shader: it needs the radii to mask itself <em>and</em> to measure the bezel its refraction is computed
 * across, and there is no way to express either through a wrapper that would have to rasterise it to a
 * texture first — which is what wrapping means. Wrapped, glass would silently become a rounded
 * rectangle full of nothing.</p>
 *
 * <p>So a drawable that implements this is handed the radii and left to draw itself. The radii arrive
 * <b>already resolved against the element's box</b> — percentages are gone, and each corner may be
 * elliptical, which is why there are eight numbers rather than four.</p>
 */
public interface CornerRadiusAware {

    /**
     * The element's resolved corner radii, in CSS order (TL, TR, BR, BL), each as a separate x and y.
     *
     * <p>Called immediately before {@code draw}, every frame, because a radius can be a percentage of a
     * box that changes — and because the alternative is a drawable caching geometry that is not its own.</p>
     */
    void setCornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                        float rxBR, float ryBR, float rxBL, float ryBL);
}
