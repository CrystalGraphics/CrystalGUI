package com.crystalgui.style.property.general.bools;

import com.crystalgui.style.property.StyleValue;

public class BoolValue extends StyleValue<Boolean> {

    public BoolValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Boolean doCompute(String rawValue) {
        return Boolean.parseBoolean(rawValue.trim());
    }
    
}