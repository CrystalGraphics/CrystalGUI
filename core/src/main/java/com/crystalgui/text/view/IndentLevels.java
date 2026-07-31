package com.crystalgui.text.view;

import com.crystalgui.text.Rope;

/**
 * How many indent guides each row should draw.
 *
 * <p><b>Ported from VS Code's {@code GuidesTextModelPart.getLinesIndentGuides}</b> and
 * {@code computeIndentLevel} — {@code src/vs/editor/common/model/guidesTextModelPart.ts} and
 * {@code src/vs/editor/common/model/utils.ts}, microsoft/vscode, MIT licence.</p>
 *
 * <h3>The whole difficulty is blank lines</h3>
 * <p>A line with content states its own indent, and that part is arithmetic. A <b>blank</b> line has no
 * indent of its own and must borrow one, and the rule for which is neither obvious nor guessable — it is
 * the difference between guides that flow continuously through a gap in a function and guides that
 * visibly break every time somebody leaves a blank line:</p>
 * <table>
 *   <caption>What a blank line borrows</caption>
 *   <tr><th>Situation</th><th>Guides</th></tr>
 *   <tr><td>At the top or bottom of the file</td><td>none — nothing encloses it</td></tr>
 *   <tr><td>Indented less above than below</td><td>one <em>more</em> than above: the block below is opening</td></tr>
 *   <tr><td>Equal above and below</td><td>that level — between two siblings</td></tr>
 *   <tr><td>Indented more above than below</td><td>one more than below: the block above is closing</td></tr>
 * </table>
 *
 * <p>The last row is where {@code offSide} comes in — a language whose blocks end by dedenting alone
 * (Python, YAML) closes the block <em>at</em> the blank line rather than after it, so it takes the level
 * below instead. Carried because the rule is wrong for those languages and this is the one place it is
 * decided.</p>
 */
public final class IndentLevels {

    private IndentLevels() {
    }

    /**
     * The visible column at which a line's text starts, or {@code -1} when it has none.
     *
     * <p>Visible columns, so a tab counts to its stop — otherwise a file indented with tabs reports one
     * level however deep it is. The {@code -1} is what marks a whitespace-only line as having no indent
     * <em>of its own</em>, which is the input the blank-line rule above works from; returning 0 instead
     * would silently claim such a line is at the outermost level.</p>
     */
    public static int computeIndentLevel(String line, int tabSize) {
        int indent = 0;
        int i = 0;
        int length = line.length();
        while (i < length) {
            char c = line.charAt(i);
            if (c == ' ') indent++;
            else if (c == '\t') indent = indent - indent % tabSize + tabSize;
            else break;
            i++;
        }
        return i == length ? -1 : indent;
    }

    /**
     * Guide counts for rows {@code from}..{@code to} inclusive.
     *
     * <p>A range rather than one row at a time because a blank line's answer depends on the nearest
     * content line in <em>both</em> directions, and computing that per row rescans the same neighbours
     * repeatedly. The scan carries them forward exactly as the original does.</p>
     *
     * @param indentSize columns per indent level — what one guide represents
     * @param offSide    true for a language whose blocks end by dedent alone (Python, YAML)
     */
    public static int[] guidesFor(Rope document, int from, int to, int indentSize, int tabSize,
                                  boolean offSide) {
        int rows = document.lineCount();
        int first = Math.max(0, Math.min(from, Math.max(0, rows - 1)));
        int last = Math.max(first, Math.min(to, rows - 1));
        int size = Math.max(1, indentSize);

        int[] result = new int[last - first + 1];

        // -2 marks "not computed yet", -1 marks "there is no such line". Two distinct states, and
        // collapsing them makes the top of a file look like an uncomputed cache and rescan every row.
        int aboveIndex = -2;
        int aboveIndent = -1;
        int belowIndex = -2;
        int belowIndent = -1;

        for (int row = first; row <= last; row++) {
            int index = row - first;
            int current = computeIndentLevel(document.line(row), tabSize);
            if (current >= 0) {
                aboveIndex = row;
                aboveIndent = current;
                result[index] = ceilDiv(current, size);
                continue;
            }

            if (aboveIndex == -2) {
                aboveIndex = -1;
                aboveIndent = -1;
                for (int scan = row - 1; scan >= 0; scan--) {
                    int indent = computeIndentLevel(document.line(scan), tabSize);
                    if (indent >= 0) {
                        aboveIndex = scan;
                        aboveIndent = indent;
                        break;
                    }
                }
            }

            if (belowIndex != -1 && (belowIndex == -2 || belowIndex < row)) {
                belowIndex = -1;
                belowIndent = -1;
                for (int scan = row + 1; scan < rows; scan++) {
                    int indent = computeIndentLevel(document.line(scan), tabSize);
                    if (indent >= 0) {
                        belowIndex = scan;
                        belowIndent = indent;
                        break;
                    }
                }
            }

            result[index] = forBlankLine(offSide, aboveIndent, belowIndent, size);
        }
        return result;
    }

    /** The blank-line rule — the table in this class's header, verbatim. */
    private static int forBlankLine(boolean offSide, int aboveIndent, int belowIndent, int indentSize) {
        if (aboveIndent == -1 || belowIndent == -1) return 0;
        if (aboveIndent < belowIndent) return 1 + aboveIndent / indentSize;
        if (aboveIndent == belowIndent) return ceilDiv(belowIndent, indentSize);
        return offSide ? ceilDiv(belowIndent, indentSize) : 1 + belowIndent / indentSize;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
