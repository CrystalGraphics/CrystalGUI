package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.property.StyleValue;

import javax.annotation.Nullable;

public class LengthPercentValue extends StyleValue<LengthPercent> {

    public LengthPercentValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable LengthPercent doCompute(String rawValue) {
        return LengthPercent.parse(rawValue);
    }
}
