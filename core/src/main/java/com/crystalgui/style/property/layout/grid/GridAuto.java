package com.crystalgui.style.property.layout.grid;

import dev.vfyjxf.taffy.style.TrackSizingFunction;

import java.util.List;

public record GridAuto(List<TrackSizingFunction> values) {
    public static final GridAuto EMPTY = new GridAuto(List.of());
}
