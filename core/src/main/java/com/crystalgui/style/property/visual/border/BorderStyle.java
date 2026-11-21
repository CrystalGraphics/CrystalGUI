package com.crystalgui.style.property.visual.border;

/**
 * CSS {@code border-style}, per side.
 *
 * <p>{@code none} and {@code hidden} make the side's width 0, in layout and in paint, as CSS computes
 * them. Every other style draws solid: the painter has no dashes or bevels yet.</p>
 *
 * <p>Initially {@code solid}, where CSS says {@code none}, so {@code border-width: 1px} alone draws. Every
 * sheet and every Java builder here was written that way; see {@code style/CLAUDE.md} § BoxStyle.</p>
 */
public enum BorderStyle {
    NONE, HIDDEN, SOLID, DOTTED, DASHED, DOUBLE, GROOVE, RIDGE, INSET, OUTSET;

    /** Whether the side has a border at all. */
    public boolean draws() {
        return this != NONE && this != HIDDEN;
    }
}
