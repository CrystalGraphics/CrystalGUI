package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class ColorProperty extends StyleProperty<Integer> {
    public ColorProperty(String name, int initialValue) {
        super(name, Integer.class, initialValue, ColorValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    /** Channel-wise ARGB lerp (each 0-255 channel interpolated independently, then repacked). */
    private int interpolate(int from, int to, float interpolation) {
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, interpolation);
        int r = lerpChannel((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, interpolation);
        int g = lerpChannel((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, interpolation);
        int b = lerpChannel(from & 0xFF, to & 0xFF, interpolation);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float interpolation) {
        return Math.round(from + (to - from) * interpolation);
    }
}