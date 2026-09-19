package com.crystalgui.style.property.visual.stacking;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;

/** {@code z-index}: {@code auto} or an integer. Two numbers interpolate; {@code auto} snaps at the midpoint. */
public final class ZIndexProperty extends StyleProperty<ZIndex> {

    public ZIndexProperty(String name) {
        super(name, ZIndex.class, ZIndex.AUTO, Value::new);
        setAllowTransition(true);
        setInterpolator(ZIndexProperty::interpolate);
    }

    private static ZIndex interpolate(ZIndex from, ZIndex to, float t) {
        if (from.auto() || to.auto()) return t < 0.5f ? from : to;
        return ZIndex.of(Math.round(from.value() + (to.value() - from.value()) * t));
    }

    static final class Value extends StyleValue<ZIndex> {

        Value(String raw) {
            super(raw);
        }

        @Override
        protected ZIndex doCompute(String raw) {
            String text = raw.trim();
            return text.equalsIgnoreCase("auto") ? ZIndex.AUTO : ZIndex.of(Integer.parseInt(text));
        }
    }
}
