package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.property.StyleValue;
import org.jetbrains.annotations.Nullable;

/**
 * CSS {@code font-size}, a length in pixels.
 *
 * <pre>
 *   font-size: 10px;
 *   font-size: 0;       a size of nothing, which CSS allows unitless
 *   font-size: 10;      dropped with a warning: a length needs its unit
 * </pre>
 *
 * <p>{@code em} and {@code %} are relative to the parent's size, which the cascade does not resolve for
 * {@code font-size} yet, so they do not parse.</p>
 */
public final class FontSizeValue extends StyleValue<Float> {

    public FontSizeValue(String raw) {
        super(raw);
    }

    @Override
    protected @Nullable Float doCompute(String raw) {
        CssLength length = CssLength.parse(raw);
        return length != null && length.unit() == CssLength.Unit.PX ? length.value() : null;
    }
}
