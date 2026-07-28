package com.crystalgui.style.property;

import com.crystalgui.style.StyleOrigin;

public record StyleSlot<T> (
        StyleProperty<T> property,
        StyleOrigin origin,
        int specificity,
        /** Position in the cascade. {@link com.crystalgui.style.StyleEngine} packs the owning sheet's
         * registration index above the rule's own index, so a later-registered sheet outranks an
         * earlier one — CSS's "later sheet wins" — and within one sheet a later rule still outranks
         * an earlier one. A {@code long} because that packing overflows an {@code int} after only a
         * couple of dozen sheets. */
        long sourceOrder,
        T value
) {

    public static <T> StyleSlot<T> of(StyleProperty<T> property, StyleOrigin origin, int specificity, long sourceOrder, T value) {
        return new StyleSlot<>(property, origin, specificity, sourceOrder, value);
    }

    public boolean typeEquals(StyleSlot<?> slot) {
        return slot.property == this.property &&
                slot.origin == this.origin &&
                slot.specificity == this.specificity &&
                slot.sourceOrder == this.sourceOrder;
    }

    public static int compare(StyleSlot<?> a, StyleSlot<?> b) {
        return a.compareTo(b);
    }

    public int compareTo(StyleSlot<?> o) {
        // compare priority, specificity, sourceOrder
        var c = Integer.compare(this.origin.priority, o.origin.priority);
        if (c != 0) return c;
        c = Integer.compare(this.specificity, o.specificity);
        if (c != 0) return c;
        return Long.compare(this.sourceOrder, o.sourceOrder);
    }
}