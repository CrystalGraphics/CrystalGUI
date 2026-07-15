package com.crystalgui.style.property.layout.length;

import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.geometry.TaffyRect;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class LPARectProperty extends StyleProperty<LPARect> {
    public LPARectProperty(String name, LPARect initialValue) {
        super(name, LPARect.class, initialValue, LPARectValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    private LPARect interpolate(LPARect from, LPARect to, float interpolation) {
        if (from == null || to == null || from.rect() == null || to.rect() == null) {
            return interpolation < 0.5f ? from : to;
        }

        TaffyRect<LengthPercentageAuto> fromRect = from.rect();
        TaffyRect<LengthPercentageAuto> toRect = to.rect();

        // Interpolate each edge separately using the same logic as LPAProperty
        LengthPercentageAuto left = interpolateEdge(fromRect.left, toRect.left, interpolation);
        LengthPercentageAuto right = interpolateEdge(fromRect.right, toRect.right, interpolation);
        LengthPercentageAuto top = interpolateEdge(fromRect.top, toRect.top, interpolation);
        LengthPercentageAuto bottom = interpolateEdge(fromRect.bottom, toRect.bottom, interpolation);

        return new LPARect(new TaffyRect<>(left, right, top, bottom));
    }

    private LengthPercentageAuto interpolateEdge(LengthPercentageAuto from, LengthPercentageAuto to, float interpolation) {
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
