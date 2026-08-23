package com.crystalgui.headless;

import com.crystalgui.text.diff.DetailedDiff;
import com.crystalgui.text.diff.InnerRange;
import com.crystalgui.text.diff.LinesDiff;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.ThreeWayMerge.Kind;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Character-level refinement and the conflict resolution built on it.
 *
 * <p>These two are the same machinery seen twice: {@link LinesDiff#refine} re-runs the diff over a changed
 * block's characters to produce word marks, and {@code MagicResolve} re-runs the whole three-way
 * <em>merge</em> over a conflicting region's characters to decide whether the two sides really clash.</p>
 */
public class MagicResolveTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    // ── Refinement ──────────────────────────────────────────────────────────────────────────────

    /** A one-word edit inside a long line must mark the word, not the line. */
    @Test
    public void aChangedWordIsMarkedRatherThanTheWholeLine() {
        List<String> before = lines("public void doSomething(int count) {");
        List<String> after = lines("public void doSomething(long count) {");

        List<DetailedDiff> detailed = LinesDiff.computeDetailed(before, after);

        assertEquals(1, detailed.size());
        List<InnerRange> inner = detailed.get(0).inner();
        assertFalse("the change must be narrowed to something inside the line", inner.isEmpty());

        int marked = 0;
        for (InnerRange range : inner) marked += range.toColumn1() - range.fromColumn1();
        assertTrue("only a few characters differ, not the whole line: " + marked,
                marked < before.get(0).length() / 2);
    }

    /**
     * A pure insertion has no inner ranges.
     *
     * <p>There is no counterpart text to compare against, so the only inner range available would be the
     * whole block restated — which a view draws as a word mark over every character of an added line, on
     * top of the band already there.</p>
     */
    @Test
    public void aPureInsertionHasNothingFinerToSay() {
        List<DetailedDiff> detailed = LinesDiff.computeDetailed(lines("a", "b"), lines("a", "new", "b"));

        assertEquals(1, detailed.size());
        assertTrue(detailed.get(0).lines().isEmpty1());
        assertTrue("nothing to refine against", detailed.get(0).inner().isEmpty());
    }

    /** Inner ranges must address real positions in both texts. */
    @Test
    public void innerRangesStayInsideTheirBlock() {
        List<String> before = lines("alpha beta gamma", "delta epsilon");
        List<String> after = lines("alpha BETA gamma", "delta EPSILON");

        for (DetailedDiff detailed : LinesDiff.computeDetailed(before, after)) {
            for (InnerRange range : detailed.inner()) {
                assertTrue(range.fromLine1() >= detailed.lines().start1());
                assertTrue(range.toLine1() <= Math.max(detailed.lines().end1() - 1, 0));
                assertTrue(range.fromColumn1() >= 0);
                assertTrue(range.fromColumn1() <= before.get(range.fromLine1()).length());
            }
        }
    }

    // ── Magic resolve ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>The case it exists for.</b>
     *
     * <p>One side changes the start of a line, the other the end. At line granularity that is a conflict
     * and must be — a merge that silently combined them at word level could produce a line neither person
     * wrote. But asked a second time, one level down, the two edits plainly do not touch.</p>
     */
    @Test
    public void twoEditsOnDifferentPartsOfALineResolve() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("int total = compute(a, b);"),
                lines("long total = compute(a, b);"),
                lines("int total = compute(a, c);"));

        assertEquals("it is still a conflict at line granularity", 1, merge.conflictCount());

        Optional<List<String>> suggestion = merge.regions().get(0).suggestedResolution();
        assertTrue("but the two edits do not touch", suggestion.isPresent());
        assertEquals(lines("long total = compute(a, c);"), suggestion.get());
    }

    /**
     * Two edits to the <b>same</b> word do not resolve.
     *
     * <p>The guard that stops this being a word-level merge wearing a disguise.</p>
     */
    @Test
    public void twoEditsToTheSameWordDoNotResolve() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("int total = 1;"),
                lines("int total = 2;"),
                lines("int total = 3;"));

        assertEquals(1, merge.conflictCount());
        assertFalse("there is no honest answer here",
                merge.regions().get(0).suggestedResolution().isPresent());
    }

    /**
     * Two different insertions at the same point do not resolve.
     *
     * <p>Upstream's own rule, and the reason is that the <em>order</em> is unknowable — sorting the two
     * blocks by length or alphabetically would be inventing an answer rather than finding one.</p>
     */
    @Test
    public void twoInsertionsAtTheSamePointDoNotResolve() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("a", "b"),
                lines("a", "mine", "b"),
                lines("a", "theirs", "b"));

        assertEquals(1, merge.conflictCount());
        assertFalse("no knowable order", merge.regions().get(0).suggestedResolution().isPresent());
    }

    /** Applying the suggestions settles the merge, and it stays marked as having been a conflict. */
    @Test
    public void resolvingAutomaticallySettlesTheMergeWithoutHidingThatItWasAConflict() {
        ThreeWayMerge merge = ThreeWayMerge.of(
                lines("x", "int total = compute(a, b);", "y"),
                lines("x", "long total = compute(a, b);", "y"),
                lines("x", "int total = compute(a, c);", "y"));

        assertFalse(merge.isResolved());
        assertEquals(1, merge.resolveConflictsAutomatically());

        assertTrue("every conflict is now decided", merge.isResolved());
        assertEquals("but it is still recorded as one", Kind.CONFLICT, merge.regions().get(0).kind());
        assertEquals(lines("x", "long total = compute(a, c);", "y"), merge.mergedLines());
    }

    /** A merge with nothing ambiguous in it reports resolving nothing, rather than failing. */
    @Test
    public void aCleanMergeHasNothingToResolveAutomatically() {
        ThreeWayMerge merge = ThreeWayMerge.of(lines("a", "b"), lines("a", "b", "mine"),
                lines("theirs", "a", "b"));

        assertEquals(0, merge.resolveConflictsAutomatically());
        assertTrue(merge.isResolved());
    }
}
