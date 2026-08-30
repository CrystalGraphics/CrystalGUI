package com.crystalgui.style.property;

import com.crystalgui.style.StyleOrigin;

/**
 * One candidate value for one property, with everything the cascade compares.
 *
 * <p>{@code proximity} is CSS Cascade 6's scoping proximity: how many hops the element is from the
 * root of the scope its rule came from, ranked <b>between specificity and order of appearance</b>
 * — a closer scope wins, and only among candidates of equal specificity. An unscoped candidate is
 * {@link #UNSCOPED}, which every scoped one beats; the old engine, which scopes nothing, orders
 * exactly as it did before the field existed.</p>
 */
public record StyleSlot<T> (
        StyleProperty<T> property,
        StyleOrigin origin,
        int specificity,
        int proximity,
        long sourceOrder,
        T value
) {
    /** Not from a scoped sheet: loses proximity to anything that is. */
    public static final int UNSCOPED = Integer.MAX_VALUE;

    public static <T> StyleSlot<T> of(StyleProperty<T> property, StyleOrigin origin, int specificity, long sourceOrder, T value) {
        return new StyleSlot<>(property, origin, specificity, UNSCOPED, sourceOrder, value);
    }

    public static <T> StyleSlot<T> of(StyleProperty<T> property, StyleOrigin origin, int specificity, int proximity,
                                      long sourceOrder, T value) {
        return new StyleSlot<>(property, origin, specificity, proximity, sourceOrder, value);
    }

    /** Same property, origin, specificity and order — the "same declaration" test a replace uses. */
    public boolean typeEquals(StyleSlot<?> slot) {
        return slot.property == this.property &&
                slot.origin == this.origin &&
                slot.specificity == this.specificity &&
                slot.sourceOrder == this.sourceOrder;
    }

    public static int compare(StyleSlot<?> a, StyleSlot<?> b) {
        return a.compareTo(b);
    }

    /** Origin, then specificity, then scope proximity (closer wins), then order of appearance. */
    public int compareTo(StyleSlot<?> o) {
        var c = Integer.compare(this.origin.priority, o.origin.priority);
        if (c != 0) return c;
        c = Integer.compare(this.specificity, o.specificity);
        if (c != 0) return c;
        c = Integer.compare(o.proximity, this.proximity);
        if (c != 0) return c;
        return Long.compare(this.sourceOrder, o.sourceOrder);
    }
}
