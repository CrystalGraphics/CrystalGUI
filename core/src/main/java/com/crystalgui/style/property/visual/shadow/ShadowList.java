package com.crystalgui.style.property.visual.shadow;

import com.crystalgui.style.property.FontRelative;

import java.util.Arrays;
import java.util.List;

/**
 * A computed shadow list, first shadow on top: the value of {@code text-shadow}, and the shape
 * {@code box-shadow} and {@code drop-shadow()} share.
 *
 * <pre>{@code
 * ShadowList list = ShadowList.parse("0 0 8px #4cf, 1px 1px black", ShadowGrammar.TEXT_LEVEL_3);
 * for (int i = list.size() - 1; i >= 0; i--) paint(list.get(i));   // last painted first
 * }</pre>
 *
 * <p>Immutable; {@link #NONE} is {@code none}. Lengths are already pixels, since an {@code em} resolves
 * at the element that declares it.</p>
 */
public final class ShadowList {

    public static final ShadowList NONE = new ShadowList(new Shadow[0]);

    private final Shadow[] shadows;

    private ShadowList(Shadow[] shadows) {
        this.shadows = shadows;
    }

    public static ShadowList of(Shadow... shadows) {
        return shadows.length == 0 ? NONE : new ShadowList(shadows.clone());
    }

    public static ShadowList of(List<Shadow> shadows) {
        return shadows.isEmpty() ? NONE : new ShadowList(shadows.toArray(new Shadow[0]));
    }

    /**
     * Parses {@code raw} at the reference font size, or answers null when it is not valid for the grammar.
     * A stylesheet goes through {@link ShadowValue} instead, which resolves {@code em} per element.
     */
    public static ShadowList parse(String raw, ShadowGrammar grammar) {
        ShadowParser.Parsed parsed = ShadowParser.parse(raw, grammar);
        return parsed == null ? null : parsed.resolve(FontRelative.REFERENCE_FONT_SIZE);
    }

    public int size() {
        return shadows.length;
    }

    public boolean isEmpty() {
        return shadows.length == 0;
    }

    public Shadow get(int index) {
        return shadows[index];
    }

    /**
     * CSS's shadow-list interpolation: the shorter list padded with transparent zero shadows, pairs
     * interpolated field by field, and the whole list discrete when any pair's {@code inset} differs.
     *
     * <p>Ported from Blink's {@code CSSShadowListInterpolationType::MaybeMergeSingles}
     * ({@code kPadToLargest}) and {@code InterpolableShadow::MaybeMergeSingles} (BSD-3-Clause).</p>
     */
    public static ShadowList interpolate(ShadowList from, ShadowList to, float t) {
        int length = Math.max(from.size(), to.size());
        if (length == 0) return NONE;
        Shadow[] out = new Shadow[length];
        for (int i = 0; i < length; i++) {
            Shadow a = i < from.size() ? from.get(i) : null;
            Shadow b = i < to.size() ? to.get(i) : null;
            if (a == null) a = Shadow.neutralLike(b);
            if (b == null) b = Shadow.neutralLike(a);
            if (a.inset() != b.inset()) return t < 0.5f ? from : to;
            out[i] = Shadow.lerp(a, b, t);
        }
        return new ShadowList(out);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ShadowList other && Arrays.equals(shadows, other.shadows));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(shadows);
    }

    @Override
    public String toString() {
        return ShadowParser.write(this);
    }
}
