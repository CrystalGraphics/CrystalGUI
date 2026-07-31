package com.crystalgui.ui.elements.table;

import com.crystalgui.ui.UIElement;

/**
 * Builds and fills one column's cells — the same {@code createTemplate}/{@code bind} split every
 * recycled thing in this engine uses, and for the same reason: there is nowhere to attach a listener in
 * {@code bind}, so a recycled cell cannot accumulate one.
 *
 * <p>Optional. A column with only a value function gets a plain text cell, which is what nearly every
 * column is. This exists for the ones that are not — an icon, a colour swatch, a progress bar.</p>
 */
public interface TableCellRenderer<T> {

    UIElement createTemplate();

    void bind(T item, int rowIndex, UIElement template);
}
