package com.crystalgui.style.property.layout.length;

import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.style.LengthPercentage;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class LPSizeProperty extends StyleProperty<LPSize> {
    public LPSizeProperty(String name, LPSize initialValue) {
        super(name, LPSize.class, initialValue, LPSizeValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    private LPSize interpolate(LPSize from, LPSize to, float interpolation) {
        // Interpolate width and height independently
        LengthPercentage width = interpolateLengthPercentage(from.size().width, to.size().width, interpolation);
        LengthPercentage height = interpolateLengthPercentage(from.size().height, to.size().height, interpolation);

        return new LPSize(new dev.vfyjxf.taffy.geometry.TaffySize<>(width, height));
    }

    private LengthPercentage interpolateLengthPercentage(LengthPercentage from, LengthPercentage to, float interpolation) {
        // Only interpolate if both values are the same numeric type
        if (from.isLength() && to.isLength()) {
            float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
            return LengthPercentage.length(interpolated);
        } else if (from.isPercent() && to.isPercent()) {
            float interpolated = from.getValue() + (to.getValue() - from.getValue()) * interpolation;
            return LengthPercentage.percent(interpolated);
        }

        // For different types, use binary snap
        return interpolation < 0.5f ? from : to;
    }
}