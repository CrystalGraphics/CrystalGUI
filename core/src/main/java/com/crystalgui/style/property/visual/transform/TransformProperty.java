package com.crystalgui.style.property.visual.transform;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UITransform;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code transform} property. Transitionable, with CSS's list-matching interpolation rule.
 *
 * <h3>Interpolation</h3>
 * <p>CSS interpolates two transforms component-wise when their function lists line up, and otherwise
 * falls back to decomposing both into matrices and interpolating those. Only the first half is
 * implemented: matching lists lerp per function, mismatched ones snap at the halfway point.</p>
 *
 * <p>The fallback is the harder and rarer case — it needs a full matrix decomposition into
 * translate/rotate/scale/skew and a quaternion slerp — and the mismatch it covers (animating
 * {@code scale(1)} to {@code rotate(45deg) scale(2)}) is one an author can always avoid by writing both
 * ends with the same functions, which is the standard advice for CSS transform animations anyway.</p>
 */
public class TransformProperty extends StyleProperty<UITransform> {

    public TransformProperty(String name, UITransform initialValue) {
        super(name, UITransform.class, initialValue, TransformValue::new);
        setAllowTransition(true);
        setInterpolator(TransformProperty::interpolate);
    }

    static UITransform interpolate(UITransform from, UITransform to, float t) {
        List<UITransform.Op> a = from.ops();
        List<UITransform.Op> b = to.ops();
        if (a.size() != b.size()) return snap(from, to, t);

        List<UITransform.Op> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) {
            UITransform.Op fromOp = a.get(i);
            UITransform.Op toOp = b.get(i);
            if (fromOp.kind() != toOp.kind()) return snap(from, to, t);

            LengthPercent lx = lerp(fromOp.lx(), toOp.lx(), t);
            LengthPercent ly = lerp(fromOp.ly(), toOp.ly(), t);
            // A px<->% translation has no single well-defined intermediate (CSS would emit a calc()),
            // so one incommensurable pair snaps the whole transform rather than half of it.
            if (lx == null || ly == null) return snap(from, to, t);

            out.add(new UITransform.Op(fromOp.kind(), lx, ly,
                    lerp(fromOp.fx(), toOp.fx(), t),
                    lerp(fromOp.fy(), toOp.fy(), t)));
        }
        return UITransform.of(out);
    }

    /** {@link com.crystalgui.style.property.IValueInterpolator#BINARY}'s rule, named for the reason. */
    private static UITransform snap(UITransform from, UITransform to, float t) {
        return t < 0.5f ? from : to;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** @return {@code null} when the two differ in unit, which the caller turns into a snap. */
    private static LengthPercent lerp(LengthPercent from, LengthPercent to, float t) {
        if (from.percent != to.percent) return null;
        float value = from.value + (to.value - from.value) * t;
        return from.percent ? LengthPercent.percent(value) : LengthPercent.px(value);
    }
}
