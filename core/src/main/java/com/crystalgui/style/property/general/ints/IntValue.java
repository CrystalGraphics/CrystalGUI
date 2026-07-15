package com.crystalgui.style.property.general.ints;

import com.crystalgui.style.property.StyleValue;

public class IntValue extends StyleValue<Integer> {

    public IntValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Integer doCompute(String rawValue) {
        return Integer.parseInt(rawValue.trim());
    }
    
}