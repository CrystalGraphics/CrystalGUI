package com.crystalgui.ui;

import com.crystalgui.core.collection.list.FixedHeightStrategy;
import com.crystalgui.core.collection.list.VariableHeightStrategy;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * P6.1.7 step 1 — rows that are not all the same height.
 *
 * <h3>The uniform case has an oracle, so use it</h3>
 * <p>When every row is the same height this must agree with {@link FixedHeightStrategy} exactly, at every
 * index and every offset. That is a far stronger check than any hand-written expectation, and it is the
 * case most likely to regress silently, since an editor is uniform until the first line wraps.</p>
 */
public class VariableHeightStrategyTest {

    // ── Agreement with the fixed strategy ───────────────────────────────────────────────────────

    @Test
    public void withUniformRowsItMatchesTheFixedStrategyExactly() {
        final float height = 12f;
        final int count = 500;
        FixedHeightStrategy fixed = new FixedHeightStrategy(height);
        VariableHeightStrategy variable = new VariableHeightStrategy(height).setCount(count);

        assertEquals(fixed.totalSize(count), variable.totalSize(count), 0.01f);
        for (int i = 0; i <= count; i++) {
            assertEquals("offsetOf(" + i + ")", fixed.offsetOf(i), variable.offsetOf(i), 0.01f);
        }
        for (float offset = -5f; offset < count * height + 20f; offset += 3.5f) {
            assertEquals("indexAt(" + offset + ")",
                    fixed.indexAt(offset, count), variable.indexAt(offset, count));
        }
    }

    // ── Variable rows ───────────────────────────────────────────────────────────────────────────

    @Test
    public void offsetsAccumulateMeasuredHeights() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(4);
        strategy.setSize(1, 30f);   // a line that wrapped into three

        assertEquals(0f, strategy.offsetOf(0), 0.01f);
        assertEquals(10f, strategy.offsetOf(1), 0.01f);
        assertEquals("row 2 starts after the wrapped row's full height", 40f, strategy.offsetOf(2), 0.01f);
        assertEquals(50f, strategy.offsetOf(3), 0.01f);
        assertEquals(60f, strategy.totalSize(4), 0.01f);
    }

    /** offsetOf and indexAt must be inverses over every row, including the tall ones. */
    @Test
    public void indexAtInvertsOffsetOf() {
        Random random = new Random(4242L);
        int count = 400;
        VariableHeightStrategy strategy = new VariableHeightStrategy(9f).setCount(count);
        for (int i = 0; i < count; i++) {
            if (random.nextInt(4) == 0) strategy.setSize(i, 9f * (1 + random.nextInt(5)));
        }

        for (int i = 0; i < count; i++) {
            float top = strategy.offsetOf(i);
            assertEquals("the top of row " + i + " is inside row " + i, i, strategy.indexAt(top, count));
            float inside = top + strategy.sizeOf(i) / 2f;
            assertEquals("the middle of row " + i + " is too", i, strategy.indexAt(inside, count));
        }
    }

    @Test
    public void anOffsetPastTheEndClampsToTheCount() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(3);
        assertEquals(3, strategy.indexAt(99999f, 3));
        assertEquals(0, strategy.indexAt(-50f, 3));
    }

    // ── Structural change ───────────────────────────────────────────────────────────────────────

    @Test
    public void insertingRowsShiftsTheOnesAfterThem() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(3);
        strategy.setSize(2, 50f);

        strategy.insert(1, 2);

        assertEquals(5, strategy.count());
        assertEquals("the measured row moved down by two default rows", 50f, strategy.sizeOf(4), 0.01f);
        assertEquals(10f, strategy.sizeOf(1), 0.01f);
        assertEquals(90f, strategy.totalSize(5), 0.01f);
    }

    @Test
    public void removingRowsPullsTheOnesAfterThemUp() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(5);
        strategy.setSize(4, 40f);

        strategy.remove(1, 2);

        assertEquals(3, strategy.count());
        assertEquals("the measured row is now index 2", 40f, strategy.sizeOf(2), 0.01f);
        assertEquals(60f, strategy.totalSize(3), 0.01f);
    }

    @Test
    public void removingPastTheEndRemovesOnlyWhatExists() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(3);
        strategy.remove(2, 99);
        assertEquals(2, strategy.count());
    }

    /**
     * <b>A model longer than what has been measured must still be scrollable to its end.</b> An editor
     * measures rows lazily — only the ones it has realised — so the count it is asked about routinely
     * exceeds the count it knows heights for. Reporting only the measured height would make the scrollbar
     * claim the document ends where measuring stopped.
     */
    @Test
    public void unmeasuredRowsAreEstimatedAtTheDefaultHeight() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(3);

        assertEquals("three measured plus seven estimated", 100f, strategy.totalSize(10), 0.01f);
        assertEquals(35f, strategy.offsetOf(3) + 5f, 0.01f);
        assertEquals("an offset in the estimated region still resolves", 5, strategy.indexAt(55f, 10));
    }

    @Test
    public void resetSizesForgetsEveryMeasurement() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(4);
        strategy.setSize(0, 100f);
        assertEquals(130f, strategy.totalSize(4), 0.01f);

        strategy.resetSizes();
        assertEquals("a wrap-width change invalidates every height", 40f, strategy.totalSize(4), 0.01f);
    }

    @Test
    public void aNonPositiveDefaultIsRejected() {
        try {
            new VariableHeightStrategy(0f);
            fail("zero would put every row at the same offset -- the whole model in one window");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("positive"));
        }
    }

    // ── Cost ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The O(n) rebuild is the deliberate trade, so it is measured rather than asserted.</b>
     *
     * <p>Heights are stored plainly and the cumulative array is rebuilt lazily after a change, so a burst
     * of edits in one frame costs one rebuild rather than one per edit. This runs 100,000 rows and
     * alternates mutate-then-query so every single query pays for a full rebuild — the worst case, and
     * far worse than real use.</p>
     *
     * <p>The bound is deliberately loose. The point is to catch an accidental O(n²) — a rebuild per
     * query <em>inside</em> a loop, say — not to police microseconds on a shared CI machine.</p>
     */
    @Test
    public void rebuildCostIsNegligibleAtScale() {
        int count = 100_000;
        VariableHeightStrategy strategy = new VariableHeightStrategy(12f).setCount(count);

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            strategy.setSize(i * 37 % count, 12f + (i % 5));
            strategy.indexAt(count * 6f, count);   // forces the rebuild
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("200 forced rebuilds over 100k rows took " + elapsedMillis
                + "ms, which suggests the rebuild is not linear", elapsedMillis < 2000L);
    }

    /** Queries after a rebuild must not rebuild again — the whole reason for the dirty flag. */
    @Test
    public void repeatedQueriesWithoutChangesStayCorrect() {
        VariableHeightStrategy strategy = new VariableHeightStrategy(10f).setCount(50);
        strategy.setSize(10, 40f);

        float first = strategy.totalSize(50);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, strategy.totalSize(50), 0.01f);
            assertEquals(10, strategy.indexAt(100f, 50));
        }
    }
}
