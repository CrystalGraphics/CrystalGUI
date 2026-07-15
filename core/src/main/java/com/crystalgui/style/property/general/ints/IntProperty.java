package com.crystalgui.style.property.general.ints;

import com.crystalgui.style.property.StyleProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class IntProperty extends StyleProperty<Integer> {
    @Setter
    private int min = Integer.MIN_VALUE;
    @Setter
    private int max = Integer.MAX_VALUE;
    @Getter
    @Setter
    private int step = 1;

    public IntProperty(String name, int initialValue) {
        super(name, Integer.class, initialValue, IntValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    public IntProperty setRange(int min, int max) {
        return setMin(min).setMax(max);
    }

    private int interpolate(int from, int to, float interpolation) {
        return Math.round(from + (to - from) * interpolation);
    }
}