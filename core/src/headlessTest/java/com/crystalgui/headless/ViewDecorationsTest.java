package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.view.IndentLevels;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.text.view.WhitespaceMarkers;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.7b §G — indent guides and visible whitespace, as pure functions of the text.
 *
 * <p>Both are decorations the editor draws, and neither needs a font to decide <em>what</em> to draw —
 * only where. Keeping the decision headless is what lets the interesting cases (a blank line between two
 * blocks, a lone space between two words) be stated as one assertion each instead of being inspected in a
 * screenshot.</p>
 */
public class ViewDecorationsTest {

    // ── computeIndentLevel ──────────────────────────────────────────────────────────────────────

    @Test
    public void indentIsMeasuredInVisibleColumns() {
        assertEquals(0, IndentLevels.computeIndentLevel("x", 4));
        assertEquals(4, IndentLevels.computeIndentLevel("    x", 4));
        assertEquals("a tab counts to its stop, not as one column",
                4, IndentLevels.computeIndentLevel("\tx", 4));
        assertEquals("two tabs", 8, IndentLevels.computeIndentLevel("\t\tx", 4));
        assertEquals("a tab after two spaces still reaches the stop",
                4, IndentLevels.computeIndentLevel("  \tx", 4));
    }

    /**
     * <b>A whitespace-only line has no indent of its own, and says so.</b> Returning 0 would claim it sits
     * at the outermost level, which is what makes guides break across every blank line in a function.
     */
    @Test
    public void aBlankLineReportsMinusOneRatherThanZero() {
        assertEquals(-1, IndentLevels.computeIndentLevel("", 4));
        assertEquals(-1, IndentLevels.computeIndentLevel("    ", 4));
        assertEquals(-1, IndentLevels.computeIndentLevel("\t\t", 4));
    }

    // ── Guides on lines with content ────────────────────────────────────────────────────────────

    @Test
    public void aLineWithContentGuidesAtItsOwnDepth() {
        Rope document = Rope.of("a\n    b\n        c");
        int[] guides = IndentLevels.guidesFor(document, 0, 2, 4, 4, false);
        assertArrayEquals(new int[] { 0, 1, 2 }, guides);
    }

    @Test
    public void aPartialIndentRoundsUp() {
        // Two spaces at an indent size of four is still inside one level, not none.
        Rope document = Rope.of("  x");
        assertArrayEquals(new int[] { 1 }, IndentLevels.guidesFor(document, 0, 0, 4, 4, false));
    }

    // ── Guides on blank lines: the whole difficulty ─────────────────────────────────────────────

    /** Nothing encloses the top or the bottom of a file, so a blank line there guides at nothing. */
    @Test
    public void aBlankLineAtTheEdgesOfTheFileHasNoGuides() {
        Rope document = Rope.of("\n    x\n");
        int[] guides = IndentLevels.guidesFor(document, 0, 2, 4, 4, false);
        assertEquals("first line, nothing above it", 0, guides[0]);
        assertEquals("last line, nothing below it", 0, guides[2]);
    }

    /**
     * <b>Indented less above than below: the block below is opening.</b> The blank line belongs to the
     * outer block, one level deeper than the line above it.
     */
    @Test
    public void aBlankLineBeforeADeeperBlockTakesOneMoreThanAbove() {
        Rope document = Rope.of("a\n\n        c");
        assertEquals(1, IndentLevels.guidesFor(document, 1, 1, 4, 4, false)[0]);
    }

    /** Equal on both sides: the blank line sits between two siblings and matches them. */
    @Test
    public void aBlankLineBetweenSiblingsMatchesThem() {
        Rope document = Rope.of("    a\n\n    c");
        assertEquals(1, IndentLevels.guidesFor(document, 1, 1, 4, 4, false)[0]);
    }

    /**
     * <b>Indented more above than below: the block above is closing.</b> The blank line is still inside
     * it, so it guides one deeper than the line below — this is the case that makes guides run
     * continuously through the gap before a closing brace instead of stopping short.
     */
    @Test
    public void aBlankLineAfterADeeperBlockStaysInsideIt() {
        Rope document = Rope.of("        a\n\nc");
        assertEquals(1, IndentLevels.guidesFor(document, 1, 1, 4, 4, false)[0]);
    }

    /**
     * <b>{@code offSide} changes exactly that case.</b> A language whose blocks end by dedenting alone —
     * Python, YAML — has already closed the block at the blank line, so it takes the level below.
     */
    @Test
    public void offSideClosesTheBlockAtTheBlankLine() {
        Rope document = Rope.of("        a\n\nc");
        assertEquals("braces: still inside", 1,
                IndentLevels.guidesFor(document, 1, 1, 4, 4, false)[0]);
        assertEquals("off-side: already out", 0,
                IndentLevels.guidesFor(document, 1, 1, 4, 4, true)[0]);
    }

    /** Several blank lines in a row all answer from the same enclosing pair. */
    @Test
    public void aRunOfBlankLinesAnswersConsistently() {
        Rope document = Rope.of("    a\n\n\n\n    e");
        int[] guides = IndentLevels.guidesFor(document, 1, 3, 4, 4, false);
        assertArrayEquals(new int[] { 1, 1, 1 }, guides);
    }

    @Test
    public void aRangeInTheMiddleOfADocumentIsSelfContained() {
        Rope document = Rope.of("a\n    b\n\n    d\ne");
        // Asking only about row 2 must still find the content lines either side of it.
        assertEquals(1, IndentLevels.guidesFor(document, 2, 2, 4, 4, false)[0]);
    }

    // ── Whitespace markers ──────────────────────────────────────────────────────────────────────

    private static String render(String line, RenderWhitespace mode) {
        boolean[] marked = WhitespaceMarkers.shouldMark(line, mode, 4, false);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) out.append(marked[i] ? 'M' : '.');
        return out.toString();
    }

    @Test
    public void noneMarksNothing() {
        assertEquals("......", render(" a  b ", RenderWhitespace.NONE));
    }

    @Test
    public void allMarksEverySpaceAndTab() {
        assertEquals("M.MM.M", render(" a  b ", RenderWhitespace.ALL));
    }

    /**
     * <b>The rule that makes boundary the useful mode.</b> A lone space between two words is left alone,
     * so code and prose stay readable; leading, trailing and runs of two are shown.
     */
    @Test
    public void boundaryLeavesALoneSpaceBetweenWordsAlone() {
        assertEquals("a single space is not marked", ".....", render("a b c", RenderWhitespace.BOUNDARY));
        assertEquals("but a run of two is", "..MM..", render("ab  cd", RenderWhitespace.BOUNDARY));
    }

    @Test
    public void boundaryAlwaysMarksLeadingAndTrailing() {
        assertEquals("MM.MM", render("  a  ", RenderWhitespace.BOUNDARY));
    }

    /**
     * <b>A tab is marked in every mode, boundary included.</b> It is invisible <em>and</em> ambiguous in
     * a way a single space is not — how far it moves depends on where it starts.
     */
    @Test
    public void aTabIsMarkedEvenInBoundaryMode() {
        assertEquals("a\tb -> the tab is marked", ".M.", render("a\tb", RenderWhitespace.BOUNDARY));
    }

    /** Leading and inner whitespace are untouched; only what follows the last word is marked. */
    @Test
    public void trailingMarksOnlyWhatFollowsTheLastWord() {
        assertEquals(".....M", render("  a b ", RenderWhitespace.TRAILING));
        assertEquals("....MM", render("  a b  ", RenderWhitespace.TRAILING).substring(0, 7)
                .substring(1));
    }

    @Test
    public void trailingMarksNothingOnALineWithoutTrailingSpace() {
        assertEquals("......", render("  a  b", RenderWhitespace.TRAILING));
    }

    @Test
    public void aWhollyBlankLineIsAllTrailing() {
        assertEquals("MMM", render("   ", RenderWhitespace.TRAILING));
    }

    /**
     * <b>A wrapped segment has no trailing whitespace.</b> Its "end" is the middle of the line, and
     * marking there would report every soft wrap as a lint error.
     */
    @Test
    public void trailingDrawsNothingOnAWrappedSegment() {
        boolean[] marked = WhitespaceMarkers.shouldMark("a b  ", RenderWhitespace.TRAILING, 4, true);
        for (boolean m : marked) assertFalse("nothing is trailing mid-row", m);
    }

    @Test
    public void theMarkerGlyphsAreTheConventionalOnes() {
        assertEquals('·', WhitespaceMarkers.markerFor(' '));
        assertEquals('→', WhitespaceMarkers.markerFor('\t'));
        assertEquals("not whitespace, no marker", '\0', WhitespaceMarkers.markerFor('a'));
    }
}
