package com.crystalgui.text.cursor;

import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;

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
