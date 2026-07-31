package com.crystalgui.text.cursor;

/**
 * The difference between a <b>column</b> and a <b>visible column</b> — ported from VS Code's
 * {@code CursorColumns}.
 *
 * <p>A column is an offset into a line. A visible column is where that offset lands on screen. They
 * differ exactly when a tab is involved, because a tab advances to the next <em>tab stop</em> rather than
 * by one character. Conflating them is why a tab-indented file misaligns: the caret walks one position
 * per tab while the text jumps a stop.</p>
 *
 * <p>Source: {@code src/vs/editor/common/core/cursorColumns.ts}, microsoft/vscode, MIT licence.</p>
 *
 * <h3>Expanded for display, tabs kept in the document</h3>
 * <p>{@link #expand} produces the string actually drawn, with tabs replaced by spaces to their stops, and
 * <b>both</b> directions of the mapping between document columns and display indices. The buffer still
 * holds a tab — nothing about the document changes — while measurement, caret placement and hit testing
 * all agree, because all three go through these maps rather than each deciding for itself.</p>
 */
public final class CursorColumns {

    private CursorColumns() {
    }

    /**
     * One line, expanded.
     *
     * @param display        what is drawn: tabs replaced by spaces to their stops
     * @param columnToDisplay display index for each document column; length is {@code line.length() + 1}
     * @param displayToColumn document column for each display index; every index inside a tab maps back
     *                        to that tab's own column, so a click inside one lands on a side of it rather
     *                        than partway through a character that has no halves
     */
    public record Line(String display, int[] columnToDisplay, int[] displayToColumn) {

        public int displayIndexOf(int column) {
            return columnToDisplay[Math.max(0, Math.min(column, columnToDisplay.length - 1))];
        }

        public int columnOf(int displayIndex) {
            return displayToColumn[Math.max(0, Math.min(displayIndex, displayToColumn.length - 1))];
        }
    }

    public static Line expand(String line, int tabSize) {
        int stops = Math.max(1, tabSize);
        StringBuilder display = new StringBuilder(line.length());
        int[] columnToDisplay = new int[line.length() + 1];

        for (int column = 0; column < line.length(); column++) {
            columnToDisplay[column] = display.length();
            char c = line.charAt(column);
            if (c == '\t') {
                // To the NEXT STOP, which depends on where we already are -- not a fixed number of
                // spaces. `ab\tc` fills to column 4, `a\tc` fills to column 4 as well, and that is the
                // whole point of a tab stop.
                int width = stops - (display.length() % stops);
                for (int i = 0; i < width; i++) display.append(' ');
            } else {
                display.append(c);
            }
        }
        columnToDisplay[line.length()] = display.length();

        int[] displayToColumn = new int[display.length() + 1];
        int column = 0;
        for (int index = 0; index <= display.length(); index++) {
            while (column < line.length() && columnToDisplay[column + 1] <= index) column++;
            displayToColumn[index] = column;
        }
        return new Line(display.toString(), columnToDisplay, displayToColumn);
    }

    /** Where {@code column} lands on screen, counted in characters rather than pixels. */
    public static int visibleColumn(String line, int column, int tabSize) {
        return expand(line, tabSize).displayIndexOf(column);
    }
}
