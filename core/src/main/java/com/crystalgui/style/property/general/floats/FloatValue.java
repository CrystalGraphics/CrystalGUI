package com.crystalgui.style.property.general.floats;

import com.crystalgui.style.property.StyleValue;

public class FloatValue extends StyleValue<Float> {

    public FloatValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Float doCompute(String rawValue) {
        return Float.parseFloat(rawValue.trim());
    }
    
}