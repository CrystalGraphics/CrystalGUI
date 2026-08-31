package com.crystalgui.core.collection.list;

import com.crystalgui.core.collection.list.FixedHeightStrategy;
import com.crystalgui.core.collection.list.ItemSizeStrategy;
import java.util.Arrays;

/**
 * Rows with individually measured heights — what a soft-wrapped code line needs, since it occupies as
 * many visual rows as it wraps into.
 *
 * <p>The counterpart to {@link FixedHeightStrategy}, and deliberately a separate implementation rather
 * than a generalisation of it: with a fixed size, offset&rarr;index is a division, and paying for a
 * search in every uniform list would be pure cost.</p>
 *
 * <h3>Lazy prefix sums, not a Fenwick tree</h3>
 * <p>The obvious structure for "update one height, query cumulative offsets" is a Fenwick tree: O(log n)
 * for both. It is the wrong fit for how this is actually used. A frame issues <em>many</em> queries —
 * one per realised row, plus the scroll-to-index and hit-test paths — and mutates rarely, in bursts,
 * when the document or the wrap width changes. So heights are stored plainly, the cumulative array is
 * rebuilt <b>lazily on the next query after any change</b>, and lookups are a binary search over it.</p>
 *
 * <p>That makes a burst of edits in one frame cost <em>one</em> rebuild rather than one per edit, and it
 * makes the common case — a frame that changed nothing — cost nothing at all. The rebuild is O(n), which
 * is the honest trade: {@code rebuildCostIsNegligibleAtScale} measures it at 100,000 rows so the claim is
 * a number rather than an assertion.</p>
 *
 * <h3>Structural changes</h3>
 * <p>{@link #insert} and {@link #remove} shift the tail with {@code System.arraycopy}. An editor inserts
 * a line per Enter, not per keystroke, and the copy is a single contiguous move — cheaper in practice
 * than the pointer chasing a tree would do at these sizes. If that ever stops being true the answer is a
 * summary tree, which this engine already has one of in {@code Rope}; it is not needed yet, and building
 * a second one speculatively would be the more expensive mistake.</p>
 */
public final class VariableHeightStrategy implements ItemSizeStrategy {

    private float defaultSize;
    private float[] sizes;
    private int count;

    /** {@code prefix[i]} is the offset of the top of row {@code i}; length is {@code count + 1}. */
    private float[] prefix = new float[] { 0f };
    private boolean prefixDirty = true;

    public VariableHeightStrategy(float defaultSize) {
        setDefaultSize(defaultSize);
        this.sizes = new float[16];
        Arrays.fill(sizes, this.defaultSize);
    }

    /**
     * The height a row has until it is measured.
     *
     * <p>Positive for the same reason {@link FixedHeightStrategy} demands it: zero would put every row at
     * the same offset, and the realised window would become "the whole model at once" — the exact failure
     * virtualisation exists to prevent, arrived at silently.</p>
     */
    public VariableHeightStrategy setDefaultSize(float size) {
        if (!(size > 0f)) {
            throw new IllegalArgumentException("Default row height must be positive, was " + size);
        }
        this.defaultSize = size;
        return this;
    }

    public float defaultSize() {
        return defaultSize;
    }

    public int count() {
        return count;
    }

    /** Grows or shrinks the row count, giving any new rows {@link #defaultSize()}. */
    public VariableHeightStrategy setCount(int newCount) {
        int wanted = Math.max(0, newCount);
        if (wanted == count) return this;
        ensureCapacity(wanted);
        if (wanted > count) Arrays.fill(sizes, count, wanted, defaultSize);
        count = wanted;
        prefixDirty = true;
        return this;
    }

    /** Sets one row's measured height. No-ops when unchanged, so re-measuring costs nothing. */
    public VariableHeightStrategy setSize(int index, float size) {
        if (index < 0 || index >= count) return this;
        if (!(size > 0f)) size = defaultSize;
        if (sizes[index] == size) return this;
        sizes[index] = size;
        prefixDirty = true;
        return this;
    }

    /** Inserts {@code howMany} rows at {@code index}, each at {@link #defaultSize()}. */
    public VariableHeightStrategy insert(int index, int howMany) {
        if (howMany <= 0) return this;
        int at = Math.max(0, Math.min(index, count));
        ensureCapacity(count + howMany);
        System.arraycopy(sizes, at, sizes, at + howMany, count - at);
        Arrays.fill(sizes, at, at + howMany, defaultSize);
        count += howMany;
        prefixDirty = true;
        return this;
    }

    /** Removes {@code howMany} rows starting at {@code index}. */
    public VariableHeightStrategy remove(int index, int howMany) {
        if (howMany <= 0 || count == 0) return this;
        int at = Math.max(0, Math.min(index, count));
        int removed = Math.min(howMany, count - at);
        if (removed <= 0) return this;
        System.arraycopy(sizes, at + removed, sizes, at, count - at - removed);
        count -= removed;
        prefixDirty = true;
        return this;
    }

    /** Forgets every measurement — for a wrap-width change, where every row's height is now unknown. */
    public VariableHeightStrategy resetSizes() {
        Arrays.fill(sizes, 0, count, defaultSize);
        prefixDirty = true;
        return this;
    }

    // ── ItemSizeStrategy ────────────────────────────────────────────────────────────────────────

    @Override
    public float sizeOf(int index) {
        return index < 0 || index >= count ? defaultSize : sizes[index];
    }

    @Override
    public float totalSize(int requestedCount) {
        rebuildIfDirty();
        int at = Math.max(0, Math.min(requestedCount, count));
        // Rows past what has been measured are assumed to be default height, so a caller whose model has
        // outgrown this strategy gets an estimate rather than a short document that cannot scroll to its
        // own end.
        return prefix[at] + Math.max(0, requestedCount - count) * defaultSize;
    }

    @Override
    public float offsetOf(int index) {
        rebuildIfDirty();
        if (index <= 0) return 0f;
        if (index >= count) return prefix[count] + (index - count) * defaultSize;
        return prefix[index];
    }

    @Override
    public int indexAt(float offset, int requestedCount) {
        if (offset <= 0f) return 0;
        rebuildIfDirty();
        int limit = Math.max(0, Math.min(requestedCount, count));
        if (limit == 0 || offset >= prefix[limit]) {
            // Past everything measured: fall back to the estimate, which is what keeps a partially
            // measured document scrollable to its end.
            float overflow = offset - prefix[limit];
            int extra = (int) (overflow / defaultSize);
            return Math.max(0, Math.min(requestedCount, limit + extra));
        }
        // Largest i with prefix[i] <= offset. Arrays.binarySearch gives the insertion point when absent,
        // and the exact index when the offset lands on a boundary -- which is the row that starts there.
        int found = Arrays.binarySearch(prefix, 0, limit + 1, offset);
        int index = found >= 0 ? found : -found - 2;
        return Math.max(0, Math.min(requestedCount, index));
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    private void rebuildIfDirty() {
        if (!prefixDirty) return;
        if (prefix.length < count + 1) prefix = new float[Math.max(count + 1, prefix.length * 2)];
        float running = 0f;
        prefix[0] = 0f;
        for (int i = 0; i < count; i++) {
            running += sizes[i];
            prefix[i + 1] = running;
        }
        prefixDirty = false;
    }

    private void ensureCapacity(int needed) {
        if (sizes.length >= needed) return;
        int grown = Math.max(needed, sizes.length * 2);
        float[] next = new float[grown];
        System.arraycopy(sizes, 0, next, 0, count);
        Arrays.fill(next, count, grown, defaultSize);
        sizes = next;
    }
}
