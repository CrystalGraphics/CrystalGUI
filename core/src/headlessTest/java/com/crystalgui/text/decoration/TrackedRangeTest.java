package com.crystalgui.text.decoration;

import com.crystalgui.text.TextBuffer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * §17.1's primitive, pinned against Monaco's stickiness cases.
 *
 * <p>Headless because it is document logic and touches nothing that needs a GL context — the same reason
 * {@code text/} lives here rather than in {@code test/}.</p>
 *
 * <p>The cases are the boundary ones, because <b>the boundary is the entire difficulty</b>. Insertion in the
 * middle of a range and insertion far away are arithmetic that could hardly be wrong; insertion exactly at
 * an edge is the one place where two answers are both defensible, which is why there are four modes rather
 * than a boolean and why each one gets a test here.</p>
 */
public class TrackedRangeTest {

    private static TextBuffer bufferOf(String text) {
        return new TextBuffer(text);
    }

    // ── The four modes at the START edge ────────────────────────────────────────────────────────

    @Test
    public void alwaysGrowsSwallowsAnInsertionAtItsStart() {
        TextBuffer buffer = bufferOf("abcdef");
        TrackedRange range = buffer.decorations()
                .add(2, 4, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "test", null);

        buffer.insert(2, "XY");

        assertEquals("the start stays put, so the new text falls inside", 2, range.from());
        assertEquals(6, range.to());
    }

    @Test
    public void neverGrowsPushesItsStartPastAnInsertionThere() {
        TextBuffer buffer = bufferOf("abcdef");
        TrackedRange range = buffer.decorations()
                .add(2, 4, Stickiness.NEVER_GROWS_WHEN_TYPING_AT_EDGES, "test", null);

        buffer.insert(2, "XY");

        assertEquals("the start moves past the insertion, so it stays outside", 4, range.from());
        assertEquals(6, range.to());
    }

    // ── The four modes at the END edge ──────────────────────────────────────────────────────────

    @Test
    public void alwaysGrowsSwallowsAnInsertionAtItsEnd() {
        TextBuffer buffer = bufferOf("abcdef");
        TrackedRange range = buffer.decorations()
                .add(2, 4, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "test", null);

        buffer.insert(4, "XY");

        assertEquals(2, range.from());
        assertEquals("the end moves past the insertion, so the new text falls inside", 6, range.to());
    }

    @Test
    public void neverGrowsLeavesAnInsertionAtItsEndOutside() {
        TextBuffer buffer = bufferOf("abcdef");
        TrackedRange range = buffer.decorations()
                .add(2, 4, Stickiness.NEVER_GROWS_WHEN_TYPING_AT_EDGES, "test", null);

        buffer.insert(4, "XY");

        assertEquals(2, range.from());
        assertEquals("the end stays put, so the new text falls outside", 4, range.to());
    }

    /**
     * The two asymmetric modes, which are the whole reason a boolean will not do.
     *
     * <p>Both edges of each is asserted in one test, because the mode is precisely the <em>combination</em>
     * — checking one edge of {@code GROWS_ONLY_WHEN_TYPING_BEFORE} passes equally against
     * {@code ALWAYS_GROWS}, so a per-edge test proves nothing about which mode is in play.</p>
     */
    @Test
    public void growsOnlyWhenTypingBeforeGrowsAtTheStartAndNotAtTheEnd() {
        TextBuffer atStart = bufferOf("abcdef");
        TrackedRange first = atStart.decorations()
                .add(2, 4, Stickiness.GROWS_ONLY_WHEN_TYPING_BEFORE, "test", null);
        atStart.insert(2, "XY");
        assertEquals(2, first.from());
        assertEquals(6, first.to());

        TextBuffer atEnd = bufferOf("abcdef");
        TrackedRange second = atEnd.decorations()
                .add(2, 4, Stickiness.GROWS_ONLY_WHEN_TYPING_BEFORE, "test", null);
        atEnd.insert(4, "XY");
        assertEquals(2, second.from());
        assertEquals("must NOT grow at the end", 4, second.to());
    }

    @Test
    public void growsOnlyWhenTypingAfterGrowsAtTheEndAndNotAtTheStart() {
        TextBuffer atStart = bufferOf("abcdef");
        TrackedRange first = atStart.decorations()
                .add(2, 4, Stickiness.GROWS_ONLY_WHEN_TYPING_AFTER, "test", null);
        atStart.insert(2, "XY");
        assertEquals("must NOT grow at the start", 4, first.from());
        assertEquals(6, first.to());

        TextBuffer atEnd = bufferOf("abcdef");
        TrackedRange second = atEnd.decorations()
                .add(2, 4, Stickiness.GROWS_ONLY_WHEN_TYPING_AFTER, "test", null);
        atEnd.insert(4, "XY");
        assertEquals(2, second.from());
        assertEquals(6, second.to());
    }

    // ── The ordinary cases, which must keep working ─────────────────────────────────────────────

    @Test
    public void anEditBeforeARangeShiftsItWhole() {
        TextBuffer buffer = bufferOf("hello world");
        TrackedRange range = buffer.decorations().add(6, 11);

        buffer.insert(0, ">>> ");

        assertEquals(10, range.from());
        assertEquals(15, range.to());
    }

    @Test
    public void anEditAfterARangeLeavesItAlone() {
        TextBuffer buffer = bufferOf("hello world");
        TrackedRange range = buffer.decorations().add(0, 5);

        buffer.insert(11, "!!!");

        assertEquals(0, range.from());
        assertEquals(5, range.to());
    }

    @Test
    public void aDeletionInsideARangeShrinksIt() {
        TextBuffer buffer = bufferOf("abcdefgh");
        TrackedRange range = buffer.decorations().add(1, 7);

        buffer.delete(3, 5);

        assertEquals(1, range.from());
        assertEquals(5, range.to());
    }

    // ── Collapse ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void deletingEverythingARangeCoveredCollapsesItAndSaysSo() {
        TextBuffer buffer = bufferOf("let value = 1;");
        TrackedRange range = buffer.decorations().add(4, 9);

        buffer.delete(4, 9);

        assertTrue("its text is gone", range.isEmpty());
        assertTrue("and that must be distinguishable from being born empty", range.collapsedByEdit());
    }

    @Test
    public void aRangeThatWasBornEmptyIsNotReportedAsCollapsed() {
        // The distinction SquigglesPart draws: a zero-width diagnostic ("expected ';'") is widened to one
        // character so it can be seen, and doing that to a range whose word was deleted paints a mark over
        // whatever moved into its place. Only collapsedByEdit tells them apart -- isEmpty() is true of both.
        TextBuffer buffer = bufferOf("int x = 1");
        TrackedRange range = buffer.decorations().add(9, 9);

        buffer.insert(0, "  ");

        assertTrue(range.isEmpty());
        assertFalse(range.collapsedByEdit());
    }

    // ── Lanes ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void replacingOneLaneLeavesEveryOtherLaneAlone() {
        TextBuffer buffer = bufferOf("abcdefghij");
        TrackedRange other = buffer.decorations()
                .add(0, 2, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "brackets", null);
        buffer.decorations().add(4, 6, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "diagnostic", null);

        buffer.decorations().replaceLane("diagnostic", Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES,
                List.of(DecorationSet.Entry.of(7, 9)));

        assertEquals(1, buffer.decorations().inLane("brackets").size());
        assertSame(other, buffer.decorations().inLane("brackets").get(0));
        List<TrackedRange> replaced = buffer.decorations().inLane("diagnostic");
        assertEquals(1, replaced.size());
        assertEquals(7, replaced.get(0).from());
    }

    @Test
    public void aReplacedRangeIsMarkedRemovedSoAStaleReferenceIsInert() {
        TextBuffer buffer = bufferOf("abcdefghij");
        TrackedRange first = buffer.decorations().add(1, 3,
                Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "diagnostic", null);

        buffer.decorations().replaceLane("diagnostic", Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES,
                List.of(DecorationSet.Entry.of(5, 7)));

        assertTrue("a holder must be able to tell its range is gone", first.isRemoved());
    }

    // ── Ordering ────────────────────────────────────────────────────────────────────────────────

    /**
     * The trap {@code DecorationSet.resortIfNeeded} exists for.
     *
     * <p>Mapping looks order-preserving because it is monotonic — but only for a fixed {@code assoc}. Two
     * ranges starting at the same offset under different stickiness are separated by an insertion there,
     * and they come out in the opposite order. Without the re-sort the binary search in
     * {@link DecorationSet#overlapping} walks a list that is no longer sorted and silently misses ranges.
     * </p>
     */
    @Test
    public void differentStickinessAtOneOffsetCanReorderRangesAndTheSetRepairsIt() {
        TextBuffer buffer = bufferOf("abcdefghij");
        buffer.decorations().add(5, 8, Stickiness.NEVER_GROWS_WHEN_TYPING_AT_EDGES, "a", "never");
        buffer.decorations().add(5, 9, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, "b", "always");

        buffer.insert(5, "XYZ");

        List<TrackedRange> all = buffer.decorations().all();
        assertEquals("always-grows kept its start and must now sort first", "always", all.get(0).payload());
        for (int i = 1; i < all.size(); i++) {
            assertTrue("the set must be in start order after an adjustment",
                    all.get(i - 1).from() <= all.get(i).from());
        }
    }

    @Test
    public void overlappingFindsARangeThatStartsBeforeTheWindowAndReachesIn() {
        TextBuffer buffer = bufferOf("0123456789");
        buffer.decorations().add(0, 8);
        buffer.decorations().add(9, 10);

        List<TrackedRange> hits = buffer.decorations().overlapping(5, 7);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).from());
    }

    // ── The thing this whole primitive exists for ───────────────────────────────────────────────

    /**
     * Typing on the line ABOVE a mark must not move the mark off its word.
     *
     * <p>This is the defect §17.1 names, and it is worth having as its own test because it is the one a
     * person would report: the squiggle was under {@code undefinedName}, a line was added above it, and the
     * squiggle stayed at the same <em>offset</em> — which is now three characters into a different line.
     * Every per-mode test above can pass while this one fails, if the ranges are never actually adjusted.</p>
     */
    @Test
    public void aMarkStaysOnItsWordWhenALineIsInsertedAboveIt() {
        TextBuffer buffer = bufferOf("int a = 1;\nundefinedName();\n");
        int wordStart = buffer.toString().indexOf("undefinedName");
        TrackedRange mark = buffer.decorations().add(wordStart, wordStart + "undefinedName".length());

        buffer.insert(0, "// a comment\n");

        int nowStart = buffer.toString().indexOf("undefinedName");
        assertEquals(nowStart, mark.from());
        assertEquals("undefinedName",
                buffer.document().slice(mark.from(), mark.to()).toString());
    }
}
