package com.crystalgui.text.wrap;

import com.crystalgui.text.Rope;

import java.util.Arrays;

/**
 * The whole document's projection — which visual row is which document row, and back.
 *
 * <p><b>Ported from VS Code's {@code ViewModelLinesFromProjectedModel}</b>
 * ({@code src/vs/editor/common/viewModel/viewModelLines.ts}) over its {@code PrefixSumComputer}
 * ({@code src/vs/editor/common/model/prefixSumComputer.ts}), microsoft/vscode, MIT licence.</p>
 *
 * <p>Two coordinate spaces meet here and the whole of soft wrap is keeping them apart:</p>
 * <table>
 *   <caption>The two spaces</caption>
 *   <tr><th>Space</th><th>Unit</th><th>Who thinks in it</th></tr>
 *   <tr><td><b>Model</b></td><td>document row, character offset</td>
 *       <td>the buffer, edits, undo, search, syntax</td></tr>
 *   <tr><td><b>View</b></td><td>visual row, column</td>
 *       <td>painting, scrolling, hit testing, Up/Down, Home/End</td></tr>
 * </table>
 *
 * <p>Everything that survives a reload is model; everything that changes when the window is resized is
 * view. That is the same boundary the project already draws between document and view state for undo, and
 * it is why a soft wrap is not an edit.</p>
 *
 * <h3>Lazy prefix sums</h3>
 * <p>{@code viewLineStarts[row]} is the first view line of {@code row}, rebuilt on the next query after
 * any change rather than on the change itself — so a burst of edits in one frame costs one rebuild, and a
 * frame that changed nothing costs nothing. The same trade {@code VariableHeightStrategy} documents, for
 * the same access pattern: many queries per frame, mutations in bursts.</p>
 */
public final class ProjectedLines {

    /** A position in view space, resolved against the whole document. */
    public record ViewPosition(int viewLine, int column) {
    }

    /** Which document row a view line belongs to, and which of that row's view lines it is. */
    public record ModelPosition(int row, int viewLineInRow) {
    }

    private LineBreaksComputer computer;
    private LineProjection[] projections = new LineProjection[0];

    /**
     * Whether each row is shown at all — {@code false} for a row hidden inside a collapsed fold.
     *
     * <p><b>Ported from VS Code's {@code IModelLineProjection.isVisible}</b>
     * ({@code src/vs/editor/common/viewModel/modelLineProjection.ts}), where a hidden row's
     * {@code getViewLineCount()} returns {@code 0} and every conversion falls out of that one fact. Folding
     * needs no separate coordinate space: a folded row simply projects onto <b>zero</b> view lines, and the
     * prefix sums that already handle a row wrapping onto three handle a row occupying none.</p>
     *
     * <p>VS Code stores the flag <em>on</em> the projection object and swaps the object to change it. Here
     * it is a parallel array, because {@link LineProjection} is immutable and shared — a wrap result
     * describes the text, not who is looking at it, and making it carry view state would mean reallocating
     * a projection every time a block is folded.</p>
     */
    private boolean[] visible = new boolean[0];

    /** {@code viewLineStarts[row]} = first view line of {@code row}; length is {@code rowCount + 1}. */
    private int[] viewLineStarts = new int[] { 0 };
    private boolean prefixDirty = true;

    public ProjectedLines(LineBreaksComputer computer) {
        this.computer = computer == null ? LineBreaksComputer.none() : computer;
    }

    /**
     * Reprojects every row.
     *
     * <p>O(n) in rows, and correct to call on a resize — which is the point: a wrap width change
     * invalidates every projection at once, and there is no incremental answer to it.</p>
     */
    public void rebuild(Rope document) {
        int rows = document.lineCount();
        projections = new LineProjection[rows];
        for (int row = 0; row < rows; row++) {
            projections[row] = computer.project(document.line(row));
        }
        // Visibility survives a reprojection when the row count is unchanged, which is what a resize is --
        // wrapping differently does not unfold anything.
        //
        // NOT what keeps folds alive across a resize: the folding model reapplies its hidden rows every
        // frame, so dropping this guard leaves the picture correct and a mutation of it is not caught by
        // any widget test. What it buys is that the index is never briefly WRONG -- reproject() is also
        // reachable outside the frame loop, from setSoftWrap/setFontSize/setTabSize, and a caller reading
        // viewLineCount() in between would otherwise see every collapsed block momentarily open.
        if (visible.length != rows) {
            visible = new boolean[rows];
            Arrays.fill(visible, true);
        }
        prefixDirty = true;
    }

    /** Swaps the computer — a wrap-width or wrap-mode change — and reprojects. */
    public void setComputer(LineBreaksComputer newComputer, Rope document) {
        this.computer = newComputer == null ? LineBreaksComputer.none() : newComputer;
        rebuild(document);
    }

    /**
     * Reprojects a contiguous run of rows after an edit, shifting the tail.
     *
     * <p>An edit almost always touches one row, so reprojecting the document per keystroke would make
     * typing O(document). {@code removed} and {@code added} are row counts, so an Enter is
     * {@code (row, 1, 2)} and a line deletion is {@code (row, 2, 1)}.</p>
     */
    public void rowsChanged(Rope document, int fromRow, int removed, int added) {
        if (projections.length == 0 || fromRow < 0) {
            rebuild(document);
            return;
        }
        int oldCount = projections.length;
        int newCount = oldCount - removed + added;
        if (newCount < 0 || fromRow + removed > oldCount || newCount != document.lineCount()) {
            // The caller's row arithmetic disagrees with the document. Reprojecting everything is always
            // correct, so it is the fallback rather than an exception -- a stale index is a wrong picture
            // on screen, which is far worse than a slow frame.
            rebuild(document);
            return;
        }

        LineProjection[] updated = new LineProjection[newCount];
        System.arraycopy(projections, 0, updated, 0, fromRow);
        for (int i = 0; i < added; i++) {
            updated[fromRow + i] = computer.project(document.line(fromRow + i));
        }
        System.arraycopy(projections, fromRow + removed, updated, fromRow + added, oldCount - fromRow - removed);
        projections = updated;

        // Visibility shifts with the rows, and the ADDED rows are visible: text typed inside a collapsed
        // block has to be somewhere the caret can be. The folding model recomputes and reapplies right
        // after, so this only has to be sane in the interim, not final.
        boolean[] shifted = new boolean[newCount];
        Arrays.fill(shifted, true);
        System.arraycopy(visible, 0, shifted, 0, Math.min(fromRow, visible.length));
        System.arraycopy(visible, fromRow + removed, shifted, fromRow + added, oldCount - fromRow - removed);
        visible = shifted;
        prefixDirty = true;
    }

    /**
     * Hides exactly the rows covered by {@code ranges}, showing every other row.
     *
     * <p><b>Ported from VS Code's {@code ViewModelLinesFromProjectedModel.setHiddenAreas}</b>
     * ({@code src/vs/editor/common/viewModel/viewModelLines.ts}). Absolute rather than incremental — the
     * argument is the whole set of hidden rows, so unfolding is expressed by a range no longer being in it
     * and there is no "unhide" to forget to call.</p>
     *
     * <p>Ranges must be sorted and non-overlapping, which is what {@code FoldingModel.hiddenRows()}
     * produces.</p>
     *
     * @return whether anything changed, so a caller can skip relayout when it did not
     */
    public boolean setHiddenAreas(int[][] ranges) {
        boolean changed = false;
        int rangeIndex = 0;
        boolean hasVisibleLine = false;

        for (int row = 0; row < visible.length; row++) {
            while (rangeIndex < ranges.length && ranges[rangeIndex][1] < row) rangeIndex++;
            boolean shouldBeVisible = rangeIndex >= ranges.length
                    || row < ranges[rangeIndex][0] || row > ranges[rangeIndex][1];
            if (shouldBeVisible) hasVisibleLine = true;
            if (visible[row] != shouldBeVisible) {
                visible[row] = shouldBeVisible;
                changed = true;
            }
        }

        // VS Code throws "Cannot have all lines hidden" here. A document with nothing visible has no view
        // line for the caret to sit on and no row for the fold indicator that would reopen it, so it is
        // unrecoverable from the user's side -- but a THROW would take down the frame over a view-state
        // bug. Showing the first row instead degrades to something the user can act on.
        if (!hasVisibleLine && visible.length > 0) {
            visible[0] = true;
            changed = true;
        }
        if (changed) prefixDirty = true;
        return changed;
    }

    /** Whether a row is shown at all — {@code false} inside a collapsed fold. */
    public boolean isVisible(int row) {
        return row < 0 || row >= visible.length || visible[row];
    }

    private void ensurePrefix() {
        if (!prefixDirty) return;
        if (viewLineStarts.length != projections.length + 1) {
            viewLineStarts = new int[projections.length + 1];
        }
        int running = 0;
        for (int row = 0; row < projections.length; row++) {
            viewLineStarts[row] = running;
            running += isVisible(row) ? projections[row].viewLineCount() : 0;
        }
        viewLineStarts[projections.length] = running;
        prefixDirty = false;
    }

    public int rowCount() {
        return projections.length;
    }

    /** Total visual rows — what the scroll height is computed from. */
    public int viewLineCount() {
        ensurePrefix();
        return viewLineStarts[projections.length];
    }

    public LineProjection projectionOf(int row) {
        return projections[Math.max(0, Math.min(row, projections.length - 1))];
    }

    /** The first view line of a document row — where its gutter number goes. */
    public int firstViewLineOfRow(int row) {
        ensurePrefix();
        return viewLineStarts[Math.max(0, Math.min(row, projections.length))];
    }

    /** Which document row a view line belongs to. */
    public ModelPosition modelAt(int viewLine) {
        ensurePrefix();
        if (projections.length == 0) return new ModelPosition(0, 0);
        int clamped = Math.max(0, Math.min(viewLine, viewLineCount() - 1));

        // VS Code's PrefixSumComputer.getIndexOf, and the loop shape matters once folding exists. A hidden
        // row occupies ZERO view lines, so consecutive entries in viewLineStarts are equal -- and
        // Arrays.binarySearch over duplicates may return ANY of them, which lands on a hidden row and
        // reports the caret inside a collapsed block. Testing the row's half-open span [start, stop)
        // instead makes a zero-width row fail both bounds and get stepped over, which is the same reason
        // the original does not use a plain binary search either.
        int low = 0;
        int high = projections.length - 1;
        int mid = 0;
        int midStart = 0;
        while (low <= high) {
            mid = low + (high - low) / 2;
            midStart = viewLineStarts[mid];
            int midStop = viewLineStarts[mid + 1];
            if (clamped < midStart) high = mid - 1;
            else if (clamped >= midStop) low = mid + 1;
            else break;
        }
        return new ModelPosition(mid, clamped - midStart);
    }

    /** The document offset a view position refers to. */
    public int toDocumentOffset(Rope document, int viewLine, int column) {
        ModelPosition model = modelAt(viewLine);
        int inRow = projectionOf(model.row()).toModelOffset(model.viewLineInRow(), column);
        return document.lineStartOffset(model.row()) + inRow;
    }

    /** Where a document offset sits in view space. */
    public ViewPosition toViewPosition(Rope document, int offset, LineProjection.Affinity affinity) {
        int clamped = Math.max(0, Math.min(offset, document.length()));
        int row = document.offsetToPoint(clamped).row();

        // A HIDDEN row has no view line to name, so walk to the nearest visible one above it -- VS Code's
        // convertModelPositionToViewPosition, whose default direction is upwards (its belowHiddenRanges
        // flag picks the other way). Without this an offset inside a collapsed block resolves past the end
        // of the view: the row still reports a first view line, but it is the line where the fold RESUMES,
        // which is one past the last real one. Reachable from search, from Ctrl+End, and from any caret
        // that was already there when the block closed.
        if (!isVisible(row)) {
            int visible = row;
            while (visible > 0 && !isVisible(visible)) visible--;
            if (!isVisible(visible)) {
                // Everything above is hidden too; take the first visible row below instead of giving up.
                visible = row;
                while (visible < projections.length - 1 && !isVisible(visible)) visible++;
            }
            row = visible;
            // The END of that row, since the hidden content followed it.
            LineProjection projection = projectionOf(row);
            int last = projection.viewLineCount() - 1;
            return new ViewPosition(firstViewLineOfRow(row) + last, projection.maxColumn(last));
        }

        int inRow = clamped - document.lineStartOffset(row);
        LineProjection.ViewPosition local = projectionOf(row).toViewPosition(inRow, affinity);
        return new ViewPosition(firstViewLineOfRow(row) + local.viewLine(), local.column());
    }

    /**
     * The text painted on a view line, <b>without</b> the carried indent.
     *
     * <p>The indent is a column offset, not characters — returning it as spaces would make it selectable
     * and copyable, and pasting a soft wrap's indentation into a file is exactly the failure that makes
     * people turn soft wrap off.</p>
     */
    public String viewLineText(Rope document, int viewLine) {
        ModelPosition model = modelAt(viewLine);
        LineProjection projection = projectionOf(model.row());
        String text = document.line(model.row());
        int from = projection.viewLineStart(model.viewLineInRow());
        int to = Math.min(text.length(), projection.viewLineEnd(model.viewLineInRow()));
        return from >= to ? "" : text.substring(from, to);
    }
}
