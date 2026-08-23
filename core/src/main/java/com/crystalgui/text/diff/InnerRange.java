package com.crystalgui.text.diff;

/**
 * A character-level change inside a line-level one — the word marks a diff view draws within a changed line.
 *
 * <p>Ported from {@code RangeMapping} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a> ({@code .../diff/rangeMapping.ts}), MIT.
 * <b>Modified:</b> flattened to eight ints rather than a pair of {@code Range} objects.</p>
 *
 * <p>Positions are (line, column), both zero-based and half-open at the end, in each text's own numbering.
 * Columns are character offsets within the line, not visual columns — the view resolves those against its
 * own tab handling.</p>
 */
public record InnerRange(
        int fromLine1, int fromColumn1, int toLine1, int toColumn1,
        int fromLine2, int fromColumn2, int toLine2, int toColumn2) {

    /** True when nothing was removed: text arrived at a point in side 1. */
    public boolean isInsertion() {
        return fromLine1 == toLine1 && fromColumn1 == toColumn1;
    }

    /** True when nothing was added. */
    public boolean isDeletion() {
        return fromLine2 == toLine2 && fromColumn2 == toColumn2;
    }
}
