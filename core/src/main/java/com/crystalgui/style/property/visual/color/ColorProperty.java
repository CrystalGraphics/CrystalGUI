package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class ColorProperty extends StyleProperty<Integer> {
    public ColorProperty(String name, int initialValue) {
        super(name, Integer.class, initialValue, ColorValue::new);
        setAllowTransition(true);
    }
}