package com.crystalgui.text.cursor;

import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;

/**
 * Click-to-selection — ported from VS Code's mouse handling.
 *
 * <p><b>A click count picks a granularity, not a separate action</b>: one click a caret, two a word,
 * three a line. Structuring it that way is what lets the same code serve the initial press <em>and</em>
 * every drag update after it — which is the difference between double-click-drag extending by word and
 * silently dropping back to characters the moment the pointer moves.</p>
 *
 * <p>Source: {@code src/vs/editor/browser/controller/mouseHandler.ts} and {@code mouseTarget.ts},
 * microsoft/vscode, MIT licence.</p>
 */
public final class MouseSelection {

    private MouseSelection() {
    }

    /** Granularity, in click counts. */
    public static final int CHARACTER = 1;
    public static final int WORD = 2;
    public static final int LINE = 3;

    /** The span a click of {@code clicks} selects, as {@code {start, end}}. */
    public static int[] unitAt(Rope document, int offset, int clicks, WordClassifier classifier) {
        int at = Math.max(0, Math.min(offset, document.length()));
        if (clicks >= LINE) {
            int row = document.offsetToPoint(at).row();
            // THE LINE'S TEXT, WITHOUT ITS NEWLINE. VS Code selects lineEnd + 1 here, which ends the
            // selection at the FIRST OFFSET OF THE NEXT ROW -- and two visible things follow from that.
            // The band loop draws a sliver on the row below, and the caret, which sits at the selection's
            // head, is painted a line under the one that was clicked.
            //
            // IntelliJ ends at the line's end and leaves the caret on the clicked line, which is the
            // behaviour wanted here. It also makes "triple-click then type" replace the line you pointed
            // at rather than swallowing the break after it and joining the next line on.
            return new int[] { document.lineStartOffset(row), document.lineEndOffset(row) };
        }
        if (clicks == WORD) {
            int[] word = WordOperations.wordAt(document, at, classifier);
            if (word != null) return word;
        }
        return new int[] { at, at };
    }

    /**
     * The selection a drag produces, given the unit it started on.
     *
     * <p>Spans the <b>union</b> of the anchor unit and the unit under the pointer, so dragging backwards
     * past the anchor keeps the anchor word or line whole rather than eating into it — the behaviour that
     * makes a word-granularity drag feel like it is selecting words rather than a moving edge.</p>
     */
    public static Selection extend(Rope document, int[] anchorUnit, int offset, int clicks,
                                   WordClassifier classifier) {
        if (anchorUnit == null) return Selection.caret(offset);
        int[] head = unitAt(document, offset, clicks, classifier);
        return head[0] < anchorUnit[0]
                ? new Selection(anchorUnit[1], head[0])
                : new Selection(anchorUnit[0], head[1]);
    }
}
