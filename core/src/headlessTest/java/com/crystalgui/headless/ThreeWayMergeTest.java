package com.crystalgui.headless;

import com.crystalgui.text.diff.LineDiff;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.ThreeWayMerge.Kind;
import com.crystalgui.text.diff.ThreeWayMerge.Region;
import com.crystalgui.text.diff.RegionState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6 <b>6.7</b> — the three-way merge under the merger.
 *
 * <h3>What is worth asserting</h3>
 *
 * <p><b>The identities</b> are the correctness net, and they are the assertions a subtly-wrong merge cannot
 * pass. If one side did not change, the merge <em>is</em> the other side — exactly, including the lines
 * nobody touched. An off-by-one region boundary, a mis-accumulated coordinate delta, or a dropped tail all
 * fail that, and none of them fails a test that counts conflicts.</p>
 *
 * <p><b>The non-overlap invariant</b> is asserted separately and over random input, because it is the one
 * whose failure mode is corruption rather than confusion: two regions claiming the same base lines cannot be
 * assembled into an output at all, and the result would be a merged file containing text twice.</p>
 *
 * <p><b>The auto-merge property</b> is the whole reason a merger is three-way. Two people editing different
 * parts of a file must produce zero conflicts and a result carrying both edits. If that does not hold, the
 * tool is a two-way chooser wearing a base.</p>
 */
public class ThreeWayMergeTest {

    private static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    // ── The identities ──────────────────────────────────────────────────────────────────────────

    @Test
    public void nobodyChangedAnythingSoTheMergeIsTheBase() {
        String base = text("a", "b", "c");
        ThreeWayMerge merge = ThreeWayMerge.of(base, base, base);

        assertTrue("no region to report", merge.regions().isEmpty());
        assertEquals(0, merge.conflictCount());
        assertTrue(merge.isResolved());
        assertEquals(base, merge.merged());
    }

    @Test
    public void onlyIChangedItSoTheMergeIsMine() {
        String base = text("a", "b", "c");
        String mine = text("a", "changed", "c", "appended");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, base);

        assertEquals("nothing for a person to decide", 0, merge.conflictCount());
        assertEquals(mine, merge.merged());
        for (Region region : merge.regions()) {
            assertEquals(Kind.MINE_ONLY, region.kind());
        }
    }

    @Test
    public void onlyTheyChangedItSoTheMergeIsTheirs() {
        String base = text("a", "b", "c");
        String theirs = text("prepended", "a", "b", "different");

        ThreeWayMerge merge = ThreeWayMerge.of(base, base, theirs);

        assertEquals(0, merge.conflictCount());
        assertEquals(theirs, merge.merged());
        for (Region region : merge.regions()) {
            assertEquals(Kind.THEIRS_ONLY, region.kind());
        }
    }

    /**
     * Both sides made the same edit — which happens constantly, because two people fixing the same typo is
     * not a conflict and reporting it as one is how a merge tool trains people to stop reading it.
     */
    @Test
    public void theSameEditOnBothSidesIsNotAConflict() {
        String base = text("a", "teh", "c");
        String both = text("a", "the", "c");

        ThreeWayMerge merge = ThreeWayMerge.of(base, both, both);

        assertEquals(1, merge.regions().size());
        assertEquals(Kind.BOTH_SAME, merge.regions().get(0).kind());
        assertEquals(0, merge.conflictCount());
        assertEquals(both, merge.merged());
    }

    // ── The auto-merge property ─────────────────────────────────────────────────────────────────

    /** The whole reason for a base: edits that do not touch each other both survive, unasked. */
    @Test
    public void disjointEditsBothApplyWithNoConflict() {
        String base = text("one", "two", "three", "four", "five", "six", "seven", "eight");
        String mine = text("ONE", "two", "three", "four", "five", "six", "seven", "eight");
        String theirs = text("one", "two", "three", "four", "five", "six", "seven", "EIGHT");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);

        assertEquals("two independent edits, nothing to ask", 0, merge.conflictCount());
        assertTrue(merge.isResolved());
        assertEquals(text("ONE", "two", "three", "four", "five", "six", "seven", "EIGHT"), merge.merged());
    }

    /** An insertion by each side, in different places. Zero-width base ranges are the fiddly case. */
    @Test
    public void disjointInsertionsBothApply() {
        String base = text("a", "b", "c", "d");
        String mine = text("a", "mine", "b", "c", "d");
        String theirs = text("a", "b", "c", "theirs", "d");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);

        assertEquals(0, merge.conflictCount());
        assertEquals(text("a", "mine", "b", "c", "theirs", "d"), merge.merged());
    }

    // ── The conflicts ───────────────────────────────────────────────────────────────────────────

    @Test
    public void theSameLineChangedTwoWaysIsAConflict() {
        String base = text("a", "b", "c");
        String mine = text("a", "mine", "c");
        String theirs = text("a", "theirs", "c");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);

        assertEquals(1, merge.conflictCount());
        assertFalse("a conflict is not resolved just because it defaults somewhere",
                merge.isResolved());

        Region conflict = merge.conflicts().get(0);
        assertTrue("a conflict starts pointed at mine so nothing is silently discarded",
                conflict.state() instanceof RegionState.Mine);
        assertEquals(java.util.Collections.singletonList("mine"), conflict.mineLines());
        assertEquals(java.util.Collections.singletonList("theirs"), conflict.theirsLines());
        assertEquals(java.util.Collections.singletonList("b"), conflict.baseLines());
    }

    /**
     * <b>A conflict pre-pointed at mine is not a conflict somebody resolved to mine.</b>
     *
     * <p>They produce identical output, so nothing about the text can tell them apart — which is exactly
     * why the settled flag exists. Without it a merge reports itself finished before anybody has read it,
     * and the OK button is live on arrival.</p>
     */
    @Test
    public void aConflictIsUnresolvedUntilSomebodyChoosesEvenTheDefault() {
        ThreeWayMerge merge = ThreeWayMerge.of(text("a", "b"), text("a", "mine"), text("a", "theirs"));
        Region conflict = merge.conflicts().get(0);

        assertFalse(merge.isResolved());
        String beforeChoosing = merge.merged();

        conflict.acceptMine();

        assertTrue("choosing the side it already showed still settles it", merge.isResolved());
        assertEquals("and changes nothing about the output", beforeChoosing, merge.merged());
    }

    @Test
    public void choosingTheirsChangesTheMergedText() {
        ThreeWayMerge merge = ThreeWayMerge.of(text("a", "b", "c"), text("a", "mine", "c"),
                text("a", "theirs", "c"));

        merge.conflicts().get(0).acceptTheirs();

        assertEquals(text("a", "theirs", "c"), merge.merged());
    }

    @Test
    public void takingBothKeepsMineFirst() {
        ThreeWayMerge merge = ThreeWayMerge.of(text("a", "b", "c"), text("a", "mine", "c"),
                text("a", "theirs", "c"));

        merge.conflicts().get(0).accept(new RegionState.Both(true, false));

        assertEquals(text("a", "mine", "theirs", "c"), merge.merged());
    }

    @Test
    public void customTextIsWhatMakesTheCentrePaneAnEditor() {
        ThreeWayMerge merge = ThreeWayMerge.of(text("a", "b", "c"), text("a", "mine", "c"),
                text("a", "theirs", "c"));

        merge.conflicts().get(0).acceptCustom(java.util.Collections.singletonList("neither"));

        assertTrue(merge.regions().get(0).state() instanceof RegionState.Custom);
        assertEquals(text("a", "neither", "c"), merge.merged());
        assertTrue(merge.isResolved());
    }

    /** {@code acceptAll} settles conflicts and must leave auto-merged regions alone. */
    @Test
    public void acceptAllTouchesConflictsAndNothingElse() {
        String base = text("a", "b", "c", "d");
        String mine = text("MINE-ONLY", "b", "conflict-mine", "d");
        String theirs = text("a", "b", "conflict-theirs", "d");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);
        assertEquals(1, merge.conflictCount());

        merge.acceptAll(new RegionState.Theirs());

        assertTrue(merge.isResolved());
        assertEquals("the region only I touched is still mine",
                text("MINE-ONLY", "b", "conflict-theirs", "d"), merge.merged());
    }

    /**
     * <b>Two of my edits against one spanning edit of theirs is ONE conflict.</b>
     *
     * <p>The region-not-hunk rule. Reported as three, the person is asked the same question three times and
     * their answers can disagree with each other; worse, the three resolutions overlap and cannot be
     * assembled.</p>
     */
    @Test
    public void adjacentEditsAgainstOneSpanningEditAreASingleConflict() {
        String base = text("head", "1", "2", "3", "4", "tail");
        String mine = text("head", "ONE", "2", "THREE", "4", "tail");
        String theirs = text("head", "replaced entirely", "tail");

        ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);

        assertEquals("one span of base lines, one question", 1, merge.conflictCount());
        assertEquals(1, merge.regions().size());
        assertNoOverlap(merge);
    }

    // ── Structural invariants, over random input ────────────────────────────────────────────────

    /**
     * Regions must never overlap in base coordinates.
     *
     * <p>The one invariant whose failure is corruption rather than confusion — overlapping regions each
     * claim the same base lines, so the assembled output contains that text twice or drops it entirely.</p>
     */
    private static void assertNoOverlap(ThreeWayMerge merge) {
        int previousEnd = 0;
        for (Region region : merge.regions()) {
            assertTrue("regions must be ordered and disjoint: " + region.baseFrom() + " after " + previousEnd,
                    region.baseFrom() >= previousEnd);
            assertTrue("a region cannot end before it starts", region.baseTo() >= region.baseFrom());
            assertTrue("mine range must be well formed", region.mineTo() >= region.mineFrom());
            assertTrue("theirs range must be well formed", region.theirsTo() >= region.theirsFrom());
            previousEnd = region.baseTo();
        }
        assertTrue("the last region cannot run off the end of the base",
                previousEnd <= merge.baseLines().size());
    }

    @Test
    public void randomEditsKeepTheStructuralInvariants() {
        Random random = new Random(20260822L);
        for (int trial = 0; trial < 300; trial++) {
            List<String> base = randomLines(random, 1 + random.nextInt(30));
            List<String> mine = randomlyEdited(random, base);
            List<String> theirs = randomlyEdited(random, base);

            ThreeWayMerge merge = ThreeWayMerge.of(base, mine, theirs);
            assertNoOverlap(merge);

            // Every base line is either copied or claimed by exactly one region -- so resolving everything
            // to BASE must reproduce the base exactly, whatever the two sides did.
            for (Region region : merge.regions()) region.accept(new RegionState.Base());
            assertEquals("resolving everything to the ancestor must rebuild the ancestor",
                    join(base), merge.merged());
        }
    }

    /**
     * The identities again, over random input — where a coordinate-delta bug actually lives.
     *
     * <p>The written cases above are small enough to reason about; a mis-accumulated delta only shows up
     * once there are several hunks with differing sizes ahead of the one that goes wrong.</p>
     */
    @Test
    public void randomOneSidedEditsAlwaysMergeToThatSide() {
        Random random = new Random(6060842L);
        for (int trial = 0; trial < 300; trial++) {
            List<String> base = randomLines(random, 1 + random.nextInt(30));
            List<String> edited = randomlyEdited(random, base);

            ThreeWayMerge onlyMine = ThreeWayMerge.of(base, edited, base);
            assertEquals("only I changed it", join(edited), onlyMine.merged());
            assertEquals(0, onlyMine.conflictCount());

            ThreeWayMerge onlyTheirs = ThreeWayMerge.of(base, base, edited);
            assertEquals("only they changed it", join(edited), onlyTheirs.merged());
            assertEquals(0, onlyTheirs.conflictCount());

            ThreeWayMerge bothSame = ThreeWayMerge.of(base, edited, edited);
            assertEquals("both made the same edit", join(edited), bothSame.merged());
            assertEquals(0, bothSame.conflictCount());
        }
    }

    private static List<String> randomLines(Random random, int count) {
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add("line " + random.nextInt(8));
        return lines;
    }

    private static List<String> randomlyEdited(Random random, List<String> source) {
        List<String> edited = new ArrayList<>(source);
        for (int edit = 0; edit < 1 + random.nextInt(4); edit++) {
            if (edited.isEmpty()) {
                edited.add("inserted " + random.nextInt(8));
                continue;
            }
            int at = random.nextInt(edited.size());
            switch (random.nextInt(3)) {
                case 0 -> edited.add(at, "inserted " + random.nextInt(8));
                case 1 -> edited.remove(at);
                default -> edited.set(at, "changed " + random.nextInt(8));
            }
        }
        return edited;
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    /** Sanity: the merge's own line splitting is {@link LineDiff}'s, so a trailing newline behaves. */
    @Test
    public void mergeUsesTheSameLineSplittingAsTheDiff() {
        assertEquals(LineDiff.lines("a\nb\n"), ThreeWayMerge.of("a\nb\n", "a\nb\n", "a\nb\n").baseLines());
    }
}
