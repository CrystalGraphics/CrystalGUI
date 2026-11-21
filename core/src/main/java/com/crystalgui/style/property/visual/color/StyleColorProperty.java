package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;
import org.jetbrains.annotations.Nullable;

/**
 * A colour property that may hold {@code currentcolor}, resolved against the element's {@code color} only
 * when it paints, as CSS keeps it in the computed value.
 *
 * <pre>{@code
 * StyleProperty<StyleColor> p = create(new StyleColorProperty("outline-color", StyleColor.CURRENT));
 * int argb = style.get(p).resolve(style.get(COLOR));   // paint time
 * }</pre>
 *
 * <p>For the colours whose CSS initial value is {@code currentcolor}: border sides, outline, caret, text
 * decoration, stroke and fill. A colour that never follows the text, such as {@code background-color},
 * stays a {@link ColorProperty}. A transition between a literal and {@code currentcolor} is a mix of the
 * two (see {@link StyleColor}); written mid-way, it writes its literal part, as shadows do.</p>
 */
public class StyleColorProperty extends StyleProperty<StyleColor> {

    public StyleColorProperty(String name, StyleColor initialValue) {
        super(name, StyleColor.class, initialValue, Value::new);
        setAllowTransition(true);
        setInterpolator(StyleColor::lerp);
    }

    @Override
    public String write(StyleColor value) {
        if (value == null) return "";
        if (value.isCurrentColor()) return "currentcolor";
        // As ColorProperty writes: #RRGGBB, with alpha (last, as CSS puts it) only when there is some to state.
        int argb = value.literalArgb();
        return (argb >>> 24) == 0xFF ? String.format("#%06X", argb & 0xFFFFFF)
                : String.format("#%08X", ((argb & 0xFFFFFF) << 8) | (argb >>> 24));
    }

    /** A CSS {@code <color>} or {@code currentcolor}. */
    public static final class Value extends StyleValue<StyleColor> {

        public Value(String raw) {
            super(raw);
        }

        @Override
        protected @Nullable StyleColor doCompute(String raw) {
            if (raw.trim().equalsIgnoreCase("currentcolor")) return StyleColor.CURRENT;
            Integer argb = ColorValue.parseCssColor(raw);
            return argb == null ? null : StyleColor.of(argb);
        }
    }
}
