package com.crystalgui.text.cursor;

import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Box selection</b> — one selection per row, all at the same pair of columns.
 *
 * <p>Ported from VS Code's {@code common/cursor/cursorColumnSelection.ts}. It is the second of the two
 * gaps {@code AGENTS.md} records against this package, and it is the one that is genuinely a feature
 * rather than a nicety: editing a column of a table, or putting a caret at the end of twenty lines at
 * once, has no other spelling.</p>
 *
 * <h3>Columns are VISUAL, which is the whole of the port</h3>
 *
 * <p>A box is a rectangle <em>on screen</em>. Two rows whose text differs in tabs have the same character
 * column at different places and different character columns at the same place, so a box computed from
 * character offsets is not a box — it is a ragged edge that follows the text. Every position here is
 * therefore taken through {@link CursorColumns}, and the offsets come back out of it.</p>
 *
 * <h3>A short row still gets a caret</h3>
 *
 * <p>Clamped to its own end rather than skipped, and this is deliberate: the point of a box selection is
 * usually to type at the end of every line in it, and a row dropping out of the set because it happens
 * to be shorter is exactly the row somebody wanted to extend. VS Code and IntelliJ both clamp.</p>
 */
public final class ColumnSelection {

    private ColumnSelection() {
    }

    /**
     * The box between two offsets, one selection per row it spans.
     *
     * <p>Rows come back in document order and the <b>head's row is last</b> when the gesture went
     * downwards, so the caller can treat the final entry as the primary — which is what keeps the
     * blinking caret on the row the pointer is actually over.</p>
     */
    public static List<Selection> between(Rope document, int anchor, int head, int tabSize) {
        int stops = Math.max(1, tabSize);
        int anchorRow = document.offsetToPoint(anchor).row();
        int headRow = document.offsetToPoint(head).row();
        int anchorColumn = visualColumnAt(document, anchor, anchorRow, stops);
        int headColumn = visualColumnAt(document, head, headRow, stops);

        int from = Math.min(anchorRow, headRow);
        int to = Math.max(anchorRow, headRow);
        boolean downwards = headRow >= anchorRow;

        List<Selection> box = new ArrayList<>(to - from + 1);
        for (int step = 0; step <= to - from; step++) {
            int row = downwards ? from + step : to - step;
            int start = offsetAtVisualColumn(document, row, anchorColumn, stops);
            int end = offsetAtVisualColumn(document, row, headColumn, stops);
            // EVERY ROW GETS ONE, including where the two columns land on the same character: a box of
            // bare carets is the gesture's most common use, and dropping the empty ones would leave the
            // caller with a selection on some rows and nothing on others.
            box.add(start == end ? Selection.caret(start) : new Selection(start, end));
        }
        return box;
    }

    private static int visualColumnAt(Rope document, int offset, int row, int tabSize) {
        int column = offset - document.lineStartOffset(row);
        return CursorColumns.visibleColumn(document.line(row), Math.max(0, column), tabSize);
    }

    /**
     * Where {@code visualColumn} falls on {@code row}, as a document offset.
     *
     * <p>Past the end of a short row this answers its end — see the class note. Inside a tab it answers
     * that tab's own boundary, which is {@link CursorColumns}' rule and the reason a click inside one
     * lands on a side of it rather than in the middle of a character that has no middle.</p>
     */
    private static int offsetAtVisualColumn(Rope document, int row, int visualColumn, int tabSize) {
        String line = document.line(row);
        CursorColumns.Line drawn = CursorColumns.expand(line, tabSize);
        int column = visualColumn >= drawn.display().length()
                ? line.length() : drawn.columnOf(visualColumn);
        return document.lineStartOffset(row) + Math.min(column, line.length());
    }
}
