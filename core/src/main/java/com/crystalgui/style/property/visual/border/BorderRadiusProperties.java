package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * The eight corner radii, a horizontal and a vertical one per corner. A sheet writes them through
 * {@code border-radius} or CSS's per-corner {@code border-top-left-radius: h [v]}; the {@code -x} and
 * {@code -y} names are this engine's, not CSS's, and are not written directly.
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
        // Written through the corner's CSS name: "border-top-left-radius-x" as "border-top-left-radius".
        StyleProperty<LengthPercent> p = StylePropertyRegistry.create(new LengthPercentProperty(name, LengthPercent.ZERO));
        p.setAuthoredThrough(name.substring(0, name.length() - 2));
        return p;
    }
}
