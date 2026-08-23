package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A two-way diff as <b>both</b> its changed and its unchanged spans.
 *
 * <p>Ported from {@code com.intellij.diff.comparison.iterables.DiffIterable} /
 * {@code FairDiffIterable} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0.
 * <b>Modified:</b> collapsed to one concrete class over eagerly-built lists rather than an iterator
 * hierarchy, since the consumers here walk it once.</p>
 *
 * <h3>Why the unchanged half is the point</h3>
 *
 * <p>A differ naturally answers with what <em>changed</em>, and every consumer that shows a diff wants
 * exactly that. A <b>three-way merge does not</b>: it needs the spans where the base and one side
 * <em>agree</em>, so that it can intersect two such sets and find where all three agree. Everything left
 * over is then a region needing a decision — and because it is a leftover rather than a union of changed
 * spans, <b>it cannot overlap another region</b>.</p>
 *
 * <p>That is the whole reason this type exists instead of a bare list of hunks. Building merge regions by
 * grouping changed hunks is possible and needs an explicit rule about whether an edit abutting a region
 * joins it; get that rule wrong and two regions claim the same base lines, which is not a miscount but a
 * corrupted merge. Intersecting agreement removes the failure mode rather than defending against it.</p>
 *
 * <h3>"Fair"</h3>
 *
 * <p>JetBrains' name for the property this class checks: in an unchanged range the two sides have the same
 * length, because the lines are equal one-for-one. A differ that reports otherwise has produced a pairing
 * that cannot be rendered or merged, so it is rejected here rather than propagated.</p>
 */
public final class DiffIterable {

    private final int length1;
    private final int length2;
    private final List<DiffRange> changed;
    private final List<DiffRange> unchanged;

    private DiffIterable(int length1, int length2, List<DiffRange> changed, List<DiffRange> unchanged) {
        this.length1 = length1;
        this.length2 = length2;
        this.changed = Collections.unmodifiableList(changed);
        this.unchanged = Collections.unmodifiableList(unchanged);
    }

    /**
     * Builds an iterable from the changed spans, deriving the unchanged ones as the gaps between them.
     *
     * @throws IllegalArgumentException if the changed spans are out of order, or if the gaps between them
     *                                  are not equal-length on both sides — the "fair" property above
     */
    public static DiffIterable fromChanged(int length1, int length2, List<DiffRange> changed) {
        List<DiffRange> unchanged = new ArrayList<>();
        int at1 = 0;
        int at2 = 0;
        for (DiffRange range : changed) {
            if (range.start1() < at1 || range.start2() < at2) {
                throw new IllegalArgumentException("changed ranges must be ordered and disjoint: " + range);
            }
            addUnchanged(unchanged, at1, range.start1(), at2, range.start2());
            at1 = range.end1();
            at2 = range.end2();
        }
        addUnchanged(unchanged, at1, length1, at2, length2);
        return new DiffIterable(length1, length2, new ArrayList<>(changed), unchanged);
    }

    /** Builds one from a {@link LineDiff} result over two line lists, comparing exactly. */
    public static DiffIterable of(List<String> before, List<String> after) {
        return of(before, after, ComparisonPolicy.DEFAULT);
    }

    /**
     * Builds one under a {@link ComparisonPolicy}.
     *
     * <p>The diff runs over the policy's <b>comparison keys</b> rather than the lines themselves, which is
     * {@code ByLineRt.convertMode}'s trick: normalising is one pass, and every equality test afterwards is
     * a plain string comparison instead of re-deciding what whitespace means. Keys are index-parallel with
     * the originals, so the ranges that come back address the real text.</p>
     */
    public static DiffIterable of(List<String> before, List<String> after, ComparisonPolicy policy) {
        List<DiffRange> changed = new ArrayList<>();
        for (LineDiff.Hunk hunk : LineDiff.diff(policy.normaliseAll(before), policy.normaliseAll(after))) {
            changed.add(new DiffRange(hunk.fromLine(), hunk.toLine(), hunk.newFromLine(), hunk.newToLine()));
        }
        return fromChanged(before.size(), after.size(), changed);
    }

    private static void addUnchanged(List<DiffRange> into, int start1, int end1, int start2, int end2) {
        if (start1 == end1 && start2 == end2) return;
        if (end1 - start1 != end2 - start2) {
            // NOT AN ASSERTION ABOUT TASTE. A gap between two changed spans is by definition text both
            // sides kept, so it is the same text and therefore the same number of lines. A differ that
            // says otherwise has paired lines that do not correspond, and a merge built on that pairing
            // drops or duplicates lines with nothing to indicate it happened.
            throw new IllegalArgumentException("unchanged range must match on both sides: "
                    + (end1 - start1) + " vs " + (end2 - start2));
        }
        into.add(new DiffRange(start1, end1, start2, end2));
    }

    /** Spans that differ. */
    public List<DiffRange> changed() {
        return changed;
    }

    /** Spans the two texts agree on — equal length on both sides. */
    public List<DiffRange> unchanged() {
        return unchanged;
    }

    public int length1() {
        return length1;
    }

    public int length2() {
        return length2;
    }
}
