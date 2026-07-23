package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * The 8 real longhand corner-radius properties (rx/ry per corner, CSS {@code border-radius}
 * TL/TR/BR/BL order) — the composite {@code border-radius} name is pure parse-time shorthand
 * syntax (see {@link BorderRadiusShorthand}), never itself a registered {@link StyleProperty},
 * exactly mirroring how {@code margin}/{@code padding}/{@code border-width} work.
 */
public final class BorderRadiusProperties {

    public static final StyleProperty<LengthPercent> TOP_LEFT_X = create("border-top-left-radius-x");
    public static final StyleProperty<LengthPercent> TOP_LEFT_Y = create("border-top-left-radius-y");
    public static final StyleProperty<LengthPercent> TOP_RIGHT_X = create("border-top-right-radius-x");
    public static final StyleProperty<LengthPercent> TOP_RIGHT_Y = create("border-top-right-radius-y");
    public static final StyleProperty<LengthPercent> BOTTOM_RIGHT_X = create("border-bottom-right-radius-x");
    public static final StyleProperty<LengthPercent> BOTTOM_RIGHT_Y = create("border-bottom-right-radius-y");
    public static final StyleProperty<LengthPercent> BOTTOM_LEFT_X = create("border-bottom-left-radius-x");
    public static final StyleProperty<LengthPercent> BOTTOM_LEFT_Y = create("border-bottom-left-radius-y");

    private BorderRadiusProperties() {
    }

    private static StyleProperty<LengthPercent> create(String name) {
        return StylePropertyRegistry.create(new LengthPercentProperty(name, LengthPercent.ZERO));
    }
}
