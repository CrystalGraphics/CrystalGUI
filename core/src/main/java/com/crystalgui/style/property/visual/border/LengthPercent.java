package com.crystalgui.style.property.visual.border;

import javax.annotation.Nullable;

/**
 * A plain length-or-percentage scalar — {@code border-radius}'s per-axis unit, deliberately not
 * Taffy's {@link dev.vfyjxf.taffy.style.LengthPercentageAuto}: corner radius isn't a layout
 * quantity Taffy resolves, so percentages here are resolved manually against the element's own
 * already-computed width/height at paint/hit-test time, not by Taffy during layout.
 */
public final class LengthPercent {
    public static final LengthPercent ZERO = px(0f);

    public final boolean percent;
    public final float value;

    private LengthPercent(boolean percent, float value) {
        this.percent = percent;
        this.value = value;
    }

    public static LengthPercent px(float pixels) {
        return new LengthPercent(false, pixels);
    }

    /** @param fraction 0.0-1.0, e.g. 0.5 for {@code 50%}. */
    public static LengthPercent percent(float fraction) {
        return new LengthPercent(true, fraction);
    }

    /** @param axisSize the element's own width (for a horizontal radius) or height (vertical). */
    public float resolve(float axisSize) {
        return percent ? value * axisSize : value;
    }

    /** Same {@code %}/{@code px}/bare-number suffix convention as {@code LPAValue}, minus the
     * layout-only keywords (auto/min-content/etc.) that don't apply to a corner radius. */
    public static @Nullable LengthPercent parse(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim().toLowerCase();
        if (trimmed.isEmpty()) return null;
        try {
            if (trimmed.endsWith("%")) {
                float fraction = Float.parseFloat(trimmed.substring(0, trimmed.length() - 1).trim()) / 100f;
                return percent(fraction);
            }
            if (trimmed.endsWith("px")) {
                return px(Float.parseFloat(trimmed.substring(0, trimmed.length() - 2).trim()));
            }
            return px(Float.parseFloat(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Value equality, and it is load-bearing rather than a convenience.
     *
     * <p>{@code ElementStyle.resolveOne} decides whether a computed value actually changed with
     * {@code Objects.equals}. Without this, every re-resolve of a {@code border-radius} or
     * {@code transform-origin} looked like a change and fired the property's listeners — which for
     * {@code transform-origin} means invalidating a whole subtree's matrices on every style pass.</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LengthPercent other)) return false;
        return percent == other.percent && Float.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(percent) + Float.hashCode(value);
    }

    @Override
    public String toString() {
        return percent ? (value * 100f) + "%" : value + "px";
    }
}
