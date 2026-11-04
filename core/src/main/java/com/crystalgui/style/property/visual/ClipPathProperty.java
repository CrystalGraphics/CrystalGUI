package com.crystalgui.style.property.visual;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.border.LengthPercent;

import javax.annotation.Nullable;

/** {@code clip-path}, transitionable between two insets. @see ClipPath */
public final class ClipPathProperty extends StyleProperty<ClipPath> {

    public ClipPathProperty(String name) {
        super(name, ClipPath.class, ClipPath.NONE, Value::new);
        setAllowTransition(true);
        setInterpolator(ClipPathProperty::interpolate);
    }

    private static ClipPath interpolate(ClipPath from, ClipPath to, float t) {
        if (from.isNone() || to.isNone()) return t < 0.5f ? from : to;
        LengthPercent top = lerp(from.top(), to.top(), t), right = lerp(from.right(), to.right(), t);
        LengthPercent bottom = lerp(from.bottom(), to.bottom(), t), left = lerp(from.left(), to.left(), t);
        LengthPercent round = lerp(from.round(), to.round(), t);
        if (top == null || right == null || bottom == null || left == null || round == null) {
            return t < 0.5f ? from : to;
        }
        return ClipPath.inset(top, right, bottom, left, round);
    }

    /** Null when the units differ: a px and a % have no one intermediate. */
    @Nullable
    private static LengthPercent lerp(LengthPercent from, LengthPercent to, float t) {
        // Zero is zero in either unit, so `0` against `50%` still eases
        boolean fromZero = from.value == 0f, toZero = to.value == 0f;
        if (from.percent != to.percent && !fromZero && !toZero) return null;
        boolean percent = fromZero ? to.percent : from.percent;
        float value = from.value + (to.value - from.value) * t;
        return percent ? LengthPercent.percent(value) : LengthPercent.px(value);
    }

    private static final class Value extends StyleValue<ClipPath> {
        Value(String raw) {
            super(raw);
        }

        @Override
        protected @Nullable ClipPath doCompute(String raw) {
            return ClipPath.parse(raw);
        }
    }
}
