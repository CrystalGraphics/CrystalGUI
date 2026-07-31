package com.crystalgui.text.cursor;

import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.ProjectedLines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Caret movement — ported from VS Code's {@code MoveOperations}.
 *
 * <p>Source: {@code src/vs/editor/common/cursor/cursorMoveOperations.ts}, microsoft/vscode, MIT licence.
 * </p>
 *
 * <p>Pure functions over a document and a set of selections, deliberately: movement is where an editor's
 * feel lives, and every rule here is worth a two-line unit test rather than a simulated key press through
 * a window with fonts and a style engine attached.</p>
 */
public final class MoveOperations {

    private MoveOperations() {
    }

    /**
     * Left/Right for every caret.
     *
     * <p><b>A plain horizontal move with a selection cancels it at the corresponding edge</b> rather than
     * moving one character from the head — {@code moveLeft} puts the caret at the selection's start and
     * goes no further. Moving from the head lands one character <em>inside</em> the text just deselected,
     * which is wrong in the way people feel without being able to name.</p>
     *
     * <p>Word moves do not take that shortcut: they move by word from the active position, matching
     * {@code cursorWordLeft}/{@code cursorWordRight}.</p>
     */
    public static List<Selection> horizontal(Rope document, List<Selection> selections, int direction,
                                             boolean extend, boolean byWord, WordClassifier classifier) {
        List<Selection> moved = new ArrayList<>(selections.size());
        for (Selection selection : selections) {
            int head;
            if (byWord) {
                head = direction < 0
                        ? WordOperations.previousWordStart(document, selection.head(), classifier)
                        : WordOperations.nextWordEnd(document, selection.head(), classifier);
            } else if (!extend && !selection.isEmpty()) {
                head = direction < 0 ? selection.start() : selection.end();
            } else {
                head = selection.head() + direction;
            }
            head = Math.max(0, Math.min(head, document.length()));
            moved.add(extend ? selection.withHead(head) : Selection.caret(head));
        }
        return moved;
    }

    /** The result of a vertical move: where the carets went, and what column each is still aiming for. */
    public record Vertical(List<Selection> selections, int[] goalColumns) {
    }

    /**
     * Up/Down for every caret, each aiming for <b>its own</b> remembered column.
     *
     * <p>VS Code calls the memory {@code leftoverVisibleColumns} and keeps it per cursor. One shared value
     * is wrong the moment two carets sit in different columns: whichever moved last imposes its column on
     * the rest, and a rectangular block of carets degrades into a ragged one. Keeping it per caret is also
     * what makes down-through-a-short-line-and-back return to where it started.</p>
     *
     * @param goals one per selection, {@code -1} where none is remembered; a length mismatch is treated
     *              as "no goals", because a wrong goal is worse than none
     */
    public static Vertical vertical(Rope document, List<Selection> selections, int[] goals,
                                    int rows, boolean extend) {
        int[] previous = goals != null && goals.length == selections.size()
                ? goals : filled(selections.size());

        List<Selection> moved = new ArrayList<>(selections.size());
        int[] next = new int[selections.size()];
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.get(i);
            TextPoint point = document.offsetToPoint(selection.head());
            int goal = previous[i] >= 0 ? previous[i] : point.column();
            int row = Math.max(0, Math.min(document.lineCount() - 1, point.row() + rows));
            int offset = document.pointToOffset(new TextPoint(row, goal));
            moved.add(extend ? selection.withHead(offset) : Selection.caret(offset));
            next[i] = goal;
        }
        return new Vertical(moved, next);
    }

    /**
     * Up/Down through <b>visual</b> rows, for a soft-wrapped view.
     *
     * <p>The difference is not cosmetic. With wrap on, Down must reach the next line <em>on screen</em>;
     * skipping to the next document row means a long paragraph is one keypress tall and the caret jumps
     * a screenful, which is the behaviour every editor was judged on and none of them ships.</p>
     *
     * <p>The goal column becomes a <b>view</b> column, so a caret travelling down a wrapped paragraph
     * holds its horizontal place the same way it does through unwrapped rows. Both halves fall out of
     * doing the arithmetic in view space and converting once at each end.</p>
     */
    public static Vertical verticalInView(Rope document, ProjectedLines projections,
                                          List<Selection> selections, int[] goals,
                                          int rows, boolean extend) {
        int[] previous = goals != null && goals.length == selections.size()
                ? goals : filled(selections.size());
        int lastViewLine = Math.max(0, projections.viewLineCount() - 1);

        List<Selection> moved = new ArrayList<>(selections.size());
        int[] next = new int[selections.size()];
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.get(i);
            ProjectedLines.ViewPosition view = projections.toViewPosition(
                    document, selection.head(), LineProjection.Affinity.LEFT);
            int goal = previous[i] >= 0 ? previous[i] : view.column();
            int target = Math.max(0, Math.min(lastViewLine, view.viewLine() + rows));

            // Clamp to the target line's own extent: a short view line must not swallow the goal, or the
            // caret slides to the start of the line below on the way past.
            ProjectedLines.ModelPosition model = projections.modelAt(target);
            LineProjection projection = projections.projectionOf(model.row());
            int column = Math.max(projection.minColumn(model.viewLineInRow()),
                    Math.min(goal, projection.maxColumn(model.viewLineInRow())));

            int offset = projections.toDocumentOffset(document, target, column);
            moved.add(extend ? selection.withHead(offset) : Selection.caret(offset));
            next[i] = goal;
        }
        return new Vertical(moved, next);
    }

    /** The start of the caret's own view line — Home, when wrapped. */
    public static int viewLineStart(Rope document, ProjectedLines projections, int head) {
        ProjectedLines.ViewPosition view = projections.toViewPosition(
                document, head, LineProjection.Affinity.RIGHT);
        ProjectedLines.ModelPosition model = projections.modelAt(view.viewLine());
        LineProjection projection = projections.projectionOf(model.row());
        return projections.toDocumentOffset(document, view.viewLine(),
                projection.minColumn(model.viewLineInRow()));
    }

    /** The end of the caret's own view line — End, when wrapped. */
    public static int viewLineEnd(Rope document, ProjectedLines projections, int head) {
        ProjectedLines.ViewPosition view = projections.toViewPosition(
                document, head, LineProjection.Affinity.LEFT);
        ProjectedLines.ModelPosition model = projections.modelAt(view.viewLine());
        LineProjection projection = projections.projectionOf(model.row());
        return projections.toDocumentOffset(document, view.viewLine(),
                projection.maxColumn(model.viewLineInRow()));
    }

    private static int[] filled(int size) {
        int[] out = new int[size];
        Arrays.fill(out, -1);
        return out;
    }

    /**
     * Home: the first non-blank character, or column 0 when already there.
     *
     * <p>"Smart home", which every code editor has — in indented code the useful position is the start of
     * the text, not of the indentation. Pressing it twice still reaches column 0, so nothing is lost.</p>
     */
    public static int smartHome(Rope document, int head) {
        int row = document.offsetToPoint(head).row();
        int lineStart = document.lineStartOffset(row);
        String text = document.line(row);
        int indent = 0;
        while (indent < text.length() && (text.charAt(indent) == ' ' || text.charAt(indent) == '\t')) {
            indent++;
        }
        // A whitespace-only line has no "first non-blank"; its start is the answer rather than sending
        // the caret past everything on it.
        if (indent >= text.length()) return lineStart;
        return head == lineStart + indent ? lineStart : lineStart + indent;
    }

    /** End: the last character of the caret's line, newline excluded. */
    public static int lineEnd(Rope document, int head) {
        return document.lineEndOffset(document.offsetToPoint(head).row());
    }
}
