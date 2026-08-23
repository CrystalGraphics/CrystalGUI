package com.crystalgui.headless;

import com.crystalgui.text.diff.RegionState;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.ThreeWayMerge.Region;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The merge state model — {@link RegionState} and the capabilities it exists to express.
 *
 * <p>Each of these is something the enum it replaced could not say. They are worth testing precisely
 * because they are the reasons for the replacement: if none of them holds, the sealed hierarchy is
 * ceremony.</p>
 */
public class RegionStateTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    /**
     * A conflict whose two sides touch DIFFERENT parts of the region.
     *
     * <p>Worth spelling out how to build one, because the obvious fixture does not work: a region can
     * never contain a line <em>both</em> sides left alone — {@code MergeRanges} splits wherever the two
     * diffs agree, so agreed text is outside every region by construction. Both sides must therefore
     * disagree with the base across the whole span, while disagreeing about different lines of it.</p>
     */
    private static ThreeWayMerge separableConflict() {
        return ThreeWayMerge.of(
                lines("one", "middle", "two"),
                lines("MINE", "middle", "two"),
                lines("one", "MIDDLE", "TWO"));
    }

    /**
     * <b>"Take both" interleaves by base position; it does not concatenate.</b>
     *
     * <p>Concatenation is what a person means only when the two edits are adjacent. Here mine replaced the
     * first line and theirs the last two, so gluing the two versions end to end emits <em>six</em> lines —
     * every line of the region twice, in a jumbled order — where the answer is plainly three.</p>
     */
    @Test
    public void takingBothInterleavesRatherThanConcatenating() {
        Region region = separableConflict().conflicts().get(0);

        region.accept(new RegionState.Both(true, false));
        List<String> concatenated = region.resolvedLines();
        region.accept(new RegionState.Both(true, true));
        List<String> interleaved = region.resolvedLines();

        assertEquals("concatenation restates both whole sides", 6, concatenated.size());
        assertEquals("interleaving takes each side's own edit", lines("MINE", "MIDDLE", "TWO"), interleaved);
    }

    /** And the shorthand a view offers by default is the interleaved one. */
    @Test
    public void acceptBothUsesTheInterleavedForm() {
        ThreeWayMerge merge = separableConflict();
        merge.conflicts().get(0).acceptBoth();

        assertEquals(lines("MINE", "MIDDLE", "TWO"), merge.mergedLines());
        assertTrue(merge.isResolved());
    }

    /** Two edits to the same lines cannot be interleaved, and the region says so. */
    @Test
    public void overlappingEditsCannotBeCombined() {
        ThreeWayMerge merge = ThreeWayMerge.of(lines("a"), lines("mine"), lines("theirs"));

        assertFalse("there is no place to put both", merge.conflicts().get(0).canBeCombined());
    }

    @Test
    public void separableEditsCanBeCombined() {
        assertTrue(separableConflict().conflicts().get(0).canBeCombined());
    }

    /**
     * Order matters only sometimes, and a view should be able to say which.
     *
     * <p>Two edits at different places in the base come out the same either way; two at the same place do
     * not. Offering an order choice that changes nothing is as bad as hiding one that does.</p>
     */
    @Test
    public void orderIsRelevantOnlyWhenTheEditsShareAPlace() {
        assertFalse("interleaved by position, so order cannot matter",
                separableConflict().conflicts().get(0).isOrderRelevant());

        ThreeWayMerge sameSpot = ThreeWayMerge.of(lines("a", "b"), lines("a", "mine", "b"),
                lines("a", "theirs", "b"));
        assertTrue("two insertions at one point have no natural order",
                sameSpot.conflicts().get(0).isOrderRelevant());
    }

    // ── Hand edits, attributed ──────────────────────────────────────────────────────────────────

    /**
     * <b>A hand edit is charged to the regions it touched, and only those.</b>
     *
     * <p>The reason the model needed {@code Unrecognized} at all. A global "somebody typed" latch has to
     * disable every control in the view; attributing the edit leaves every untouched region working.</p>
     */
    @Test
    public void aHandEditMarksOnlyTheRegionItLandedIn() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("a", "b", "c", "d", "e"),
                lines("MINE1", "b", "c", "d", "MINE2"),
                lines("THEIRS1", "b", "c", "d", "e"));

        assertEquals("two regions: the clash at the top and my own edit at the bottom",
                2, merge.regions().size());

        List<String> edited = merge.mergedLines();
        edited.set(0, "TYPED BY HAND");

        assertEquals(1, merge.attributeHandEdit(edited));
        assertTrue("the region the edit landed in no longer matches a choice",
                merge.regions().get(0).state() instanceof RegionState.Unrecognized);
        assertFalse("and the other one is untouched",
                merge.regions().get(1).state() instanceof RegionState.Unrecognized);
    }

    /** An unchanged result attributes nothing — the common case, and it must be cheap and quiet. */
    @Test
    public void anUnchangedResultAttributesNothing() {
        ThreeWayMerge merge = ThreeWayMerge.of(lines("a", "b"), lines("a", "mine"), lines("a", "theirs"));

        assertEquals(0, merge.attributeHandEdit(merge.mergedLines()));
        assertFalse(merge.regions().get(0).state() instanceof RegionState.Unrecognized);
    }

    /** An unrecognised region counts as decided: somebody decided, by typing. */
    @Test
    public void anUnrecognisedRegionIsSettled() {
        ThreeWayMerge merge = ThreeWayMerge.of(lines("a"), lines("mine"), lines("theirs"));
        assertFalse(merge.isResolved());

        merge.regions().get(0).markUnrecognized(lines("something else entirely"));

        assertTrue(merge.isResolved());
        assertEquals(lines("something else entirely"), merge.mergedLines());
    }
}
