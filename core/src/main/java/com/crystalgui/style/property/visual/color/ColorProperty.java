package com.crystalgui.style.property.visual.color;

import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.style.property.StyleProperty;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class ColorProperty extends StyleProperty<Integer> {
    public ColorProperty(String name, int initialValue) {
        super(name, Integer.class, initialValue, ColorValue::new);
        setAllowTransition(true);
        setInterpolator(ArgbMath::lerp);
    }

    /**
     * {@code #RRGGBB}, or {@code #AARRGGBB} when there is transparency to state.
     *
     * <p>The default writer is {@link String#valueOf}, which answers a colour as the signed integer it is
     * stored as — {@code -1535686}. That reads back (the parser takes a bare number), so nothing failed;
     * what it produced was a value no sheet would ever be written with, and an inspector row showing a
     * colour as a negative number.</p>
     */
    @Override
    public String write(Integer value) {
        if (value == null) return "";
        // #rrggbbaa puts alpha LAST, unlike our ARGB int -- the same asymmetry ColorValue's parser states.
        return (value >>> 24) == 0xFF
                ? String.format("#%06X", value & 0xFFFFFF)
                : String.format("#%08X", ((value & 0xFFFFFF) << 8) | (value >>> 24));
    }
}