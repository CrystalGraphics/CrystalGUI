package com.crystalgui.text.wrap;

/**
 * How one document row projects onto the visual rows it wraps into.
 *
 * <p><b>Ported from VS Code's {@code ModelLineProjectionData}</b> —
 * {@code src/vs/editor/common/modelLineProjectionData.ts}, microsoft/vscode, MIT licence. The binary
 * search in {@link #toViewPosition} and the affinity rules in {@link #normalize} are that file's, kept
 * operator-for-operator.</p>
 *
 * <pre>
 * model row:   "    return alpha + beta;"          breakOffsets = [16, 24]
 *
 * view line 0: "    return alpha"                  wrappedIndent = 4
 * view line 1: "     + beta;"                      (the leading 4 are the carried indent)
 * </pre>
 *
 * <h3>What was deliberately not ported</h3>
 * <p>VS Code's version also threads <b>injected text</b> — inlay hints, and decorations that add
 * characters the document does not contain — through every conversion, which is most of the original
 * file. This engine has no injected text, and porting the machinery for an absent feature would leave
 * four coordinate spaces to reason about where two suffice. If inlay hints ever arrive, the shape to
 * restore is in that file and the three call sites are marked in the original as
 * {@code offsetInInputWithInjections}.</p>
 *
 * <h3>Coordinates</h3>
 * <p>Everything here is a <b>character offset within the row</b>, not a document offset, and 0-based —
 * VS Code's columns are 1-based, so its {@code +1}/{@code -1} adjustments are absent by design rather
 * than by oversight. {@link ProjectedLines} is what turns a row-relative position into a document one.</p>
 */
public final class LineProjection {

    /**
     * Which side of a wrap boundary a position belongs to — VS Code's {@code PositionAffinity}.
     *
     * <p>An offset exactly at a break is genuinely two places: the end of one view line and the start of
     * the next. Both are correct, and which one is wanted depends on the question. A caret arriving by
     * pressing End wants {@link #LEFT}; the same offset arriving by pressing Home on the next line wants
     * {@link #RIGHT}. Without this the caret at a wrap point flickers between the two, which is the
     * single most visible way a soft-wrap implementation is wrong.</p>
     */
    public enum Affinity {
        /** No preference — the offset resolves to the view line that contains it. */
        NONE,
        /** Prefer the end of the earlier view line. */
        LEFT,
        /** Prefer the start of the later view line. */
        RIGHT
    }

    /** A position in view coordinates: which visual row, and how far into it. */
    public record ViewPosition(int viewLine, int column) {
    }

    /**
     * Where each view line ends, in row-relative character offsets.
     *
     * <p>The <b>last entry is the row's length</b>, not a break — so the array is never empty and
     * {@code breakOffsets.length} is the view line count with no special case for an unwrapped row.</p>
     */
    private final int[] breakOffsets;

    /** Columns of leading indent carried onto every continuation line. */
    private final int wrappedIndent;

    public LineProjection(int[] breakOffsets, int wrappedIndent) {
        if (breakOffsets == null || breakOffsets.length == 0) {
            throw new IllegalArgumentException("A projection needs at least one break offset (the row length)");
        }
        this.breakOffsets = breakOffsets;
        this.wrappedIndent = wrappedIndent;
    }

    /** The projection of a row that does not wrap — one view line, no carried indent. */
    public static LineProjection unwrapped(int rowLength) {
        return new LineProjection(new int[] { rowLength }, 0);
    }

    /** True when this row occupies exactly one visual row, which is the overwhelmingly common case. */
    public boolean isUnwrapped() {
        return breakOffsets.length == 1;
    }

    public int viewLineCount() {
        return breakOffsets.length;
    }

    public int wrappedIndent() {
        return wrappedIndent;
    }

    /** The row-relative offset at which {@code viewLine} begins. */
    public int viewLineStart(int viewLine) {
        return viewLine > 0 ? breakOffsets[viewLine - 1] : 0;
    }

    /** The row-relative offset at which {@code viewLine} ends. */
    public int viewLineEnd(int viewLine) {
        return breakOffsets[viewLine];
    }

    /** The lowest column a caret may occupy on {@code viewLine} — past the carried indent. */
    public int minColumn(int viewLine) {
        return viewLine > 0 ? wrappedIndent : 0;
    }

    /** The highest column a caret may occupy on {@code viewLine}, including the carried indent. */
    public int maxColumn(int viewLine) {
        int length = breakOffsets[viewLine] - viewLineStart(viewLine);
        return viewLine > 0 ? length + wrappedIndent : length;
    }

    /** The row-relative model offset a view position refers to. */
    public int toModelOffset(int viewLine, int column) {
        int offset = column;
        if (viewLine > 0) offset = Math.max(0, offset - wrappedIndent);
        return viewLine == 0 ? offset : breakOffsets[viewLine - 1] + offset;
    }

    /**
     * Which view line and column a row-relative offset lands on.
     *
     * <p>A binary search over {@link #breakOffsets} in which <b>the affinity changes the comparison
     * operators rather than adjusting the result afterwards</b> — {@code LEFT} tests {@code <= start} and
     * {@code > end}, everything else tests {@code < start} and {@code >= end}. That is what makes an
     * offset sitting exactly on a break resolve to the earlier line under {@code LEFT} and the later one
     * otherwise, in one pass. Adjusting afterwards is the obvious alternative and gets the row's final
     * offset wrong, because the last view line has no following line to be pushed onto.</p>
     */
    public ViewPosition toViewPosition(int offset, Affinity affinity) {
        int low = 0;
        int high = breakOffsets.length - 1;
        int mid = 0;
        int midStart = 0;

        while (low <= high) {
            mid = low + (high - low) / 2;
            int midStop = breakOffsets[mid];
            midStart = mid > 0 ? breakOffsets[mid - 1] : 0;

            if (affinity == Affinity.LEFT) {
                if (offset <= midStart) high = mid - 1;
                else if (offset > midStop) low = mid + 1;
                else break;
            } else {
                if (offset < midStart) high = mid - 1;
                else if (offset >= midStop) low = mid + 1;
                else break;
            }
        }
        // Falling out of the loop without a break leaves mid/midStart at the last probe, which clamps to
        // the nearest line rather than throwing. Ported deliberately: an offset past the end is reachable
        // from a stale caret during an edit, and clamping is what stops it becoming an exception.

        int column = offset - midStart;
        if (mid > 0) column += wrappedIndent;
        return new ViewPosition(mid, column);
    }

    /**
     * Moves a view position onto the side of a wrap boundary the affinity asks for.
     *
     * <p>Distinct from {@link #toViewPosition}: that answers "where is this offset", this answers "given a
     * position I already have, is it on the side I want". Pressing Left at the start of a continuation
     * line needs the second question — the offset does not change, only which of its two homes is meant.</p>
     */
    public ViewPosition normalize(int viewLine, int column, Affinity affinity) {
        if (affinity == Affinity.LEFT) {
            if (viewLine > 0 && column == minColumn(viewLine)) {
                return new ViewPosition(viewLine - 1, maxColumn(viewLine - 1));
            }
        } else if (affinity == Affinity.RIGHT) {
            if (viewLine < viewLineCount() - 1 && column == maxColumn(viewLine)) {
                return new ViewPosition(viewLine + 1, minColumn(viewLine + 1));
            }
        }
        return new ViewPosition(viewLine, column);
    }
}
