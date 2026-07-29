package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.StyleProperty;

/**
 * {@code line-height}, whose value is {@code normal} or a unitless multiplier — see {@link LineHeightValue}.
 *
 * <p>Transitionable, with the interpolator guarded against the {@code normal} sentinel: {@code NaN}
 * poisons arithmetic, so blending into or out of it would produce a {@code NaN} line box for the whole
 * transition rather than a smooth one. Snapping at the halfway point is the same fallback the engine
 * uses everywhere two values have no meaningful intermediate, and it matches
 * {@code AutoFloatProperty}, which faced this exact problem for {@code flex} and {@code aspect-rate}.</p>
 */
public class LineHeightProperty extends StyleProperty<Float> {

    public LineHeightProperty(String name, float initialValue) {
        super(name, Float.class, initialValue, LineHeightValue::new);
        setAllowTransition(true);
        setInterpolator(LineHeightProperty::interpolate);
    }

    private static float interpolate(float from, float to, float interpolation) {
        // `normal` is font-derived and a number is font-size-derived; there is no continuum between
        // them, and NaN would contaminate every intermediate anyway.
        if (LineHeightValue.isNormal(from) || LineHeightValue.isNormal(to)) {
            return interpolation < 0.5f ? from : to;
        }
        return from + (to - from) * interpolation;
    }
}
