package com.crystalgui.text.decoration;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.ChangeSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Every {@link TrackedRange} on one document, kept in start order and moved with the text.
 *
 * <h3>The one new L0 primitive (§17.1)</h3>
 *
 * <p>The missing piece under every squiggle: a range that is still right after you type above it. Before
 * this, a diagnostic was stored as row/column and resolved to an offset at paint time, so an edit anywhere
 * earlier in the file left every mark below it pointing at the wrong text — visibly, and only until the next
 * compile landed, which is what made it read as lag rather than as a bug.</p>
 *
 * <h3>A sorted array, and not a tree</h3>
 *
 * <p>Monaco uses a red-black interval tree because a Monaco document is a million lines with tens of
 * thousands of decorations. A script is a few hundred lines with tens, and at that size a binary search over
 * an array beats a tree on every axis including the one nobody measures — being obviously correct. The
 * interval tree is an internal swap behind this exact API if profiling ever asks for it; §17.1's instruction
 * was not to build it speculatively.</p>
 *
 * <h3>Re-sorting after an edit is not paranoia</h3>
 *
 * <p>It would be easy to assume mapping preserves order, since it is monotonic. It is monotonic <em>for a
 * fixed {@code assoc}</em>, and different ranges have different ones: two ranges starting at the same offset,
 * one {@link Stickiness#ALWAYS_GROWS_WHEN_TYPING_AT_EDGES} and one
 * {@link Stickiness#NEVER_GROWS_WHEN_TYPING_AT_EDGES}, are separated by an insertion at that offset and come
 * out in the opposite order. So order is <b>checked</b> after every adjustment and repaired only when it
 * actually broke, which is almost never — a sortedness scan is one pass and the sort it usually skips is
 * {@code n log n}.</p>
 *
 * <h3>Lanes replace, they do not merge</h3>
 *
 * <p>{@link #replaceLane} is the verb every producer wants and is the same shape {@code DiagnosticSet} keys
 * its owners with, for the same reason: a producer either succeeds or reports its complete findings, and
 * there is no such thing as "one of my ranges is no longer valid" arriving alone.</p>
 */
public final class DecorationSet {

    /** Where a range with no lane named goes. */
    public static final String DEFAULT_LANE = "default";

    /**
     * Fires when the contents change — a range added, removed, or a lane replaced.
     *
     * <p><b>Not on adjustment.</b> An edit moves every range, and every consumer of this is already
     * repainting for that edit; announcing here as well would mean a second full repaint per keystroke for
     * information the consumer necessarily already has. Same reasoning as {@code TreeObserver} deliberately
     * not hooking {@code onStyleChanged}.</p>
     */
    public final Signal.Action onChanged = new Signal.Action();

    /** Sorted by {@link TrackedRange#from()}. See the class note on why that is maintained rather than assumed. */
    private final List<TrackedRange> ranges = new ArrayList<>();

    public int size() {
        return ranges.size();
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Everything, in start order. */
    public List<TrackedRange> all() {
        return Collections.unmodifiableList(ranges);
    }

    // ── Adding and removing ─────────────────────────────────────────────────────────────────────

    /** A range in {@link #DEFAULT_LANE} with the default stickiness. */
    public TrackedRange add(int from, int to) {
        return add(from, to, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, DEFAULT_LANE, null);
    }

    public TrackedRange add(int from, int to, Stickiness stickiness, String lane, @Nullable Object payload) {
        TrackedRange range = new TrackedRange(from, to, stickiness, lane, payload);
        insertSorted(range);
        onChanged.emit();
        return range;
    }

    /** True when it was there. A range already removed is silently ignored, so a double-release is safe. */
    public boolean remove(@Nullable TrackedRange range) {
        if (range == null || range.isRemoved()) return false;
        if (!ranges.remove(range)) return false;
        range.markRemoved();
        onChanged.emit();
        return true;
    }

    /**
     * Swaps everything in {@code lane} for {@code replacements}, leaving every other lane alone.
     *
     * <p>The ranges are given as {@code [from, to]} pairs plus a payload each rather than as built
     * {@link TrackedRange}s, because a caller that built them would hold references to objects this method
     * is about to discard — and the natural next line is to keep those references, which then track nothing.
     * Handing back the freshly-installed list makes the live objects the only ones a caller ever sees.</p>
     */
    public List<TrackedRange> replaceLane(String lane, Stickiness stickiness, List<Entry> replacements) {
        String key = lane == null ? DEFAULT_LANE : lane;
        boolean removedAny = false;
        for (int i = ranges.size() - 1; i >= 0; i--) {
            if (ranges.get(i).lane().equals(key)) {
                ranges.remove(i).markRemoved();
                removedAny = true;
            }
        }
        List<TrackedRange> installed = new ArrayList<>();
        if (replacements != null) {
            for (Entry entry : replacements) {
                if (entry == null) continue;
                TrackedRange range = new TrackedRange(entry.from(), entry.to(), stickiness, key, entry.payload());
                insertSorted(range);
                installed.add(range);
            }
        }
        if (removedAny || !installed.isEmpty()) onChanged.emit();
        return installed;
    }

    /** What {@link #replaceLane} takes: a span and what the lane's owner wants back with it. */
    public record Entry(int from, int to, @Nullable Object payload) {
        public static Entry of(int from, int to) {
            return new Entry(from, to, null);
        }

        public static Entry of(int from, int to, @Nullable Object payload) {
            return new Entry(from, to, payload);
        }
    }

    /** Drops a whole lane. */
    public void clearLane(String lane) {
        replaceLane(lane, Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, List.of());
    }

    public void clear() {
        if (ranges.isEmpty()) return;
        for (TrackedRange range : ranges) range.markRemoved();
        ranges.clear();
        onChanged.emit();
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    public List<TrackedRange> inLane(String lane) {
        String key = lane == null ? DEFAULT_LANE : lane;
        List<TrackedRange> out = new ArrayList<>();
        for (TrackedRange range : ranges) {
            if (range.lane().equals(key)) out.add(range);
        }
        return out;
    }

    /**
     * Everything overlapping {@code [from, to)}, in start order — the viewport query.
     *
     * <p>Binary search finds where to <em>start</em> looking, and the walk from there is linear because a
     * range starting earlier can still reach in. The scan back is bounded by the widest range in the set,
     * which for diagnostics is a line or two; an interval tree is what removes that bound, and see the class
     * note on why there is not one.</p>
     */
    public List<TrackedRange> overlapping(int from, int to) {
        List<TrackedRange> out = new ArrayList<>();
        if (ranges.isEmpty()) return out;
        int index = firstAtOrAfter(from);
        // Back up over ranges that begin before the window but end inside it.
        while (index > 0 && ranges.get(index - 1).to() >= from) index--;
        for (int i = index; i < ranges.size(); i++) {
            TrackedRange range = ranges.get(i);
            if (range.from() >= to) break;
            if (range.overlaps(from, to) || (range.isEmpty() && range.from() >= from && range.from() <= to)) {
                out.add(range);
            }
        }
        return out;
    }

    /** The index of the first range starting at or after {@code offset}, or {@code size()}. */
    private int firstAtOrAfter(int offset) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ranges.get(mid).from() < offset) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    // ── Tracking ────────────────────────────────────────────────────────────────────────────────

    /**
     * Moves every range through an edit. Called synchronously from the document's change signal.
     *
     * <p><b>Synchronous is the requirement, not an optimisation.</b> Anything that reads a range between the
     * edit landing and the adjustment running reads offsets into a document that no longer exists, and a
     * frame is more than enough time for a paint to do exactly that.</p>
     */
    public void adjust(@Nullable ChangeSet change) {
        if (change == null || change.isEmpty() || ranges.isEmpty()) return;
        for (TrackedRange range : ranges) range.adjust(change);
        resortIfNeeded();
    }

    /** See the class note — different stickiness at one offset is what can reorder two ranges. */
    private void resortIfNeeded() {
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i - 1).from() > ranges.get(i).from()) {
                ranges.sort((a, b) -> Integer.compare(a.from(), b.from()));
                return;
            }
        }
    }

    private void insertSorted(TrackedRange range) {
        int index = firstAtOrAfter(range.from());
        // Past every range with the same start, so insertion order is preserved among equals -- which makes
        // replaceLane hand back its list in the order the caller supplied it.
        while (index < ranges.size() && ranges.get(index).from() <= range.from()) index++;
        ranges.add(index, range);
    }
}
