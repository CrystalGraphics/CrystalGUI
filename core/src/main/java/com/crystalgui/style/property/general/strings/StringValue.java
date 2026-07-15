package com.crystalgui.style.property.general.strings;

import com.crystalgui.style.property.StyleValue;

public class StringValue extends StyleValue<String> {

    public StringValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected String doCompute(String rawValue) {
        return rawValue;
    }
    
}