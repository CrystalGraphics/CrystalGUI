package com.crystalgui.style.property.layout.dimension;

import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.style.TaffyDimension;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class DimensionProperty extends StyleProperty<TaffyDimension> {
    public DimensionProperty(String name, TaffyDimension initialValue) {
        super(name, TaffyDimension.class, initialValue, DimensionValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    private TaffyDimension interpolate(TaffyDimension from, TaffyDimension to, float interpolation) {
        // Only interpolate if both values are the same numeric type (LENGTH or PERCENT)
        if (from.isLength() && to.isLength()) {
            // Interpolate lengths
            float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
            return TaffyDimension.length(interpolated);
        } else if (from.isPercent() && to.isPercent()) {
            // Interpolate percentages
            float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
            return TaffyDimension.percent(interpolated);
        }

        // For different types or auto, use binary snap
        return interpolation < 0.5f ? from : to;
    }
}