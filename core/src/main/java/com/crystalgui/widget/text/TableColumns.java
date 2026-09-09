package com.crystalgui.widget.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How wide each column of a table is — CSS Tables 3's automatic layout, over nothing but numbers.
 *
 * <p>Give it every cell's minimum and maximum content width and the room the table has; it answers a
 * width per column. It knows nothing about elements, styles or boxes, which is what makes it testable
 * and what keeps the measuring (whoever owns the cells) apart from the deciding.</p>
 *
 * <pre>{@code
 * List<TableColumns.Cell> cells = new ArrayList<>();
 * for (Cell c : row) cells.add(new TableColumns.Cell(c.column, c.colspan, minOf(c), maxOf(c)));
 * float[] widths = TableColumns.solve(columnCount, cells, table.contentBoxWidth());
 * }</pre>
 *
 * <h3>What it does, in three steps</h3>
 *
 * <ol>
 *   <li>A cell in ONE column contributes its minimum and maximum to that column directly.</li>
 *   <li>A cell spanning several contributes only what they cannot already carry between them — see
 *       {@link #distributeSpans}.</li>
 *   <li>The columns are then grown from their minimums toward their maximums by as much as the
 *       available width allows, all of them in step.</li>
 * </ol>
 *
 * <h3>It never stretches</h3>
 *
 * <p>Room left over after every column has its maximum is left over: a table whose content is narrower
 * than the page is drawn narrow, which is what a browser does with {@code width: auto} and what
 * IntelliJ's own javadoc viewer does. Stretching to the full width would put the last column's rule
 * far to the right of its text and read as an empty column nobody wrote.</p>
 *
 * <p>Ported from Chromium's {@code table_layout_utils.cc} (BSD-3-Clause) — {@code
 * ComputeGridInlineMinMax} and {@code DistributeColspanCellsToColumns} — held to CSS Tables 3
 * §Computing column measures. See {@code THIRD-PARTY.md}.</p>
 *
 * <p><b>Not implemented</b>, because a doc comment's table never carries them: a declared
 * {@code width} on a column or a cell, percentage columns, and {@code table-layout: fixed}. Each is a
 * separate branch in the reference and each would be dead code here.</p>
 */
public final class TableColumns {

    private TableColumns() {
    }

    /**
     * One cell's contribution — where it starts, how far it reaches, and what it needs.
     *
     * @param column   the column it starts in, as the grid placed it (a span above it may have moved it)
     * @param colspan  how many columns it covers, at least 1
     * @param min      its minimum content width: the widest thing in it that cannot be broken
     * @param max      its maximum content width: what it takes on one line
     */
    public record Cell(int column, int colspan, float min, float max) {
    }

    /**
     * A width per column, left to right.
     *
     * @param columns   how many there are; a zero or negative count answers an empty array
     * @param cells     every cell in the table, in any order
     * @param available the room the table has for its columns — its own content width
     */
    public static float[] solve(int columns, List<Cell> cells, float available) {
        if (columns <= 0) return new float[0];
        float[] min = new float[columns];
        float[] max = new float[columns];

        List<Cell> spanning = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.column() < 0 || cell.column() >= columns) continue;
            if (cell.colspan() > 1) {
                spanning.add(cell);
                continue;
            }
            min[cell.column()] = Math.max(min[cell.column()], Math.max(0f, cell.min()));
            max[cell.column()] = Math.max(max[cell.column()], Math.max(0f, cell.max()));
        }
        distributeSpans(spanning, columns, min, max);

        // A MAXIMUM BELOW ITS OWN MINIMUM is not a table anyone can draw. It arises from a cell whose
        // longest word is wider than the line it would take unbroken -- which cannot happen for text but
        // can once a span has contributed to only one of the two.
        for (int c = 0; c < columns; c++) max[c] = Math.max(max[c], min[c]);

        float sumMin = sum(min);
        float sumMax = sum(max);
        if (available <= sumMin || sumMax <= sumMin) {
            // NO ROOM TO GIVE. The table is drawn at its minimum and overflows, which is honest: the
            // alternative is columns narrower than the words in them, and text cannot be made narrower.
            return min.clone();
        }
        if (available >= sumMax) return max.clone();

        // IN STEP, not one column at a time. Every column takes the same fraction of the room between
        // its own minimum and its own maximum, so a column that wants a lot and a column that wants a
        // little are squeezed by the same proportion of what they asked for.
        float fraction = (available - sumMin) / (sumMax - sumMin);
        float[] out = new float[columns];
        for (int c = 0; c < columns; c++) out[c] = min[c] + (max[c] - min[c]) * fraction;
        return out;
    }

    /**
     * What a spanning cell asks of the columns under it.
     *
     * <p>Only the EXCESS: a cell covering three columns that already carry more than it needs asks for
     * nothing, because its own width says nothing about how wide any one of them is. What it does not
     * fit into is shared out in proportion to what each column already wants, so a wide column takes
     * more of the excess than a narrow one and the ratio between them survives.</p>
     *
     * <p><b>Narrowest span first.</b> A two-column span resolves against columns a three-column span has
     * not touched yet, which is the order the reference uses and the only one that is stable: run the
     * other way and the wide span's contribution is spread over columns the narrow one is about to
     * raise, then raised again.</p>
     */
    private static void distributeSpans(List<Cell> spanning, int columns, float[] min, float[] max) {
        if (spanning.isEmpty()) return;
        spanning.sort(Comparator.comparingInt(Cell::colspan));
        for (Cell cell : spanning) {
            int from = cell.column();
            int to = Math.min(columns, from + cell.colspan());
            if (to <= from) continue;
            distribute(min, from, to, Math.max(0f, cell.min()), max);
            distribute(max, from, to, Math.max(0f, cell.max()), max);
        }
    }

    /**
     * Raises {@code track[from..to)} until it sums to at least {@code wanted}.
     *
     * @param weights what to share the excess in proportion to — the columns' maximums, since that is
     *                what "how much does this column want" means. All-zero weights share it equally,
     *                which is the only answer available when no column has asked for anything.
     */
    private static void distribute(float[] track, int from, int to, float wanted, float[] weights) {
        float have = 0f;
        for (int c = from; c < to; c++) have += track[c];
        float excess = wanted - have;
        if (excess <= 0f) return;

        float weight = 0f;
        for (int c = from; c < to; c++) weight += weights[c];
        int span = to - from;
        for (int c = from; c < to; c++) {
            track[c] += weight > 0f ? excess * (weights[c] / weight) : excess / span;
        }
    }

    private static float sum(float[] values) {
        float total = 0f;
        for (float value : values) total += value;
        return total;
    }
}
