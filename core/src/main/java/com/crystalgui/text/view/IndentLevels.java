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


    /**
     * The block enclosing {@code row} — start row, end row, and its indent level.
     *
     * <p><b>Ported from VS Code's {@code GuidesTextModelPart.getActiveIndentGuide}</b>
     * ({@code src/vs/editor/common/model/guidesTextModelPart.ts}, MIT). This is what draws IntelliJ's
     * brighter guide: the one guide belonging to the scope you are actually inside.</p>
     *
     * <p><b>Rows here are 0-based; the original's line numbers are 1-based.</b> That is the whole
     * transcription risk in this method — every bound and every neighbour scan shifts by one, and an
     * off-by-one produces a plausible-looking block one line out rather than an error.</p>
     *
     * <h3>The special case at distance 1</h3>
     * <p>Standing on a line that <em>opens</em> a scope, the active block is the <b>child</b>, not the
     * parent — put the caret on a line ending in an opening brace and the guide that lights up is the
     * body's. The same applies in reverse on the line that closes one. Without those two carve-outs the
     * highlight jumps a level every time you touch a brace line, which is where you look at it most.</p>
     *
     * @param minRow lowest row to search — pass the visible range, so a long file costs the viewport
     * @param maxRow highest row to search
     */
    public static ActiveGuide activeGuideFor(Rope document, int row, int minRow, int maxRow,
                                             int indentSize, int tabSize, boolean offSide) {
        int rows = document.lineCount();
        if (rows == 0) return new ActiveGuide(0, 0, 0);
        int at = Math.max(0, Math.min(row, rows - 1));
        int size = Math.max(1, indentSize);

        // aboveIndex, aboveIndent, belowIndex, belowIndent -- the original's four locals per direction,
        // carried in an array so the two resolve helpers can update them in place.
        int[] up = { -2, -1, -2, -1 };
        int[] down = { -2, -1, -2, -1 };

        int startRow = 0;
        int endRow = 0;
        int indent = 0;
        int initialIndent = 0;
        boolean goUp = true;
        boolean goDown = true;

        for (int distance = 0; goUp || goDown; distance++) {
            int upRow = at - distance;
            int downRow = at + distance;

            if (distance > 1 && (upRow < 0 || upRow < minRow)) goUp = false;
            if (distance > 1 && (downRow > rows - 1 || downRow > maxRow)) goDown = false;
            // The original's guard against a pathological file, kept: this walks outward a line at a time,
            // so a document that is one long block would otherwise scan all of it.
            if (distance > 50000) {
                goUp = false;
                goDown = false;
            }

            int upLevel = -1;
            if (goUp && upRow >= 0) {
                int current = computeIndentLevel(document.line(upRow), tabSize);
                if (current >= 0) {
                    up[2] = upRow;
                    up[3] = current;
                    upLevel = ceilDiv(current, size);
                } else {
                    resolveUp(document, upRow, tabSize, up);
                    upLevel = forBlankLine(offSide, up[1], up[3], size);
                }
            }

            int downLevel = -1;
            if (goDown && downRow <= rows - 1) {
                int current = computeIndentLevel(document.line(downRow), tabSize);
                if (current >= 0) {
                    down[0] = downRow;
                    down[1] = current;
                    downLevel = ceilDiv(current, size);
                } else {
                    resolveDown(document, downRow, tabSize, down);
                    downLevel = forBlankLine(offSide, down[1], down[3], size);
                }
            }

            if (distance == 0) {
                initialIndent = upLevel;
                continue;
            }

            if (distance == 1) {
                // Opening a scope: the CHILD block is the active one, not the parent.
                if (downRow <= rows - 1 && downLevel >= 0 && initialIndent + 1 == downLevel) {
                    goUp = false;
                    startRow = downRow;
                    endRow = downRow;
                    indent = downLevel;
                    continue;
                }
                // Closing one: the same, in reverse.
                if (upRow >= 0 && upLevel >= 0 && upLevel - 1 == initialIndent) {
                    goDown = false;
                    startRow = upRow;
                    endRow = upRow;
                    indent = upLevel;
                    continue;
                }
                startRow = at;
                endRow = at;
                indent = initialIndent;
                if (indent == 0) return new ActiveGuide(startRow, endRow, indent);
            }

            if (goUp) {
                if (upLevel >= indent) startRow = upRow;
                else goUp = false;
            }
            if (goDown) {
                if (downLevel >= indent) endRow = downRow;
                else goDown = false;
            }
        }
        return new ActiveGuide(startRow, endRow, indent);
    }

    /** The block enclosing a position: the rows it spans, and which guide level is its own. */
    public record ActiveGuide(int startRow, int endRow, int indent) {
        /**
         * Whether the guide at {@code level} on {@code row} is this block's.
         *
         * <p>{@code indent - 1} because guides are drawn at columns 0..n-1 for n levels, so the guide
         * marking a block of indent n is the one at level n-1.</p>
         */
        public boolean covers(int row, int level) {
            return indent > 0 && level == indent - 1 && row >= startRow && row <= endRow;
        }
    }

    /** {@code up_resolveIndents} — nearest content rows either side, cached across the walk. */
    private static void resolveUp(Rope document, int row, int tabSize, int[] state) {
        if (state[0] != -1 && (state[0] == -2 || state[0] > row)) {
            state[0] = -1;
            state[1] = -1;
            for (int scan = row - 1; scan >= 0; scan--) {
                int indent = computeIndentLevel(document.line(scan), tabSize);
                if (indent >= 0) {
                    state[0] = scan;
                    state[1] = indent;
                    break;
                }
            }
        }
        if (state[2] == -2) {
            state[2] = -1;
            state[3] = -1;
            for (int scan = row + 1; scan < document.lineCount(); scan++) {
                int indent = computeIndentLevel(document.line(scan), tabSize);
                if (indent >= 0) {
                    state[2] = scan;
                    state[3] = indent;
                    break;
                }
            }
        }
    }

    /**
     * {@code down_resolveIndents} — the mirror image, and deliberately NOT folded into the one above.
     *
     * <p>The guard conditions differ: walking up re-resolves the row <em>above</em> when it has been
     * passed, walking down re-resolves the row <em>below</em>. Merging them into one method with a
     * direction flag makes that asymmetry look like a bug and invites somebody to "fix" it.</p>
     */
    private static void resolveDown(Rope document, int row, int tabSize, int[] state) {
        if (state[0] == -2) {
            state[0] = -1;
            state[1] = -1;
            for (int scan = row - 1; scan >= 0; scan--) {
                int indent = computeIndentLevel(document.line(scan), tabSize);
                if (indent >= 0) {
                    state[0] = scan;
                    state[1] = indent;
                    break;
                }
            }
        }
        if (state[2] != -1 && (state[2] == -2 || state[2] < row)) {
            state[2] = -1;
            state[3] = -1;
            for (int scan = row + 1; scan < document.lineCount(); scan++) {
                int indent = computeIndentLevel(document.line(scan), tabSize);
                if (indent >= 0) {
                    state[2] = scan;
                    state[3] = indent;
                    break;
                }
            }
        }
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
