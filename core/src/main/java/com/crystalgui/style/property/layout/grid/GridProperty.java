package com.crystalgui.style.property.layout.grid;

import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

/**
 * Property for CSS grid-row and grid-column.
 * Represents grid item placement using start and end lines.
 */
@Accessors(chain = true)
public class GridProperty extends StyleProperty<Grid> {
    public GridProperty(String name, Grid initialValue) {
        super(name, Grid.class, initialValue, GridValue::new);
    }
}