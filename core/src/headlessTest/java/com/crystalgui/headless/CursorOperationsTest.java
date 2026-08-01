package com.crystalgui.headless;

import com.crystalgui.text.Change;
import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.cursor.CursorColumns;
import com.crystalgui.text.cursor.LineOperations;
import com.crystalgui.text.cursor.MouseSelection;
import com.crystalgui.text.cursor.MoveOperations;
import com.crystalgui.text.cursor.TypeOperations;
import com.crystalgui.text.syntax.Language;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.7b — the cursor operations, extracted from the widget.
 *
 * <h3>Why this file exists, and why it is headless</h3>
 * <p>All of this logic was written inline as private methods on {@code TextEditor}, so the only way to
 * reach it was through a {@code UIWindow} with fonts, a style engine and an input handler attached. That
 * is not merely inconvenient — it hid a bug. The per-caret goal column needed <em>two</em> simulated key
 * presses to expose, because the first move behaves identically whether the goal is shared or not; as a
 * direct call it is one assertion. Porting VS Code's algorithms without porting its module boundaries
 * kept the algorithms and threw away the testability that makes them stay correct.</p>
 */
public class CursorOperationsTest {

    private static final WordClassifier WORDS = WordClassifier.DEFAULT;

    // ── CursorColumns ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aTabExpandsToItsStopNotToAFixedWidth() {
        assertEquals("    x", CursorColumns.expand("\tx", 4).display());
        assertEquals("two characters in, the tab fills only to column 4",
                "ab  c", CursorColumns.expand("ab\tc", 4).display());
        assertEquals("three in, it fills one column", "abc d", CursorColumns.expand("abc\td", 4).display());
    }

    @Test
    public void consecutiveTabsEachReachTheNextStop() {
        assertEquals("        x", CursorColumns.expand("\t\tx", 4).display());
    }

    @Test
    public void theColumnMapsAreInverses() {
        CursorColumns.Line line = CursorColumns.expand("a\tbc\td", 4);
        for (int column = 0; column <= "a\tbc\td".length(); column++) {
            int display = line.displayIndexOf(column);
            assertEquals("column " + column + " round trip", column, line.columnOf(display));
        }
    }

    /** Every display index inside a tab maps back to that tab's own column. */
    @Test
    public void clickingInsideATabLandsOnOneSideOfIt() {
        CursorColumns.Line line = CursorColumns.expand("\tx", 4);
        for (int index = 0; index < 4; index++) {
            assertEquals("index " + index + " is inside the tab", 0, line.columnOf(index));
        }
        assertEquals(1, line.columnOf(4));
    }

    // ── MoveOperations ──────────────────────────────────────────────────────────────────────────

    /** The rule that needed a simulated key press before; now one call. */
    @Test
    public void aPlainLeftWithASelectionCollapsesToItsStart() {
        Rope document = Rope.of("abcdefgh");
        List<Selection> moved = MoveOperations.horizontal(
                document, List.of(new Selection(2, 6)), -1, false, false, WORDS);

        assertEquals(Selection.caret(2), moved.get(0));
    }

    @Test
    public void aPlainRightWithASelectionCollapsesToItsEnd() {
        Rope document = Rope.of("abcdefgh");
        assertEquals(Selection.caret(6), MoveOperations.horizontal(
                document, List.of(new Selection(2, 6)), 1, false, false, WORDS).get(0));
        assertEquals("direction of the gesture must not matter", Selection.caret(6),
                MoveOperations.horizontal(
                        document, List.of(new Selection(6, 2)), 1, false, false, WORDS).get(0));
    }

    @Test
    public void shiftExtendsFromTheHeadInstead() {
        Rope document = Rope.of("abcdefgh");
        Selection moved = MoveOperations.horizontal(
                document, List.of(new Selection(2, 6)), -1, true, false, WORDS).get(0);
        assertEquals(2, moved.anchor());
        assertEquals(5, moved.head());
    }

    /**
     * <b>Each caret keeps its own goal column.</b> Through the widget this needed two key presses to
     * expose, because the first move behaves identically either way — here the goals are simply an
     * argument.
     */
    @Test
    public void verticalMovementKeepsAGoalPerCaret() {
        Rope document = Rope.of("aaaaaaaa\nbb\ncccccccc\ndd\neeeeeeee");
        List<Selection> carets = List.of(Selection.caret(6), Selection.caret(21));
        int[] goals = { 6, 2 };

        var result = MoveOperations.vertical(document, carets, goals, 1, false);

        assertEquals("the first aims for column 6", 6, result.goalColumns()[0]);
        assertEquals("the second keeps column 2 rather than inheriting", 2, result.goalColumns()[1]);
    }

    @Test
    public void aFirstVerticalMoveTakesTheGoalFromTheCurrentColumn() {
        Rope document = Rope.of("abcdefgh\nij");
        var result = MoveOperations.vertical(document, List.of(Selection.caret(5)), new int[0], 1, false);
        assertEquals(5, result.goalColumns()[0]);
    }

    @Test
    public void smartHomeTogglesBetweenIndentAndColumnZero() {
        Rope document = Rope.of("    indented");
        assertEquals("from inside the text, the first non-blank", 4,
                MoveOperations.smartHome(document, 8));
        assertEquals("from there, column 0", 0, MoveOperations.smartHome(document, 4));
    }

    @Test
    public void smartHomeOnABlankLineGoesToItsStart() {
        Rope document = Rope.of("a\n    \nb");
        assertEquals(2, MoveOperations.smartHome(document, 5));
    }

    // ── TypeOperations ──────────────────────────────────────────────────────────────────────────

    @Test
    public void autoCloseFiresOnlyBeforeTheAllowedCharacters() {
        Language java = Language.java();
        assertTrue("before end of document", TypeOperations.shouldAutoClose(
                Rope.of(""), List.of(Selection.caret(0)), '(', java, WORDS));
        assertTrue("before a semicolon", TypeOperations.shouldAutoClose(
                Rope.of(";"), List.of(Selection.caret(0)), '(', java, WORDS));
        assertFalse("but not before a word", TypeOperations.shouldAutoClose(
                Rope.of("foo"), List.of(Selection.caret(0)), '(', java, WORDS));
        assertFalse("nor before a $", TypeOperations.shouldAutoClose(
                Rope.of("$foo"), List.of(Selection.caret(0)), '(', java, WORDS));
    }

    /** An apostrophe in prose must not become a pair. */
    @Test
    public void aQuoteAfterAWordCharacterDoesNotAutoClose() {
        assertFalse(TypeOperations.shouldAutoClose(
                Rope.of("dont "), List.of(Selection.caret(4)), '\'', Language.java(), WORDS));
    }

    /** Nor a third quote in a row, which would otherwise produce five. */
    @Test
    public void aQuoteAfterTheSameQuoteDoesNotAutoClose() {
        assertFalse(TypeOperations.shouldAutoClose(
                Rope.of("\"\" "), List.of(Selection.caret(1)), '"', Language.java(), WORDS));
    }

    @Test
    public void typeOverNeedsEveryCaretToHaveTheCloserAhead() {
        Rope document = Rope.of("()  ()");
        assertTrue(TypeOperations.nextCharIs(document,
                List.of(Selection.caret(1), Selection.caret(5)), ')'));
        assertFalse("one caret without it is enough to refuse", TypeOperations.nextCharIs(document,
                List.of(Selection.caret(1), Selection.caret(3)), ')'));
    }

    @Test
    public void backspaceTakesAWholeIndentLevelInsideIndentation() {
        Rope document = Rope.of("        text");
        assertEquals(4, TypeOperations.backspaceFrom(document, 8, 4));
        assertEquals("but one character inside the text", 11,
                TypeOperations.backspaceFrom(document, 12, 4));
    }

    @Test
    public void surroundWrapsRatherThanReplaces() {
        List<Change> changes = TypeOperations.surround(List.of(new Selection(0, 5)), '(', ')');
        assertEquals(2, changes.size());
        assertEquals(0, changes.get(0).from());
        assertEquals(5, changes.get(1).from());
    }

    // ── LineOperations ──────────────────────────────────────────────────────────────────────────

    @Test
    public void deletingTheLastLineTakesThePrecedingNewline() {
        Rope document = Rope.of("one\ntwo");
        List<Change> changes = LineOperations.delete(document, List.of(1));
        assertEquals("otherwise a blank line is left where the text was", 3, changes.get(0).from());
    }

    @Test
    public void movingLinesIsOneReplacement() {
        Rope document = Rope.of("one\ntwo\nthree");
        LineOperations.Move move = LineOperations.move(document, List.of(0), 1);
        assertNotNull(move);
        assertEquals("two edits would be two undo steps", "two\none\n",
                move.change().insert().substring(0, 8));
    }

    @Test
    public void movingPastTheEndDoesNothing() {
        assertNull(LineOperations.move(Rope.of("one\ntwo"), List.of(0), -1));
    }

    /** A mixed block comments out rather than half-toggling. */
    @Test
    public void aPartlyCommentedBlockCommentsOut() {
        Rope document = Rope.of("// one\ntwo");
        List<Change> changes = LineOperations.toggleLineComment(document, List.of(0, 1), Language.java());
        for (Change change : changes) {
            assertTrue("every change should be an insertion", change.inserted() > 0);
        }
    }

    @Test
    public void aFullyCommentedBlockUncomments() {
        Rope document = Rope.of("// one\n// two");
        List<Change> changes = LineOperations.toggleLineComment(document, List.of(0, 1), Language.java());
        for (Change change : changes) {
            assertEquals("every change should be a deletion", 0, change.inserted());
        }
    }

    @Test
    public void adjacentChangesAreMergedSoAChangeSetWillAcceptThem() {
        List<Change> merged = LineOperations.mergeAdjacent(new java.util.ArrayList<>(List.of(
                Change.delete(0, 4), Change.delete(4, 8))));
        assertEquals(1, merged.size());
        assertEquals(0, merged.get(0).from());
        assertEquals(8, merged.get(0).to());
    }

    // ── MouseSelection ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>A triple-click takes the line's TEXT, not the line plus its newline.</b>
     *
     * <p>This asserted {@code {0, 11}} — VS Code's {@code lineEnd + 1}, which ends the selection at the
     * first offset of the NEXT row. Two visible things follow from that end offset, both reported against
     * the widget: a selection sliver is drawn on the row below, and the caret, which sits at the
     * selection's head, is painted a line under the one that was clicked.</p>
     *
     * <p>Changed to IntelliJ's span, which stops at the line's end and leaves the caret on the clicked
     * line. The old expectation was not wrong about the code — it was a faithful record of the port — so it
     * is rewritten rather than deleted, and this note is why.</p>
     */
    @Test
    public void clickCountPicksTheGranularity() {
        Rope document = Rope.of("alpha beta\nsecond");
        assertArrayEquals(new int[] { 3, 3 }, MouseSelection.unitAt(document, 3, 1, WORDS));
        assertArrayEquals("a double-click takes the word", new int[] { 0, 5 },
                MouseSelection.unitAt(document, 3, 2, WORDS));
        assertArrayEquals("a triple-click takes the line WITHOUT its newline", new int[] { 0, 10 },
                MouseSelection.unitAt(document, 3, 3, WORDS));
        assertEquals("and the newline is genuinely the next character", '\n', document.charAt(10));
    }

    /**
     * <b>A backwards drag keeps the anchor unit whole.</b> Without the union the anchor word is eaten
     * into, and a word-granularity drag stops feeling like it selects words.
     */
    @Test
    public void draggingBackwardsKeepsTheAnchorWordWhole() {
        Rope document = Rope.of("alpha beta gamma");
        int[] anchor = MouseSelection.unitAt(document, 13, 2, WORDS);   // "gamma"
        Selection dragged = MouseSelection.extend(document, anchor, 2, 2, WORDS);

        assertEquals("it still covers all of gamma", 16, dragged.end());
        assertEquals("and reaches the start of alpha", 0, dragged.start());
    }
}
