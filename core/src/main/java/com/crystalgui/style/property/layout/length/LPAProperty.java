package com.crystalgui.style.property.layout.length;

import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class LPAProperty extends StyleProperty<LengthPercentageAuto> {
    public LPAProperty(String name, LengthPercentageAuto initialValue) {
        super(name, LengthPercentageAuto.class, initialValue, LPAValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }


    private LengthPercentageAuto interpolate(LengthPercentageAuto from, LengthPercentageAuto to, float interpolation) {
        // Only interpolate if both values are the same numeric type (LENGTH or PERCENT)
        if (from.getType() == to.getType()) {
            if (from.isLength()) {
                // Interpolate lengths
                float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
                return LengthPercentageAuto.length(interpolated);
            } else if (from.isPercent()) {
                // Interpolate percentages
                float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
                return LengthPercentageAuto.percent(interpolated);
            }
        }

        // For different types or non-numeric types, use binary snap
        return interpolation < 0.5f ? from : to;
    }
}