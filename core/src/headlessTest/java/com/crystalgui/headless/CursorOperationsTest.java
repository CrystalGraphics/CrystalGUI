package com.crystalgui.headless;

import com.crystalgui.text.Change;
import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.cursor.ColumnSelection;
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

    /**
     * <b>Counted the way the line is DRAWN.</b> Two tabs are two characters and eight columns; counting
     * characters said {@code 2 % 4 == 2} and one press deleted the whole indent, landing the caret at
     * column zero. Reported from the harness as "backspace does not take me back up to the previous
     * line" — which is the same bug seen from its other end, since only column zero joins lines.
     */
    @Test
    public void backspaceTakesOneTabAtATime() {
        Rope document = Rope.of("\t\ttext");
        assertEquals("one tab, not both", 1, TypeOperations.backspaceFrom(document, 2, 4));
        assertEquals("then the other", 0, TypeOperations.backspaceFrom(document, 1, 4));
    }

    /** An indent that is not on a stop goes to the stop below it, not a whole level from where it is. */
    @Test
    public void backspaceFromAnUnalignedIndentGoesToTheStop() {
        Rope document = Rope.of("      text");
        assertEquals(4, TypeOperations.backspaceFrom(document, 6, 4));
        assertEquals(0, TypeOperations.backspaceFrom(document, 4, 4));
    }

    /** At column zero it is the line join, which is what the whole indent walk is on its way to. */
    @Test
    public void backspaceAtColumnZeroJoinsTheLines() {
        Rope document = Rope.of("a\n    b");
        assertEquals(1, TypeOperations.backspaceFrom(document, 2, 4));
    }

    /**
     * <b>A blank line goes straight up.</b> Reported from the harness as "backspace here should take me
     * up and it doesn't" — the second time that sentence has been said about this method, and a different
     * cause each time: the first was counting characters instead of columns
     * ({@link #backspaceTakesOneTabAtATime}), this one is the indent walk running on a line with nothing
     * to unindent.
     *
     * <p>Press Enter twice inside a method and the caret sits on eight spaces the user never typed.
     * Walking them costs two presses before the one that does the intended thing. IntelliJ removes the
     * indent and the break together; this is that.</p>
     */
    @Test
    public void backspaceOnAWhollyBlankLineJumpsToTheEndOfThePreviousLine() {
        Rope document = Rope.of("    void f() {\n\n        \n    }");
        // The caret at the end of the blank third line -- one press, and it lands after the empty second.
        int caret = document.lineStartOffset(2) + 8;
        assertEquals(document.lineEndOffset(1), TypeOperations.backspaceFrom(document, caret, 4));
    }

    /**
     * And the line it lands on keeps whatever is on it — the join is a join, not a jump to column zero.
     */
    @Test
    public void theJumpLandsAfterThePreviousLinesContent() {
        Rope document = Rope.of("a();\n        ");
        int caret = document.length();
        assertEquals(4, TypeOperations.backspaceFrom(document, caret, 4));
    }

    /**
     * <b>The distinction is CONTENT AFTER THE CARET, not "is the caret in indentation".</b>
     *
     * <p>Both lines here start with eight spaces and both have the caret at column eight. The one with a
     * statement after it unindents by a level, because there is something to unindent; the empty one goes
     * up. Merging them either makes the tab-stop walk unreachable or makes every blank line take three
     * presses.</p>
     */
    @Test
    public void anIndentWithCodeAfterItStillWalksItsStops() {
        Rope withCode = Rope.of("x;\n        y;");
        int caretInIndent = withCode.lineStartOffset(1) + 8;
        assertEquals("a level, not a jump", caretInIndent - 4,
                TypeOperations.backspaceFrom(withCode, caretInIndent, 4));

        Rope blank = Rope.of("x;\n        ");
        assertEquals("the same column, the other answer", 2,
                TypeOperations.backspaceFrom(blank, blank.length(), 4));
    }

    /**
     * A caret parked inside a blank line's whitespace walks the stops instead of jumping.
     *
     * <p>Jumping would carry the spaces <em>after</em> the caret up onto the previous line as trailing
     * whitespace. Only reachable by clicking into the middle of an empty line, which is exactly why it
     * would never have been noticed.</p>
     */
    @Test
    public void aCaretInsideABlankLinesWhitespaceStillWalksTheStops() {
        Rope document = Rope.of("x;\n        ");
        int midway = document.lineStartOffset(1) + 4;
        assertEquals(document.lineStartOffset(1), TypeOperations.backspaceFrom(document, midway, 4));
    }

    /** Nothing above to join to — the first line of a file falls back to the walk. */
    @Test
    public void aBlankFirstLineHasNowhereToGoUpTo() {
        Rope document = Rope.of("        \nx;");
        assertEquals(4, TypeOperations.backspaceFrom(document, 8, 4));
    }

    // ── Enter ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The caret lands on a line of its own between the braces.</b> Reported from the harness: typing
     * Enter after {@code if (true) &#123;} with the closer already there produced one line with the
     * closing brace sitting beside the caret. The rule that did it asked whether the LINE ended in an
     * opener, and with the caret between the pair the line ends in the closer.
     */
    @Test
    public void enterBetweenBracesOpensAMiddleLine() {
        Rope document = Rope.of("    if (true) {}");
        TypeOperations.Enter enter = TypeOperations.enterAt(document, 15,
                TypeOperations.IndentStyle.spaces(4), Language.java());
        assertEquals("\n        \n    ", enter.text());
        assertEquals("the caret is on the FIRST of the two new lines", 15 + 9, enter.caret());
    }

    /** An opener with anything else after it indents once and stays on one line. */
    @Test
    public void enterAfterAnOpenerIndentsOnce() {
        Rope document = Rope.of("    if (true) {");
        TypeOperations.Enter enter = TypeOperations.enterAt(document, 15,
                TypeOperations.IndentStyle.spaces(4), Language.java());
        assertEquals("\n        ", enter.text());
        assertEquals(15 + 9, enter.caret());
    }

    /** Anything else carries the indentation across and does nothing more. */
    @Test
    public void enterElsewhereCarriesTheIndent() {
        Rope document = Rope.of("    call();");
        TypeOperations.Enter enter = TypeOperations.enterAt(document, 11,
                TypeOperations.IndentStyle.spaces(4), Language.java());
        assertEquals("\n    ", enter.text());
    }

    /**
     * <b>The line's last character is not the question.</b> Splitting a line in the middle carries the
     * indent and nothing else, however the line happens to end.
     */
    @Test
    public void enterInTheMiddleOfALineDoesNotIndent() {
        Rope document = Rope.of("    foo bar {");
        TypeOperations.Enter enter = TypeOperations.enterAt(document, 7,
                TypeOperations.IndentStyle.spaces(4), Language.java());
        assertEquals("\n    ", enter.text());
    }

    /** In tabs mode one level is a tab, and the carried indent is whatever the line already had. */
    @Test
    public void enterInTabsModeIndentsWithATab() {
        Rope document = Rope.of("\tif (true) {");
        TypeOperations.Enter enter = TypeOperations.enterAt(document, 12,
                TypeOperations.IndentStyle.tabs(4), Language.java());
        assertEquals("\n\t\t", enter.text());
    }

    // ── Column selection ────────────────────────────────────────────────────────────────────────

    /** One selection per row, all between the same two columns. */
    @Test
    public void aBoxCoversTheSameColumnsOnEveryRow() {
        Rope document = Rope.of("abcdef\nghijkl\nmnopqr");
        List<Selection> box = ColumnSelection.between(document, 1, document.lineStartOffset(2) + 4, 4);

        assertEquals(3, box.size());
        assertEquals(new Selection(1, 4), box.get(0));
        assertEquals(new Selection(8, 11), box.get(1));
        assertEquals(new Selection(15, 18), box.get(2));
    }

    /**
     * <b>Columns are VISUAL, which is the whole of the port.</b> A box is a rectangle on screen, so two
     * rows whose text differs in tabs must still line up — computed from character offsets it would be a
     * ragged edge that follows the text rather than a box.
     */
    @Test
    public void aBoxLinesUpAcrossTabsAndSpaces() {
        Rope document = Rope.of("\tabc\n    def");
        // Column 4 on both rows: just past the tab on the first, just past the four spaces on the second.
        List<Selection> box = ColumnSelection.between(document, 1, document.lineStartOffset(1) + 4, 4);

        assertEquals(2, box.size());
        assertEquals("the tab row starts after its single tab character", 1, box.get(0).start());
        assertEquals("and the space row after its four", document.lineStartOffset(1) + 4,
                box.get(1).start());
    }

    /**
     * <b>A short row is clamped, never skipped.</b> The point of a box is usually to type at the end of
     * every line in it, and a row dropping out because it is shorter is exactly the row somebody wanted.
     */
    @Test
    public void aShortRowIsClampedToItsOwnEnd() {
        Rope document = Rope.of("abcdefgh\nij\nklmnopqr");
        List<Selection> box = ColumnSelection.between(document, 4, document.lineStartOffset(2) + 6, 4);

        assertEquals(3, box.size());
        Selection middle = box.get(1);
        assertEquals("clamped to the end of `ij`", document.lineStartOffset(1) + 2, middle.start());
        assertEquals("and to the same place, so it is a bare caret", middle.start(), middle.end());
    }

    /** Dragging upwards puts the head's row last, so the caller can treat it as the primary. */
    @Test
    public void anUpwardBoxEndsOnTheHeadsRow() {
        Rope document = Rope.of("aaaa\nbbbb\ncccc");
        List<Selection> box = ColumnSelection.between(document, document.lineStartOffset(2) + 1, 1, 4);

        assertEquals(3, box.size());
        assertEquals("the head's row is last", 1, box.get(2).start());
    }

    // ── Paste ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A block arrives at the depth it is dropped into</b>, keeping its own shape. The shift is
     * measured from the minimum indent of the lines after the first, so nothing inside the block moves
     * relative to anything else — a nested statement stays nested.
     */
    @Test
    public void pastedLinesAreShiftedToWhereTheyLand() {
        Rope document = Rope.of("class A {\n    void go() {\n        \n    }\n}");
        int at = document.lineStartOffset(2) + 8;                     // inside the 8-space indent
        String pasted = "if (x) {\n    call();\n}";

        assertEquals("if (x) {\n            call();\n        }",
                TypeOperations.reindentForPaste(document, at, pasted,
                        TypeOperations.IndentStyle.spaces(4)));
    }

    /** The first line is being typed at the caret, so it goes in exactly as it was cut. */
    @Test
    public void theFirstPastedLineIsNeverTouched() {
        Rope document = Rope.of("        ");
        String pasted = "    already indented\n    second";
        String out = TypeOperations.reindentForPaste(document, 8, pasted,
                TypeOperations.IndentStyle.spaces(4));
        assertTrue(out.startsWith("    already indented\n"));
    }

    /** Into the middle of a line, what the rest should line up with is genuinely ambiguous. */
    @Test
    public void pastingIntoTextLeavesTheBlockAlone() {
        Rope document = Rope.of("    int x = 1;");
        String pasted = "a\n        b";
        assertEquals(pasted, TypeOperations.reindentForPaste(document, 10, pasted,
                TypeOperations.IndentStyle.spaces(4)));
    }

    /** A single line has no shape to preserve and no lines below it to shift. */
    @Test
    public void aSingleLinePasteIsUnchanged() {
        Rope document = Rope.of("        ");
        assertEquals("value", TypeOperations.reindentForPaste(document, 8, "value",
                TypeOperations.IndentStyle.spaces(4)));
    }

    /** In tabs mode the shift is written as tabs, because that is how the document indents. */
    @Test
    public void aShiftInTabsModeIsWrittenWithTabs() {
        Rope document = Rope.of("\t\t");
        String out = TypeOperations.reindentForPaste(document, 2, "a\nb",
                TypeOperations.IndentStyle.tabs(4));
        assertEquals("a\n\t\tb", out);
    }

    // ── Tab ─────────────────────────────────────────────────────────────────────────────────────

    /** To the next stop, so a Tab-indented block does not drift one character further out per press. */
    @Test
    public void tabGoesToTheNextStop() {
        Rope document = Rope.of("ab");
        assertEquals("  ", TypeOperations.tabAt(document, 2, TypeOperations.IndentStyle.spaces(4)));
        assertEquals("    ", TypeOperations.tabAt(document, 0, TypeOperations.IndentStyle.spaces(4)));
        assertEquals("\t", TypeOperations.tabAt(document, 1, TypeOperations.IndentStyle.tabs(4)));
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
