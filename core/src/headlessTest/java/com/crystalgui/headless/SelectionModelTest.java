package com.crystalgui.headless;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Selection;
import com.crystalgui.text.SelectionModel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.7 step 2 — the multi-caret selection model.
 *
 * <p>Headless because it is pure offset arithmetic over {@link ChangeSet}: no window, no fonts, no GL. It
 * is also the layer that has to be right before the widget is touched, since every movement method in the
 * editor will go through {@link SelectionModel#transform}.</p>
 */
public class SelectionModelTest {

    // ── The invariant ───────────────────────────────────────────────────────────────────────────

    @Test
    public void startsWithOneCaretAtTheStart() {
        SelectionModel model = new SelectionModel();
        assertEquals(1, model.count());
        assertEquals(Selection.caret(0), model.primary());
        assertFalse(model.isMultiple());
    }

    @Test
    public void selectionsAreKeptSorted() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(30), Selection.caret(10), Selection.caret(20)), 0);

        assertEquals(List.of(Selection.caret(10), Selection.caret(20), Selection.caret(30)), model.all());
    }

    /**
     * <b>Two carets at the same offset are one caret.</b> Left as two, every keystroke would be inserted
     * twice at the same place — and a {@link ChangeSet} would refuse the edit outright, since its changes
     * must not overlap.
     */
    @Test
    public void carestAtTheSameOffsetMerge() {
        SelectionModel model = new SelectionModel();
        model.set(Selection.caret(5)).add(Selection.caret(5));

        assertEquals(1, model.count());
        assertEquals(Selection.caret(5), model.primary());
    }

    @Test
    public void overlappingSelectionsMerge() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(new Selection(0, 10), new Selection(5, 15)), 0);

        assertEquals(1, model.count());
        assertEquals(0, model.all().get(0).start());
        assertEquals(15, model.all().get(0).end());
    }

    @Test
    public void separateSelectionsDoNotMerge() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(new Selection(0, 5), new Selection(10, 15)), 0);
        assertEquals(2, model.count());
    }

    /**
     * <b>The primary caret survives being absorbed.</b> Otherwise the caret being driven jumps to whichever
     * selection happened to sort first, which reads as the editor losing your place mid-gesture.
     */
    @Test
    public void thePrimaryFollowsItsSelectionThroughAMerge() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(new Selection(0, 5), new Selection(20, 25), new Selection(40, 45)), 2);
        assertEquals(new Selection(40, 45), model.primary());

        // Grow the last one backwards until it swallows the middle one.
        model.setAll(List.of(new Selection(0, 5), new Selection(20, 25), new Selection(45, 22)), 2);

        assertEquals(2, model.count());
        assertEquals("the merged selection is still the primary", 20, model.primary().start());
        assertEquals(45, model.primary().end());
    }

    @Test
    public void mergingKeepsTheDirectionOfTheSelectionBeingDriven() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(new Selection(20, 10), new Selection(15, 25)), 0);

        assertEquals(1, model.count());
        assertTrue("a leftwards gesture stays leftwards", model.all().get(0).isReversed());
    }

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void transformMovesEveryCaret() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(0), Selection.caret(10), Selection.caret(20)), 0);

        model.transform(selection -> Selection.caret(selection.head() + 1));

        assertEquals(List.of(Selection.caret(1), Selection.caret(11), Selection.caret(21)), model.all());
    }

    /** Movement that drives carets together must leave them merged, not stacked. */
    @Test
    public void transformThatCollidesCaretsMergesThem() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(4), Selection.caret(5)), 0);

        model.transform(selection -> Selection.caret(5));

        assertEquals(1, model.count());
    }

    @Test
    public void collapseToPrimaryDropsTheRest() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(1), Selection.caret(2), Selection.caret(3)), 1);

        model.collapseToPrimary();

        assertEquals(1, model.count());
        assertEquals(Selection.caret(2), model.primary());
    }

    @Test
    public void collapseEachToHeadKeepsEveryCaret() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(new Selection(0, 5), new Selection(10, 15)), 0);

        model.collapseEachToHead();

        assertEquals(2, model.count());
        assertFalse(model.hasSelection());
        assertEquals(List.of(Selection.caret(5), Selection.caret(15)), model.all());
    }

    // ── Surviving edits ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A caret ends up after what was typed at it.</b> The single behaviour everyone notices the instant
     * it is wrong, and the reason an empty caret maps with a forward bias.
     */
    @Test
    public void aCaretFollowsTextInsertedAtIt() {
        SelectionModel model = new SelectionModel();
        model.set(Selection.caret(5));

        model.mapThrough(ChangeSet.of(20, Change.insert(5, "abc")));

        assertEquals(Selection.caret(8), model.primary());
    }

    @Test
    public void aSelectionKeepsCoveringItsTextThroughAnInsertBefore() {
        SelectionModel model = new SelectionModel();
        model.set(new Selection(10, 20));

        model.mapThrough(ChangeSet.of(30, Change.insert(0, "xx")));

        assertEquals(12, model.primary().start());
        assertEquals(22, model.primary().end());
    }

    @Test
    public void everyCaretSurvivesAMultiCaretEdit() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(0), Selection.caret(10), Selection.caret(20)), 0);

        // What typing "//" at three carets produces: one change set, three changes.
        ChangeSet typed = ChangeSet.of(30, List.of(
                Change.insert(0, "//"), Change.insert(10, "//"), Change.insert(20, "//")));
        model.mapThrough(typed);

        assertEquals(List.of(Selection.caret(2), Selection.caret(14), Selection.caret(26)), model.all());
    }

    @Test
    public void aReversedSelectionStaysReversedThroughAnEdit() {
        SelectionModel model = new SelectionModel();
        model.set(new Selection(20, 10));

        model.mapThrough(ChangeSet.of(30, Change.insert(0, "xx")));

        assertTrue(model.primary().isReversed());
        assertEquals(12, model.primary().start());
        assertEquals(22, model.primary().end());
    }

    @Test
    public void clampingPullsCaretsInsideAShrunkDocument() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(5), Selection.caret(50)), 1);

        model.clampTo(10);

        assertTrue(model.all().get(model.count() - 1).head() <= 10);
    }

    /** Deleting everything must leave exactly one caret, not several stacked at zero. */
    @Test
    public void deletingTheDocumentLeavesOneCaret() {
        SelectionModel model = new SelectionModel();
        model.setAll(List.of(Selection.caret(0), Selection.caret(5), Selection.caret(9)), 0);

        model.mapThrough(ChangeSet.of(10, Change.delete(0, 10)));

        assertEquals(1, model.count());
        assertEquals(Selection.caret(0), model.primary());
    }
}
