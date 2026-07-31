package com.crystalgui.ui.elements.table;

import lombok.Getter;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.function.Function;

/**
 * One column: a header, how to get its value out of a row, how wide it is, and whether it sorts.
 *
 * <h3>Fixed and flexible widths, decided up front</h3>
 * <p>A column has a pixel {@link #getWidth()}, and may additionally carry a {@link #getWeight()} — in
 * which case it absorbs a share of whatever width the fixed columns leave over. Both exist because the
 * two are genuinely wanted at once: a "size" column wants exactly 60px forever, and a "name" column wants
 * whatever is left.</p>
 *
 * <p><b>Dragging a flexible column's divider pins it.</b> Weight decides how leftover space is handed
 * out, and a user who has just dragged a column to a width has expressed that they want <em>that</em>
 * width — so the drag sets the pixel width and clears the weight. Every file manager behaves this way,
 * and the alternative (a column that springs back after you release it) is maddening.</p>
 */
public final class TableColumn<T> {

    @Getter private final String header;
    @Getter private final Function<T, String> value;

    @Getter private float width = 100f;
    @Getter private float weight;
    @Getter private float minWidth = 24f;
    @Getter private boolean resizable = true;
    @Getter @Nullable private Comparator<T> comparator;
    @Getter @Nullable private TableCellRenderer<T> cellRenderer;

    private TableColumn(String header, Function<T, String> value) {
        this.header = header == null ? "" : header;
        this.value = value == null ? item -> String.valueOf(item) : value;
    }

    public static <T> TableColumn<T> of(String header, Function<T, String> value) {
        return new TableColumn<>(header, value);
    }

    public TableColumn<T> width(float width) {
        this.width = Math.max(0f, width);
        return this;
    }

    /** Absorbs a share of the width the fixed columns leave over. */
    public TableColumn<T> flexible(float weight) {
        this.weight = Math.max(0f, weight);
        return this;
    }

    public TableColumn<T> flexible() {
        return flexible(1f);
    }

    /** Floor for a drag, so a column cannot be dragged to nothing and become unreachable — there would
     * be no divider left to drag back. */
    public TableColumn<T> minWidth(float minWidth) {
        this.minWidth = Math.max(1f, minWidth);
        return this;
    }

    public TableColumn<T> notResizable() {
        this.resizable = false;
        return this;
    }

    /** Sorts by the rendered text. Fine for names, wrong for numbers and dates — see the overload. */
    public TableColumn<T> sortable() {
        return sortable(Comparator.comparing(value, String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * Sorts by a real comparator.
     *
     * <p>Worth reaching for more often than it looks: sorting a "size" column by its rendered text puts
     * 1 KB after 10 MB and before 2 KB, which looks like a broken sort rather than a string sort.</p>
     */
    public TableColumn<T> sortable(Comparator<T> comparator) {
        this.comparator = comparator;
        return this;
    }

    /**
     * Sorts by the rendered text, comparing runs of digits as <b>numbers</b> — so {@code asset_2} comes
     * before {@code asset_10}, not after it.
     *
     * <p>A plain string sort puts {@code asset_1077} before {@code asset_2}, which is correct
     * lexicographically and wrong to every human looking at a file list. Explorer, Finder and every file
     * manager do this; it is worth being the easy option rather than the clever one.</p>
     */
    public TableColumn<T> naturalOrder() {
        return sortable(Comparator.comparing(value, TableColumn::compareNatural));
    }

    /** Digit runs compare numerically, everything else case-insensitively. */
    static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i, startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                // Compare by length first after stripping leading zeros, so "0009" and "9" tie rather
                // than ordering by a spurious digit count.
                String numA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                if (numA.length() != numB.length()) return numA.length() - numB.length();
                int cmp = numA.compareTo(numB);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    public TableColumn<T> cells(TableCellRenderer<T> renderer) {
        this.cellRenderer = renderer;
        return this;
    }

    public boolean isSortable() {
        return comparator != null;
    }

    /** Set by a divider drag. Clears the weight — see the class doc. */
    void applyDraggedWidth(float pixels) {
        this.width = Math.max(minWidth, pixels);
        this.weight = 0f;
    }

    /** Resolved width for layout: the pixel width plus this column's share of {@code leftover}. */
    float resolvedWidth(float leftover, float totalWeight) {
        if (weight <= 0f || totalWeight <= 0f || leftover <= 0f) return width;
        return width + leftover * (weight / totalWeight);
    }
}
