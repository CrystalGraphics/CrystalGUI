package com.crystalgui.headless;

import com.crystalgui.text.diff.DetailedDiff;
import com.crystalgui.text.diff.LinesDiff;
import com.crystalgui.ui.elements.editor.DiffDecorations;
import com.crystalgui.ui.elements.editor.DiffDecorations.Band;
import com.crystalgui.ui.elements.editor.DiffDecorations.Kind;
import com.crystalgui.ui.elements.editor.DiffDecorations.Mark;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiffDecorations} — what the two panes of a diff are told to draw.
 *
 * <p>Headless because it is a pure mapping from a diff to a list of ranges: no editor, no window, no
 * fonts. That the drawing model can be tested without any of those is the point of it being a model.</p>
 */
public class DiffDecorationsTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    private static List<DetailedDiff> diff(List<String> before, List<String> after) {
        return LinesDiff.computeDetailed(before, after);
    }

    /**
     * <b>The kind is a fact about the change, not about the pane.</b>
     *
     * <p>An insertion is an insertion in both panes — it simply has no rows to band in the original. Derive
     * it per pane instead and the same change reads as two different things depending on which side the
     * eye is on, which is precisely what a side-by-side view exists to prevent.</p>
     */
    @Test
    public void bothPanesAgreeOnWhatKindOfChangeItWas() {
        List<DetailedDiff> diffs = diff(lines("a", "b"), lines("a", "new", "b"));

        List<Band> original = DiffDecorations.forOriginal(diffs).bands();
        List<Band> modified = DiffDecorations.forModified(diffs).bands();

        assertEquals(1, original.size());
        assertEquals(1, modified.size());
        assertEquals(Kind.ADDED, original.get(0).kind());
        assertEquals("the same change, seen from the other side", Kind.ADDED, modified.get(0).kind());
    }

    /** An insertion has no rows in the original, so its band there is a boundary marker. */
    @Test
    public void anInsertionIsAMarkerInTheOriginalAndABandInTheModified() {
        List<DetailedDiff> diffs = diff(lines("a", "b"), lines("a", "new", "b"));

        Band original = DiffDecorations.forOriginal(diffs).bands().get(0);
        Band modified = DiffDecorations.forModified(diffs).bands().get(0);

        assertEquals("nothing to band on the side it is missing from",
                original.fromLine(), original.toLine());
        assertEquals("one row arrived", 1, modified.toLine() - modified.fromLine());
    }

    /** And a deletion is the mirror of it. */
    @Test
    public void aDeletionIsABandInTheOriginalAndAMarkerInTheModified() {
        List<DetailedDiff> diffs = diff(lines("a", "gone", "b"), lines("a", "b"));

        Band original = DiffDecorations.forOriginal(diffs).bands().get(0);
        Band modified = DiffDecorations.forModified(diffs).bands().get(0);

        assertEquals(Kind.REMOVED, original.kind());
        assertEquals(1, original.toLine() - original.fromLine());
        assertEquals(modified.fromLine(), modified.toLine());
    }

    @Test
    public void anEditedLineIsBandedOnBothSides() {
        List<DetailedDiff> diffs = diff(lines("a", "before", "b"), lines("a", "after", "b"));

        assertEquals(Kind.CHANGED, DiffDecorations.forOriginal(diffs).bands().get(0).kind());
        assertEquals(Kind.CHANGED, DiffDecorations.forModified(diffs).bands().get(0).kind());
    }

    /**
     * A changed line carries character marks; an inserted one does not.
     *
     * <p>There is no counterpart text to compare an inserted line against, so the only mark available
     * would be the whole line restated — drawn over every character of a line the band already covers.</p>
     */
    @Test
    public void marksAppearOnChangedLinesAndNotOnInsertedOnes() {
        List<Mark> changed = DiffDecorations.forModified(
                diff(lines("value = compute(a)"), lines("value = compute(b)"))).marks();
        assertFalse("a changed line narrows to the characters that differ", changed.isEmpty());

        List<Mark> inserted = DiffDecorations.forModified(
                diff(lines("a", "b"), lines("a", "brand new line", "b"))).marks();
        assertTrue("an inserted line has nothing to narrow against", inserted.isEmpty());
    }

    /** Marks address a real span of a real line in the pane they were built for. */
    @Test
    public void marksAddressRealPositions() {
        List<String> after = lines("alpha", "value = compute(b)", "omega");
        List<Mark> marks = DiffDecorations.forModified(
                diff(lines("alpha", "value = compute(a)", "omega"), after)).marks();

        for (Mark mark : marks) {
            assertTrue(mark.line() >= 0 && mark.line() < after.size());
            assertTrue(mark.fromColumn() >= 0);
            assertTrue("a mark cannot run past its line",
                    mark.toColumn() <= after.get(mark.line()).length());
            assertTrue("and cannot be empty", mark.toColumn() > mark.fromColumn());
        }
    }

    @Test
    public void identicalTextsProduceNothingToDraw() {
        List<DetailedDiff> diffs = diff(lines("a", "b"), lines("a", "b"));

        assertTrue(DiffDecorations.forOriginal(diffs).isEmpty());
        assertTrue(DiffDecorations.forModified(diffs).isEmpty());
        assertTrue(DiffDecorations.NONE.isEmpty());
    }
}
