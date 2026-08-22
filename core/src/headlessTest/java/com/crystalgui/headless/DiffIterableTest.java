package com.crystalgui.headless;

import com.crystalgui.text.diff.ComparisonPolicy;
import com.crystalgui.text.diff.DiffIterable;
import com.crystalgui.text.diff.DiffRange;
import com.crystalgui.text.diff.ThreeWayMerge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DiffIterable} and {@link ComparisonPolicy} — the two pieces the ported region builder rests on.
 *
 * <p>The properties worth asserting here are structural. A diff's changed and unchanged spans must
 * <b>partition</b> both texts: every line belongs to exactly one span, in order. That is what lets
 * {@code MergeRanges} intersect agreement and get non-overlapping regions for free, so if the partition
 * breaks, the guarantee the whole merge rests on breaks with it — quietly, since a merge built on a bad
 * pairing still produces text.</p>
 */
public class DiffIterableTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    /**
     * Changed and unchanged together must cover every line of both texts, exactly once, in order.
     *
     * <p>Checked by walking the two span lists merged by position and asserting each picks up where the
     * last left off on <em>both</em> sides — a gap loses lines and an overlap duplicates them, and the
     * merged output shows either as text that is simply wrong rather than as a failure.</p>
     */
    private static void partitionsBothTexts(DiffIterable iterable) {
        List<DiffRange> all = new ArrayList<>(iterable.changed());
        all.addAll(iterable.unchanged());
        all.sort((a, b) -> a.start1() != b.start1()
                ? Integer.compare(a.start1(), b.start1())
                : Integer.compare(a.start2(), b.start2()));

        int at1 = 0;
        int at2 = 0;
        for (DiffRange range : all) {
            assertEquals("side 1 must be contiguous", at1, range.start1());
            assertEquals("side 2 must be contiguous", at2, range.start2());
            at1 = range.end1();
            at2 = range.end2();
        }
        assertEquals("side 1 must be covered to the end", iterable.length1(), at1);
        assertEquals("side 2 must be covered to the end", iterable.length2(), at2);
    }

    @Test
    public void identicalTextsAreOneUnchangedSpan() {
        DiffIterable iterable = DiffIterable.of(lines("a", "b", "c"), lines("a", "b", "c"));

        assertTrue(iterable.changed().isEmpty());
        assertEquals(1, iterable.unchanged().size());
        partitionsBothTexts(iterable);
    }

    @Test
    public void aChangeSplitsTheUnchangedSpansAroundIt() {
        DiffIterable iterable = DiffIterable.of(lines("a", "b", "c"), lines("a", "X", "c"));

        assertEquals(1, iterable.changed().size());
        assertEquals("one span before it and one after", 2, iterable.unchanged().size());
        partitionsBothTexts(iterable);
    }

    @Test
    public void anInsertionIsZeroWidthOnTheOldSide() {
        DiffIterable iterable = DiffIterable.of(lines("a", "b"), lines("a", "new", "b"));

        DiffRange change = iterable.changed().get(0);
        assertEquals(0, change.length1());
        assertEquals(1, change.length2());
        partitionsBothTexts(iterable);
    }

    @Test
    public void emptyOnEitherSideStillPartitions() {
        partitionsBothTexts(DiffIterable.of(lines(), lines("a", "b")));
        partitionsBothTexts(DiffIterable.of(lines("a", "b"), lines()));
        partitionsBothTexts(DiffIterable.of(lines(), lines()));
    }

    @Test
    public void randomTextsAlwaysPartition() {
        Random random = new Random(4242L);
        for (int trial = 0; trial < 300; trial++) {
            List<String> before = new ArrayList<>();
            for (int i = 0, n = random.nextInt(30); i < n; i++) before.add("line " + random.nextInt(6));
            List<String> after = new ArrayList<>(before);
            for (int edit = 0, n = 1 + random.nextInt(4); edit < n; edit++) {
                if (after.isEmpty()) { after.add("x"); continue; }
                int at = random.nextInt(after.size());
                switch (random.nextInt(3)) {
                    case 0 -> after.add(at, "ins " + random.nextInt(6));
                    case 1 -> after.remove(at);
                    default -> after.set(at, "chg " + random.nextInt(6));
                }
            }
            partitionsBothTexts(DiffIterable.of(before, after));
        }
    }

    /**
     * The "fair" property is enforced, not assumed.
     *
     * <p>A gap between two changed spans is text both sides kept, so it is the same number of lines on
     * each. A caller handing in spans that imply otherwise has produced a pairing that cannot be merged,
     * and the failure downstream would be lines silently dropped or duplicated.</p>
     */
    @Test
    public void anUnequalUnchangedGapIsRefused() {
        try {
            // Changed [0,1)->[0,1), then the gap to the end is 4 lines on one side and 2 on the other.
            DiffIterable.fromChanged(5, 3, List.of(new DiffRange(0, 1, 0, 1)));
            fail("an unfair pairing must be refused rather than propagated");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unchanged range"));
        }
    }

    @Test
    public void outOfOrderChangesAreRefused() {
        try {
            DiffIterable.fromChanged(10, 10, List.of(
                    new DiffRange(5, 6, 5, 6),
                    new DiffRange(1, 2, 1, 2)));
            fail("changed spans must be ordered");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ordered"));
        }
    }

    // ── ComparisonPolicy ────────────────────────────────────────────────────────────────────────

    @Test
    public void theThreePoliciesNormaliseAsDocumented() {
        String line = "  a b  ";
        assertEquals("  a b  ", ComparisonPolicy.DEFAULT.normalise(line));
        assertEquals("a b", ComparisonPolicy.TRIM_WHITESPACES.normalise(line));
        assertEquals("ab", ComparisonPolicy.IGNORE_WHITESPACES.normalise(line));
    }

    @Test
    public void trimIgnoresIndentationButNotInteriorSpacing() {
        assertTrue(ComparisonPolicy.TRIM_WHITESPACES.linesEqual("  x = 1", "x = 1"));
        assertFalse("respacing an operator is a real change under TRIM",
                ComparisonPolicy.TRIM_WHITESPACES.linesEqual("x = 1", "x=1"));
        assertTrue(ComparisonPolicy.IGNORE_WHITESPACES.linesEqual("x = 1", "x=1"));
    }

    /**
     * <b>The claim the policy exists for.</b>
     *
     * <p>One side reindents a block and the other edits one line inside it. Under {@code DEFAULT} every
     * reindented line is a change on one side and the edited line is a change on the other, so the whole
     * block is one conflict a person has to read. Under {@code IGNORE_WHITESPACES} the reindentation is
     * not a change at all and only the real edit survives — which auto-merges.</p>
     *
     * <p>Asserted as a difference in <em>conflict count</em>, because that is the thing a person actually
     * pays for and the only observable that distinguishes a policy from a display option.</p>
     */
    @Test
    public void ignoringWhitespaceTurnsAReindentConflictIntoAnAutoMerge() {
        List<String> base = lines("void f() {", "int a = 1;", "int b = 2;", "int c = 3;", "}");
        List<String> reindented = lines("void f() {", "    int a = 1;", "    int b = 2;", "    int c = 3;", "}");
        List<String> edited = lines("void f() {", "int a = 1;", "int b = 99;", "int c = 3;", "}");

        ThreeWayMerge strict = ThreeWayMerge.of(base, reindented, edited, ComparisonPolicy.DEFAULT);
        ThreeWayMerge relaxed = ThreeWayMerge.of(base, reindented, edited, ComparisonPolicy.IGNORE_WHITESPACES);

        assertTrue("a reindent against an edit conflicts when whitespace counts",
                strict.conflictCount() > 0);
        assertEquals("and does not when it does not", 0, relaxed.conflictCount());
        assertTrue(relaxed.isResolved());
    }

    /** And the relaxed merge still keeps the real edit rather than quietly dropping it. */
    @Test
    public void theRelaxedMergeStillCarriesTheRealEdit() {
        List<String> base = lines("a", "b", "c");
        List<String> reindented = lines("  a", "  b", "  c");
        List<String> edited = lines("a", "CHANGED", "c");

        ThreeWayMerge merged = ThreeWayMerge.of(base, reindented, edited, ComparisonPolicy.IGNORE_WHITESPACES);

        assertEquals(0, merged.conflictCount());
        assertTrue("the edit must survive: " + merged.mergedLines(),
                merged.mergedLines().contains("CHANGED"));
    }
}
