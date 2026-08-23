package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the three-way merge regions by <b>intersecting agreement</b>.
 *
 * <p>Ported from {@code ComparisonMergeUtil.FairMergeBuilder} and its {@code ChangeBuilder} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>
 * ({@code platform/util/diff/.../comparison/ComparisonMergeUtil.kt}), Apache 2.0. <b>Modified:</b> the
 * peekable-iterator walk is written as two indices, {@code Side} is replaced by a boolean, and the
 * ignored-changes builder ({@code IgnoringChangeBuilder}, which serves JetBrains' "keep ignored changes"
 * policy) is not ported — it belongs with {@code ComparisonPolicy}, which lands in the same stage.</p>
 *
 * <h3>The idea, which is the whole reason to port this</h3>
 *
 * <p>Given two diffs taken <em>against the base</em> — base↔mine and base↔theirs — the spans where all
 * three texts agree are exactly the <b>intersection of the two diffs' unchanged spans</b>, measured in base
 * coordinates. Walk both lists together, emit each intersection as "equal", and everything left between
 * them is a region somebody changed.</p>
 *
 * <p><b>Regions therefore cannot overlap.</b> They are the gaps between agreed spans, and gaps between
 * ordered disjoint spans are themselves ordered and disjoint. The alternative — grouping the two diffs'
 * <em>changed</em> hunks — needs an explicit rule for whether an edit abutting a region joins it, and an
 * insertion is a zero-width range in base coordinates, so the rule is easy to get wrong by one. The
 * consequence is not a miscount: two regions claiming the same base lines cannot be assembled into an
 * output, so the merged text silently contains something twice or loses it.</p>
 */
public final class MergeRanges {

    private MergeRanges() {
    }

    /**
     * @param mineDiff   base → mine, so its side 1 is base and its side 2 is mine
     * @param theirsDiff base → theirs, likewise
     */
    public static List<MergeRange> build(DiffIterable mineDiff, DiffIterable theirsDiff) {
        if (mineDiff.length1() != theirsDiff.length1()) {
            throw new IllegalArgumentException("both diffs must be against the same base: "
                    + mineDiff.length1() + " vs " + theirsDiff.length1());
        }

        Builder builder = new Builder();
        List<DiffRange> agreedWithMine = mineDiff.unchanged();
        List<DiffRange> agreedWithTheirs = theirsDiff.unchanged();

        int i = 0;
        int j = 0;
        while (i < agreedWithMine.size() && j < agreedWithTheirs.size()) {
            if (advanceMine(builder, agreedWithMine.get(i), agreedWithTheirs.get(j))) i++;
            else j++;
        }

        return builder.finish(mineDiff.length2(), mineDiff.length1(), theirsDiff.length2());
    }

    /**
     * Emits the intersection of one agreed span from each side, and says which to advance.
     *
     * @return true to advance the mine-side span, false for the theirs-side one
     */
    private static boolean advanceMine(Builder builder, DiffRange mine, DiffRange theirs) {
        int startBase1 = mine.start1();
        int endBase1 = mine.end1();
        int startBase2 = theirs.start1();
        int endBase2 = theirs.end1();

        // Disjoint: nothing to emit, advance whichever is behind.
        if (endBase1 <= startBase2) return true;
        if (endBase2 <= startBase1) return false;

        int startBase = Math.max(startBase1, startBase2);
        int endBase = Math.min(endBase1, endBase2);
        int count = endBase - startBase;

        // Each side's own coordinate for the intersection, found by carrying the base-side offset across.
        // Legal only because an unchanged span is equal-length on both sides -- DiffIterable enforces it.
        int startMine = mine.start2() + (startBase - startBase1);
        int startTheirs = theirs.start2() + (startBase - startBase2);

        builder.markEqual(startMine, startBase, startTheirs,
                startMine + count, endBase, startTheirs + count);

        return endBase1 <= endBase2;
    }

    /**
     * Turns a sequence of agreed spans into the changed regions between them.
     *
     * <p>Ported from {@code ComparisonMergeUtil.ChangeBuilder}. It never sees a changed region directly:
     * it is told where agreement starts and ends, and everything it was not told about becomes a region.</p>
     */
    private static final class Builder {

        private final List<MergeRange> ranges = new ArrayList<>();
        private int atMine;
        private int atBase;
        private int atTheirs;

        void markEqual(int startMine, int startBase, int startTheirs,
                int endMine, int endBase, int endTheirs) {
            if (atMine > startMine || atBase > startBase || atTheirs > startTheirs) {
                throw new IllegalStateException("agreed spans must arrive in order");
            }
            if (startMine > endMine || startBase > endBase || startTheirs > endTheirs) {
                throw new IllegalStateException("an agreed span cannot end before it starts");
            }
            addRange(atMine, startMine, atBase, startBase, atTheirs, startTheirs);
            atMine = endMine;
            atBase = endBase;
            atTheirs = endTheirs;
        }

        List<MergeRange> finish(int lengthMine, int lengthBase, int lengthTheirs) {
            if (atMine > lengthMine || atBase > lengthBase || atTheirs > lengthTheirs) {
                throw new IllegalStateException("agreement ran past the end of a text");
            }
            // THE TAIL IS A REGION TOO, and it is the one a hand-written loop forgets: an edit at the very
            // end of a file is followed by no agreed span to trigger it.
            addRange(atMine, lengthMine, atBase, lengthBase, atTheirs, lengthTheirs);
            return ranges;
        }

        private void addRange(int startMine, int endMine, int startBase, int endBase,
                int startTheirs, int endTheirs) {
            if (startMine == endMine && startBase == endBase && startTheirs == endTheirs) return;
            ranges.add(new MergeRange(startMine, endMine, startBase, endBase, startTheirs, endTheirs));
        }
    }
}
