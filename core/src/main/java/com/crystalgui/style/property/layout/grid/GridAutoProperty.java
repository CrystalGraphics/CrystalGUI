package com.crystalgui.style.property.layout.grid;

import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class GridAutoProperty extends StyleProperty<GridAuto> {
    public GridAutoProperty(String name, GridAuto initialValue) {
        super(name, GridAuto.class, initialValue, GridAutoValue::new);
    }
}