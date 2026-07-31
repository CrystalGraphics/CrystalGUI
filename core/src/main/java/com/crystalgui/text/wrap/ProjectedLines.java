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
        prefixDirty = true;
    }

    private void ensurePrefix() {
        if (!prefixDirty) return;
        if (viewLineStarts.length != projections.length + 1) {
            viewLineStarts = new int[projections.length + 1];
        }
        int running = 0;
        for (int row = 0; row < projections.length; row++) {
            viewLineStarts[row] = running;
            running += projections[row].viewLineCount();
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

        // binarySearch returns the insertion point negated when absent; a hit means the view line is the
        // first of its row, which is the common case for unwrapped documents and worth not re-deriving.
        int found = Arrays.binarySearch(viewLineStarts, 0, projections.length, clamped);
        int row = found >= 0 ? found : -found - 2;
        row = Math.max(0, Math.min(row, projections.length - 1));
        return new ModelPosition(row, clamped - viewLineStarts[row]);
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
