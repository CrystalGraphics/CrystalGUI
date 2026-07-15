package com.crystalgui.style.property.layout.grid;

import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class GridTemplateAreasProperty extends StyleProperty<GridTemplateAreas> {
    public GridTemplateAreasProperty(String name, GridTemplateAreas initialValue) {
        super(name, GridTemplateAreas.class, initialValue, GridTemplateAreasValue::new);
    }
}