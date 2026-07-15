package com.crystalgui.style.property.general.enums;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;

public class EnumValue<T extends Enum<T>> extends StyleValue<T> {
    private final Class<T> clazz;

    public EnumValue(Class<T> clazz, String rawValue) {
        super(rawValue);
        this.clazz = clazz;
    }

    public static <T extends Enum<T>> StyleProperty.ValueParser<T> of(Class<T> clazz) {
        return raw -> new EnumValue<>(clazz, raw);
    }

    @Override
    protected T doCompute(String rawValue) {
        var constants = clazz.getEnumConstants();
        var compat1 = rawValue.replace('_', '-');
        var compat2 = rawValue.replace('-', '_');
        for (var constant : constants) {
            if (constant.name().equalsIgnoreCase(rawValue)) {
                return constant;
            }
            if (constant.name().equalsIgnoreCase(compat1)) {
                return constant;
            }
            if (constant.name().equalsIgnoreCase(compat2)) {
                return constant;
            }
        }
        return null;
    }
}
