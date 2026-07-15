package com.crystalgui.style.property.layout.grid;

import dev.vfyjxf.taffy.geometry.TaffyLine;
import dev.vfyjxf.taffy.style.GridPlacement;

public record Grid(TaffyLine<GridPlacement> grid) {
    public static final Grid EMPTY = new Grid(TaffyLine.all(GridPlacement.AUTO_INSTANCE));
}
