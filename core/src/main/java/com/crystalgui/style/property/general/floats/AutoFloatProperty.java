package com.crystalgui.style.property.general.floats;

import com.crystalgui.style.property.StyleProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class AutoFloatProperty extends StyleProperty<Float> {
    @Setter
    private float min = -Float.MAX_VALUE;
    @Setter
    private float max = Float.MAX_VALUE;
    @Getter
    @Setter
    private float step = 0.1f;

    public AutoFloatProperty(String name, float initialValue) {
        super(name, Float.class, initialValue, FloatValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    public AutoFloatProperty setRange(float min, float max) {
        return setMin(min).setMax(max);
    }



    private float interpolate(float from, float to, float interpolation) {
        if (Float.isNaN(from) || Float.isNaN(to)) {
            return interpolation < 0.5f ? from : to;
        }
        return from + (to - from) * interpolation;
    }
}
