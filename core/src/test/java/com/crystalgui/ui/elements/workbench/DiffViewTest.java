package com.crystalgui.ui.elements.workbench;

import com.crystalgui.testsupport.TestPlatformService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiffView}'s one behaviour that can destroy work: reverting a difference.
 *
 * <p>The differ itself is pinned headlessly. What is only true here is the splice — that the block
 * coordinates the view is holding still address the text it is about to edit.</p>
 */
public class DiffViewTest {

    private static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static DiffView view(String left, String right) {
        TestPlatformService.install();
        return new DiffView("HEAD", left, "Working tree", right);
    }

    @Test
    public void identicalRevisionsHaveNoDifferences() {
        assertEquals(0, view(text("a", "b"), text("a", "b")).differenceCount());
    }

    @Test
    public void aChangedLineIsOneDifference() {
        assertEquals(1, view(text("a", "b", "c"), text("a", "CHANGED", "c")).differenceCount());
    }

    /** Reverting one difference restores that block and leaves the rest of the text alone. */
    @Test
    public void revertingADifferenceRestoresThatBlockOnly() {
        DiffView view = view(text("a", "b", "c", "d"), text("a", "ONE", "c", "TWO"));
        assertEquals(2, view.differenceCount());

        view.revertDifference(0);

        assertEquals("the first block is back", text("a", "b", "c", "TWO"), view.modifiedText());
        assertEquals("and the second is untouched", 1, view.differenceCount());
    }

    /**
     * <b>The correctness net.</b>
     *
     * <p>Reverting every difference must reproduce the left-hand revision exactly. A splice at the wrong
     * offsets still produces text, and still reduces the difference count — so nothing short of comparing
     * the whole result catches it.</p>
     */
    @Test
    public void revertingEveryDifferenceReproducesTheLeftHandSide() {
        String left = text("alpha", "beta", "gamma", "delta", "epsilon");
        String right = text("alpha", "CHANGED", "gamma", "inserted", "delta", "epsilon");

        DiffView view = view(left, right);
        assertTrue(view.differenceCount() > 0);

        // Back to front: reverting a block moves everything below it, so front to back would leave every
        // later block's coordinates describing text that has already shifted -- the same reason a
        // ChangeSet applies in reverse.
        for (int i = view.differenceCount() - 1; i >= 0; i--) view.revertDifference(i);

        assertEquals(left, view.modifiedText());
        assertEquals(0, view.differenceCount());
    }

    /** Reverting an insertion removes it; reverting a deletion puts it back. */
    @Test
    public void revertingHandlesInsertionsAndDeletions() {
        DiffView inserted = view(text("a", "b"), text("a", "new", "b"));
        inserted.revertDifference(0);
        assertEquals(text("a", "b"), inserted.modifiedText());

        DiffView deleted = view(text("a", "gone", "b"), text("a", "b"));
        deleted.revertDifference(0);
        assertEquals(text("a", "gone", "b"), deleted.modifiedText());
    }

    /** An out-of-range index is refused rather than throwing out of a click handler. */
    @Test
    public void anOutOfRangeRevertIsIgnored() {
        DiffView view = view(text("a", "b"), text("a", "B"));
        String before = view.modifiedText();

        view.revertDifference(-1);
        view.revertDifference(99);

        assertEquals(before, view.modifiedText());
    }
}
