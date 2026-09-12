package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.property.StyleProperty;

public class LengthPercentProperty extends StyleProperty<LengthPercent> {

    public LengthPercentProperty(String name, LengthPercent initialValue) {
        this(name, initialValue, LengthPercentValue::new);
    }

    /**
     * With a parser of its own, for a property that accepts a unit the shared one must not — see
     * {@code FontRelativeLengthValue}, which adds {@code em} where a percentage already resolves
     * against the font size.
     */
    public LengthPercentProperty(String name, LengthPercent initialValue,
                                 ValueParser<LengthPercent> valueParser) {
        super(name, LengthPercent.class, initialValue, valueParser);
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
