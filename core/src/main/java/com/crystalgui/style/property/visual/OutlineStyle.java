package com.crystalgui.style.property.visual;

/**
 * CSS {@code outline-style}. {@code none} draws no outline ring; {@code auto} and the rest draw solid.
 *
 * <p>Initially {@code solid}, where CSS says {@code none}, so {@code outline: 1px #46505E} draws, as
 * {@link com.crystalgui.style.property.visual.border.BorderStyle} does for borders.</p>
 */
public enum OutlineStyle {
    NONE, AUTO, SOLID, DOTTED, DASHED, DOUBLE, GROOVE, RIDGE, INSET, OUTSET;

    public boolean draws() {
        return this != NONE;
    }
}
