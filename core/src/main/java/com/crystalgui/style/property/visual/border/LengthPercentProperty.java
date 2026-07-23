package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.property.StyleProperty;

public class LengthPercentProperty extends StyleProperty<LengthPercent> {

    public LengthPercentProperty(String name, LengthPercent initialValue) {
        super(name, LengthPercent.class, initialValue, LengthPercentValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    private LengthPercent interpolate(LengthPercent from, LengthPercent to, float t) {
        // Same-unit interpolation only (matches LPAProperty's precedent) — mixing a px and a %
        // value has no single well-defined intermediate, so fall back to a binary snap.
        if (from.percent == to.percent) {
            float value = from.value + (to.value - from.value) * t;
            return from.percent ? LengthPercent.percent(value) : LengthPercent.px(value);
        }
        return t < 0.5f ? from : to;
    }
}
