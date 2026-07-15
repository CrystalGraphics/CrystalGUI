package com.crystalgui.style.property.layout.grid;

import com.crystalgui.style.property.StyleProperty;

public class GridTemplateProperty extends StyleProperty<GridTemplate> {
    public GridTemplateProperty(String name, GridTemplate initialValue) {
        super(name, GridTemplate.class, initialValue, GridTemplateValue::new);
    }
}
