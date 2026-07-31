package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.wrap.LineBreaksComputer;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.MonospaceLineBreaks;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.text.wrap.WrapIndent;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.7b — soft wrap's model layer.
 *
 * <h3>Why every one of these runs headless</h3>
 * <p>Soft wrap is two separable problems, and only one of them needs a font. <b>Where</b> a line breaks
 * is a measurement question; <b>what that means for coordinates</b> is arithmetic over an {@code int[]}.
 * VS Code splits them at {@code ILineBreaksComputer} and this port keeps the seam, so the coordinate
 * mapping — which is where the bugs live, because it is invisible until a caret lands in the wrong place
 * — is asserted without a window, a font, or a GL context.</p>
 */
public class SoftWrapTest {

    /** 20 columns, tabs of 4, no carried indent — the simplest thing that wraps. */
    private static MonospaceLineBreaks breaksAt(int columns) {
        return new MonospaceLineBreaks(columns, 4, WrapIndent.NONE);
    }

    // ── LineProjection: the coordinate mapping ──────────────────────────────────────────────────

    @Test
    public void anUnwrappedRowIsOneViewLineWithNoSpecialCase() {
        LineProjection projection = LineProjection.unwrapped(12);
        assertEquals(1, projection.viewLineCount());
        assertTrue(projection.isUnwrapped());
        assertEquals(0, projection.viewLineStart(0));
        assertEquals(12, projection.viewLineEnd(0));
        assertEquals(12, projection.maxColumn(0));
    }

    @Test
    public void offsetsRoundTripThroughViewCoordinates() {
        LineProjection projection = new LineProjection(new int[] { 10, 20, 26 }, 0);
        for (int offset = 0; offset <= 26; offset++) {
            LineProjection.ViewPosition view = projection.toViewPosition(offset, LineProjection.Affinity.NONE);
            assertEquals("offset " + offset + " round trip",
                    offset, projection.toModelOffset(view.viewLine(), view.column()));
        }
    }

    @Test
    public void theCarriedIndentIsAddedAndRemovedAroundTheModelOffset() {
        LineProjection projection = new LineProjection(new int[] { 10, 20 }, 4);
        LineProjection.ViewPosition view = projection.toViewPosition(10, LineProjection.Affinity.RIGHT);

        assertEquals("offset 10 starts the second view line", 1, view.viewLine());
        assertEquals("and sits past the carried indent", 4, view.column());
        assertEquals("which is still model offset 10", 10, projection.toModelOffset(1, 4));
    }

    /**
     * <b>The rule the whole feature rests on.</b> An offset exactly at a break is two places at once, and
     * which one is meant depends on how the caret got there. Without affinity the caret at a wrap point
     * flickers between the end of one line and the start of the next.
     */
    @Test
    public void anOffsetAtABreakResolvesToEitherSideOnDemand() {
        LineProjection projection = new LineProjection(new int[] { 10, 20 }, 0);

        assertEquals("plainly, it starts the next line",
                1, projection.toViewPosition(10, LineProjection.Affinity.NONE).viewLine());
        assertEquals("asked to lean left, it ends the previous one",
                0, projection.toViewPosition(10, LineProjection.Affinity.LEFT).viewLine());
        assertEquals("and leaning left it is at that line's end",
                10, projection.toViewPosition(10, LineProjection.Affinity.LEFT).column());
    }

    @Test
    public void theRowsFinalOffsetNeverSpillsPastTheLastViewLine() {
        LineProjection projection = new LineProjection(new int[] { 10, 20 }, 0);
        LineProjection.ViewPosition end = projection.toViewPosition(20, LineProjection.Affinity.RIGHT);

        assertEquals("there is no line after the last to be pushed onto", 1, end.viewLine());
        assertEquals(10, end.column());
    }

    @Test
    public void normalizeMovesAPositionAcrossABoundaryWithoutChangingTheOffset() {
        LineProjection projection = new LineProjection(new int[] { 10, 20 }, 0);

        LineProjection.ViewPosition left = projection.normalize(1, 0, LineProjection.Affinity.LEFT);
        assertEquals("the start of a continuation is the end of its predecessor", 0, left.viewLine());
        assertEquals(10, left.column());

        LineProjection.ViewPosition right = projection.normalize(0, 10, LineProjection.Affinity.RIGHT);
        assertEquals(1, right.viewLine());
        assertEquals(0, right.column());
    }

    @Test
    public void normalizeLeavesAPositionInTheMiddleOfALineAlone() {
        LineProjection projection = new LineProjection(new int[] { 10, 20 }, 0);
        LineProjection.ViewPosition same = projection.normalize(1, 5, LineProjection.Affinity.LEFT);
        assertEquals(1, same.viewLine());
        assertEquals(5, same.column());
    }

    // ── MonospaceLineBreaks: where the breaks fall ──────────────────────────────────────────────

    @Test
    public void aShortLineDoesNotWrap() {
        assertTrue(breaksAt(20).project("short").isUnwrapped());
    }

    @Test
    public void wrappingPrefersASpaceOverTheColumnLimit() {
        // "aaaa bbbb cccc dddd" is 19 columns; at 12 the break should land at the space before "cccc".
        LineProjection projection = breaksAt(12).project("aaaa bbbb cccc dddd");
        assertEquals(2, projection.viewLineCount());
        assertEquals("broken at a word boundary, not at column 12", 10, projection.viewLineEnd(0));
    }

    /**
     * <b>Opportunistic breaking needs a forced fallback or a long token cannot wrap at all.</b> A URL or a
     * minified line has no break opportunity in it, and "break only where allowed" leaves it running off
     * the viewport for as long as it is.
     */
    @Test
    public void aWordLongerThanTheViewportIsSplitAnyway() {
        LineProjection projection = breaksAt(10).project("aaaaaaaaaaaaaaaaaaaaaaaaa");
        assertTrue("25 characters at 10 columns must produce several lines", projection.viewLineCount() >= 3);
        assertEquals("and the last break offset is the row length", 25,
                projection.viewLineEnd(projection.viewLineCount() - 1));
    }

    @Test
    public void everyBreakOffsetIsStrictlyIncreasingSoNoViewLineIsEmpty() {
        LineProjection projection = breaksAt(8).project("alpha beta gamma delta epsilon zeta");
        int previous = 0;
        for (int i = 0; i < projection.viewLineCount(); i++) {
            int end = projection.viewLineEnd(i);
            assertTrue("view line " + i + " must contain something", end > previous);
            previous = end;
        }
    }

    @Test
    public void aTabCountsToItsStopNotAsOneColumn() {
        // Four tabs at width 4 fill 16 columns, so at 12 this must wrap; as one column each it would not.
        assertFalse(new MonospaceLineBreaks(12, 4, WrapIndent.NONE).project("\t\t\t\tx").isUnwrapped());
    }

    @Test
    public void ideographicTextWrapsWithoutAnySpaces() {
        LineProjection projection = breaksAt(6).project("一二三四五六七八九十");
        assertTrue("Han has no spaces to break at, so it breaks between characters",
                projection.viewLineCount() > 1);
    }

    @Test
    public void anEmptyLineProjectsToOneEmptyViewLine() {
        LineProjection projection = breaksAt(10).project("");
        assertEquals(1, projection.viewLineCount());
        assertEquals(0, projection.viewLineEnd(0));
    }

    // ── WrapIndent ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void carriedIndentTracksTheRowsOwnIndentation() {
        assertEquals(0, WrapIndent.NONE.columnsFor("    x", 4, 80));
        assertEquals(4, WrapIndent.SAME.columnsFor("    x", 4, 80));
        assertEquals("one further level", 8, WrapIndent.INDENT.columnsFor("    x", 4, 80));
        assertEquals("two", 12, WrapIndent.DEEP_INDENT.columnsFor("    x", 4, 80));
    }

    /**
     * <b>The clamp is not a nicety.</b> Without it a deeply indented row in a narrow viewport carries an
     * indent wider than the wrap width, leaving no columns for text — and the break loop cannot advance.
     */
    @Test
    public void carriedIndentIsAbandonedWhenItWouldNotLeaveRoomForText() {
        assertEquals(0, WrapIndent.INDENT.columnsFor("                x", 4, 10));
    }

    @Test
    public void aBlankLineCarriesNoIndent() {
        assertEquals(0, WrapIndent.INDENT.columnsFor("        ", 4, 80));
    }

    // ── ProjectedLines: the document-wide index ─────────────────────────────────────────────────

    @Test
    public void viewLineCountIsTheSumOfEveryRowsProjection() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\naaaa bbbb cccc dddd");
        ProjectedLines lines = new ProjectedLines(breaksAt(12));
        lines.rebuild(document);

        assertEquals("2 + 1 + 2", 5, lines.viewLineCount());
        assertEquals(3, lines.rowCount());
    }

    @Test
    public void viewLinesMapBackToTheRowTheyCameFrom() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\nxxxx yyyy zzzz wwww");
        ProjectedLines lines = new ProjectedLines(breaksAt(12));
        lines.rebuild(document);

        assertEquals(0, lines.modelAt(0).row());
        assertEquals(0, lines.modelAt(1).row());
        assertEquals("the continuation is the row's second view line", 1, lines.modelAt(1).viewLineInRow());
        assertEquals(1, lines.modelAt(2).row());
        assertEquals(2, lines.modelAt(3).row());
        assertEquals(2, lines.modelAt(4).row());
    }

    @Test
    public void theGutterNumberGoesOnARowsFirstViewLine() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\nxxxx yyyy zzzz wwww");
        ProjectedLines lines = new ProjectedLines(breaksAt(12));
        lines.rebuild(document);

        assertEquals(0, lines.firstViewLineOfRow(0));
        assertEquals(2, lines.firstViewLineOfRow(1));
        assertEquals(3, lines.firstViewLineOfRow(2));
    }

    @Test
    public void documentOffsetsRoundTripThroughTheWholeIndex() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\nxxxx yyyy zzzz wwww");
        ProjectedLines lines = new ProjectedLines(breaksAt(12));
        lines.rebuild(document);

        for (int offset = 0; offset <= document.length(); offset++) {
            ProjectedLines.ViewPosition view =
                    lines.toViewPosition(document, offset, LineProjection.Affinity.NONE);
            assertEquals("document offset " + offset,
                    offset, lines.toDocumentOffset(document, view.viewLine(), view.column()));
        }
    }

    /**
     * <b>The index drift test.</b> An incremental reprojection that disagrees with a full rebuild is the
     * classic soft-wrap failure: everything looks right until enough edits accumulate, and then the gutter
     * numbers slide against the text with nothing to point at.
     */
    @Test
    public void anIncrementalReprojectionAgreesWithAFullRebuild() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\nxxxx yyyy zzzz wwww\nlast");
        ProjectedLines incremental = new ProjectedLines(breaksAt(12));
        incremental.rebuild(document);

        // Split row 1 into two rows, as Enter would.
        Rope edited = document.replace(22, 22, "\n");
        incremental.rowsChanged(edited, 1, 1, 2);

        ProjectedLines fresh = new ProjectedLines(breaksAt(12));
        fresh.rebuild(edited);

        assertEquals(fresh.viewLineCount(), incremental.viewLineCount());
        for (int row = 0; row < fresh.rowCount(); row++) {
            assertEquals("row " + row + " starts at the same view line",
                    fresh.firstViewLineOfRow(row), incremental.firstViewLineOfRow(row));
        }
    }

    @Test
    public void anIncrementalReprojectionAgreesAfterARowIsRemoved() {
        Rope document = Rope.of("aaaa bbbb cccc dddd\nshort\nxxxx yyyy zzzz wwww\nlast");
        ProjectedLines incremental = new ProjectedLines(breaksAt(12));
        incremental.rebuild(document);

        Rope edited = document.delete(19, 25);   // removes "\nshort"
        incremental.rowsChanged(edited, 0, 2, 1);

        ProjectedLines fresh = new ProjectedLines(breaksAt(12));
        fresh.rebuild(edited);

        assertEquals(fresh.viewLineCount(), incremental.viewLineCount());
        for (int row = 0; row < fresh.rowCount(); row++) {
            assertEquals(fresh.firstViewLineOfRow(row), incremental.firstViewLineOfRow(row));
        }
    }

    /** Row arithmetic that disagrees with the document must reproject rather than corrupt the index. */
    @Test
    public void anImpossibleRowChangeFallsBackToAFullRebuild() {
        Rope document = Rope.of("one\ntwo\nthree");
        ProjectedLines lines = new ProjectedLines(breaksAt(12));
        lines.rebuild(document);

        Rope edited = Rope.of("one\ntwo");
        lines.rowsChanged(edited, 0, 99, 99);

        assertEquals(2, lines.rowCount());
        assertEquals(2, lines.viewLineCount());
    }

    /**
     * <b>The carried indent must never become characters.</b> If it did it would be selectable and
     * copyable, and pasting a soft wrap's indentation into a file is the failure that makes people turn
     * soft wrap off.
     */
    @Test
    public void viewLineTextExcludesTheCarriedIndent() {
        Rope document = Rope.of("    aaaa bbbb cccc dddd");
        ProjectedLines lines = new ProjectedLines(new MonospaceLineBreaks(12, 4, WrapIndent.SAME));
        lines.rebuild(document);

        assertTrue("more than one view line, or this asserts nothing", lines.viewLineCount() > 1);
        String continuation = lines.viewLineText(document, 1);
        assertFalse("the indent is a column offset, not text", continuation.startsWith(" "));
    }

    @Test
    public void concatenatingEveryViewLineReturnsTheRow() {
        Rope document = Rope.of("aaaa bbbb cccc dddd eeee");
        ProjectedLines lines = new ProjectedLines(breaksAt(11));
        lines.rebuild(document);

        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < lines.viewLineCount(); i++) rebuilt.append(lines.viewLineText(document, i));
        assertEquals("wrapping must not lose or duplicate a character",
                "aaaa bbbb cccc dddd eeee", rebuilt.toString());
    }

    @Test
    public void wrapOffLeavesEveryRowAsOneViewLine() {
        Rope document = Rope.of("a very long line that would certainly wrap\nand another one just like it");
        ProjectedLines lines = new ProjectedLines(LineBreaksComputer.none());
        lines.rebuild(document);

        assertEquals(lines.rowCount(), lines.viewLineCount());
    }

    @Test
    public void anEmptyDocumentHasOneViewLine() {
        ProjectedLines lines = new ProjectedLines(breaksAt(10));
        lines.rebuild(Rope.of(""));
        assertEquals(1, lines.viewLineCount());
        assertEquals(0, lines.modelAt(0).row());
    }
}
