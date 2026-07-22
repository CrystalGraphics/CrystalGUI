package com.crystalgui.style.sheet;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.selector.Selector;

import java.util.List;

/**
 * One parsed {@code selector { declarations }} rule from a {@link StyleSheet}. A comma-separated
 * selector list in the source produces one {@code StyleRule} per selector, all sharing the same
 * declarations and {@code sourceOrder} — they were declared together, at the same position in the
 * sheet.
 */
public record StyleRule(Selector selector, List<Declaration> declarations, int sourceOrder) {

    /**
     * One {@code property: value} pair. {@code important} is tracked per-declaration — a trailing
     * {@code !important} on a single value routes only that declaration to
     * {@link StyleOrigin#IMPORTANT}, not the whole rule.
     */
    public record Declaration(StyleProperty<?> property, StyleValue<?> value, boolean important) {}
}
