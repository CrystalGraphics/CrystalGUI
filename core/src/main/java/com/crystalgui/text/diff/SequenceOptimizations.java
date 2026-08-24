package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * The heuristics that turn a minimal diff into a readable one.
 *
 * <p>Ported from {@code heuristicSequenceOptimizations.ts} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT —
 * {@code optimizeSequenceDiffs}, {@code joinSequenceDiffsByShifting}, {@code shiftSequenceDiffs} and
 * {@code shiftDiffToBetterPosition}. <b>Modified:</b> written over {@link DiffRange} rather than
 * {@code SequenceDiff}/{@code OffsetRange}, and the in-place mutation of the input list is replaced by
 * building a new one.</p>
 *
 * <h3>Why a minimal diff is not the answer</h3>
 *
 * <p>An algorithm optimises edit distance; a reader optimises comprehension, and the two disagree
 * constantly. Upstream's own example:</p>
 *
 * <pre>
 *   import { Baz, Bar } from "foo";
 *   import { Baz, Bar, Foo } from "foo";
 * </pre>
 *
 * <p>The minimal answer is two edits — insert a comma after {@code Bar}, and insert {@code Foo } after a
 * space. Both are correct and the pair is unreadable. The intended answer is one edit: insert
 * {@code , Foo}. Nothing about edit distance prefers it, so a heuristic has to.</p>
 *
 * <h3>The two moves</h3>
 *
 * <p><b>Join by shifting</b> — an insertion or deletion can often slide along the sequence without changing
 * what the diff <em>means</em>, because the text it moves over is the same as the text it moves through.
 * Slide everything as far left as it will go, joining any two that meet; then as far right, joining again.
 * The doubled left pass is upstream's, with a comment saying the second call measurably improves the
 * result.</p>
 *
 * <p><b>Shift to a better position</b> — having found the window a diff may slide within, pick the offset
 * inside it that scores best by {@link Sequence#boundaryScore}. That is what makes an inserted method
 * report as starting at the blank line above it instead of partway through the previous one.</p>
 *
 * <p>Note which equality each pass uses, because it is not the same one. The <b>left</b> pass compares
 * elements ({@link Sequence#elementAt}); the <b>right</b> pass compares strongly
 * ({@link Sequence#stronglyEqual}). Sliding right onto a merely-weakly-equal line would make a
 * whitespace-only difference the match and report the real difference as the change.</p>
 */
public final class SequenceOptimizations {

    /** Upstream's cap, to stop a pathological file making the slide search quadratic. */
    private static final int MAX_SHIFT = 100;

    private SequenceOptimizations() {
    }

    public static List<DiffRange> optimize(Sequence seq1, Sequence seq2, List<DiffRange> diffs) {
        List<DiffRange> result = joinByShifting(seq1, seq2, diffs);
        // TWICE, and upstream says so outright: the second pass measurably improves the result, because
        // joining two diffs can create a new opportunity to join with a third.
        result = joinByShifting(seq1, seq2, result);
        return shiftToBetterPositions(seq1, seq2, result);
    }

    private static List<DiffRange> joinByShifting(Sequence seq1, Sequence seq2, List<DiffRange> diffs) {
        if (diffs.isEmpty()) return diffs;

        // ── Left: slide each diff back as far as it will go, joining when two meet ──
        List<DiffRange> leftward = new ArrayList<>();
        leftward.add(diffs.get(0));

        for (int i = 1; i < diffs.size(); i++) {
            DiffRange previous = leftward.get(leftward.size() - 1);
            DiffRange current = diffs.get(i);

            if (current.isEmpty1() || current.isEmpty2()) {
                int gap = current.start1() - previous.end1();
                int d = 1;
                while (d <= gap
                        && seq1.elementAt(current.start1() - d) == seq1.elementAt(current.end1() - d)
                        && seq2.elementAt(current.start2() - d) == seq2.elementAt(current.end2() - d)) {
                    d++;
                }
                d--;

                if (d == gap) {
                    // It slid the whole way back and met the previous diff: they are one edit.
                    leftward.set(leftward.size() - 1, new DiffRange(
                            previous.start1(), current.end1() - gap,
                            previous.start2(), current.end2() - gap));
                    continue;
                }
                current = current.delta(-d);
            }
            leftward.add(current);
        }

        // ── Right: the same, forwards, and on STRONG equality ──
        List<DiffRange> result = new ArrayList<>();
        for (int i = 0; i < leftward.size() - 1; i++) {
            DiffRange next = leftward.get(i + 1);
            DiffRange current = leftward.get(i);

            if (current.isEmpty1() || current.isEmpty2()) {
                int gap = next.start1() - current.end1();
                int d = 0;
                while (d < gap
                        && seq1.stronglyEqual(current.start1() + d, current.end1() + d)
                        && seq2.stronglyEqual(current.start2() + d, current.end2() + d)) {
                    d++;
                }

                if (d == gap) {
                    leftward.set(i + 1, new DiffRange(
                            current.start1() + gap, next.end1(),
                            current.start2() + gap, next.end2()));
                    continue;
                }
                if (d > 0) current = current.delta(d);
            }
            result.add(current);
        }
        result.add(leftward.get(leftward.size() - 1));
        return result;
    }

    /**
     * Joins two diffs separated by a scrap of unchanged text too small to be worth reading as unchanged.
     *
     * <p>Ported from {@code removeVeryShortMatchingLinesBetweenDiffs}. Four non-whitespace characters is
     * upstream's bound, and only when one of the two diffs either side is itself substantial — a lone
     * {@code }} or {@code });} between two large edits is noise pretending to be common ground, and
     * splitting a change around it makes a person read two changes where there is one.</p>
     *
     * <p>Repeats up to ten times, because joining two diffs can leave a new short gap against a third.</p>
     */
    public static List<DiffRange> joinShortMatchesBetween(LineSequence seq1, List<DiffRange> diffs) {
        List<DiffRange> current = diffs;
        if (current.isEmpty()) return current;

        for (int round = 0; round < 10; round++) {
            boolean joinedAny = false;
            List<DiffRange> result = new ArrayList<>();
            result.add(current.get(0));

            for (int i = 1; i < current.size(); i++) {
                DiffRange diff = current.get(i);
                DiffRange last = result.get(result.size() - 1);

                StringBuilder between = new StringBuilder();
                for (int line = last.end1(); line < diff.start1(); line++) {
                    between.append(seq1.lineAt(line));
                }
                int meaningful = 0;
                for (int c = 0; c < between.length(); c++) {
                    if (!Character.isWhitespace(between.charAt(c))) meaningful++;
                }

                boolean eitherIsSubstantial = last.length1() + last.length2() > 5
                        || diff.length1() + diff.length2() > 5;
                if (meaningful <= 4 && eitherIsSubstantial) {
                    result.set(result.size() - 1, last.join(diff));
                    joinedAny = true;
                } else {
                    result.add(diff);
                }
            }

            current = result;
            if (!joinedAny) break;
        }
        return current;
    }

    /**
     * Grows a change that cuts through the middle of a word out to the whole word.
     *
     * <p>Ported from {@code extendDiffsToEntireWordIfAppropriate}. Without it a character diff reports the
     * <em>letters</em> that differ rather than the token that changed: {@code int} against {@code long}
     * shares an {@code n}, so the honest minimal answer is two fragments — mark {@code "lo"}, keep
     * {@code "n"}, mark {@code "g"} — which on screen is a word with a hole in it and reads as a rendering
     * fault rather than as a renamed type.</p>
     *
     * <p>The <b>two-thirds</b> test is what keeps it from swallowing everything: a word is only extended
     * when less than two thirds of it was equal. A one-character change inside a long identifier stays a
     * one-character change, because there the fragment genuinely is the information.</p>
     */
    public static List<DiffRange> extendToWholeWords(CharSequenceSlice seq1, CharSequenceSlice seq2,
            List<DiffRange> diffs) {
        List<DiffRange> equal = invert(diffs, seq1.length(), seq2.length());
        List<DiffRange> additional = new ArrayList<>();
        int[] lastPoint = {0, 0};

        for (int i = 0; i < equal.size(); i++) {
            DiffRange next = equal.get(i);
            if (next.isEmpty1()) continue;
            scanWord(seq1, seq2, next.start1(), next.start2(), next, equal, i, additional, lastPoint);
            scanWord(seq1, seq2, next.end1() - 1, next.end2() - 1, next, equal, i, additional, lastPoint);
        }

        if (additional.isEmpty()) return diffs;
        List<DiffRange> merged = new ArrayList<>(diffs);
        merged.addAll(additional);
        return mergeOverlapping(merged);
    }

    private static void scanWord(CharSequenceSlice seq1, CharSequenceSlice seq2, int offset1, int offset2,
            DiffRange equalMapping, List<DiffRange> equal, int index, List<DiffRange> additional,
            int[] lastPoint) {
        if (offset1 < lastPoint[0] || offset2 < lastPoint[1]) return;

        int[] w1 = seq1.wordAround(offset1);
        int[] w2 = seq2.wordAround(offset2);
        if (w1 == null || w2 == null) return;

        DiffRange word = new DiffRange(w1[0], w1[1], w2[0], w2[1]);
        int equalChars = overlap(word.start1(), word.end1(), equalMapping.start1(), equalMapping.end1())
                + overlap(word.start2(), word.end2(), equalMapping.start2(), equalMapping.end2());

        // A word may reach past the equal span it was found in and into the next one.
        for (int i = index + 1; i < equal.size(); i++) {
            DiffRange following = equal.get(i);
            boolean intersects = following.start1() < word.end1() && following.end1() > word.start1()
                    || following.start2() < word.end2() && following.end2() > word.start2();
            if (!intersects) break;

            int[] v1 = seq1.wordAround(following.start1());
            int[] v2 = seq2.wordAround(following.start2());
            if (v1 == null || v2 == null) break;
            DiffRange other = new DiffRange(v1[0], v1[1], v2[0], v2[1]);
            equalChars += overlap(other.start1(), other.end1(), following.start1(), following.end1())
                    + overlap(other.start2(), other.end2(), following.start2(), following.end2());
            word = word.join(other);
            if (word.end1() < following.end1()) break;
        }

        int wordChars = word.length1() + word.length2();
        if (equalChars * 3 < wordChars * 2) additional.add(word);

        lastPoint[0] = word.end1();
        lastPoint[1] = word.end2();
    }

    private static int overlap(int fromA, int toA, int fromB, int toB) {
        return Math.max(0, Math.min(toA, toB) - Math.max(fromA, fromB));
    }

    /** The spans BETWEEN the diffs — what both sides agree on. */
    private static List<DiffRange> invert(List<DiffRange> diffs, int length1, int length2) {
        List<DiffRange> equal = new ArrayList<>();
        int at1 = 0;
        int at2 = 0;
        for (DiffRange diff : diffs) {
            if (diff.start1() > at1 || diff.start2() > at2) {
                equal.add(new DiffRange(at1, diff.start1(), at2, diff.start2()));
            }
            at1 = diff.end1();
            at2 = diff.end2();
        }
        if (at1 < length1 || at2 < length2) equal.add(new DiffRange(at1, length1, at2, length2));
        return equal;
    }

    private static List<DiffRange> mergeOverlapping(List<DiffRange> diffs) {
        List<DiffRange> sorted = new ArrayList<>(diffs);
        sorted.sort((a, b) -> a.start1() != b.start1()
                ? Integer.compare(a.start1(), b.start1())
                : Integer.compare(a.start2(), b.start2()));

        List<DiffRange> merged = new ArrayList<>();
        for (DiffRange diff : sorted) {
            if (merged.isEmpty()) {
                merged.add(diff);
                continue;
            }
            DiffRange last = merged.get(merged.size() - 1);
            if (diff.start1() <= last.end1() || diff.start2() <= last.end2()) {
                merged.set(merged.size() - 1, last.join(diff));
            } else {
                merged.add(diff);
            }
        }
        return merged;
    }

    private static List<DiffRange> shiftToBetterPositions(Sequence seq1, Sequence seq2,
            List<DiffRange> diffs) {
        List<DiffRange> result = new ArrayList<>(diffs);
        for (int i = 0; i < result.size(); i++) {
            DiffRange previous = i > 0 ? result.get(i - 1) : null;
            DiffRange diff = result.get(i);
            DiffRange next = i + 1 < result.size() ? result.get(i + 1) : null;

            // The window this diff may slide within without colliding with its neighbours.
            int valid1From = previous != null ? previous.end1() + 1 : 0;
            int valid1To = next != null ? next.start1() - 1 : seq1.length();
            int valid2From = previous != null ? previous.end2() + 1 : 0;
            int valid2To = next != null ? next.start2() - 1 : seq2.length();

            if (diff.isEmpty1()) {
                result.set(i, shiftOne(diff, seq1, seq2, valid1From, valid1To, valid2From, valid2To));
            } else if (diff.isEmpty2()) {
                // Only pure insertions and pure deletions can slide; a replacement is anchored on both
                // sides. The deletion case is the insertion case with the sequences exchanged.
                result.set(i, shiftOne(diff.swap(), seq2, seq1, valid2From, valid2To, valid1From, valid1To)
                        .swap());
            }
        }
        return result;
    }

    private static DiffRange shiftOne(DiffRange diff, Sequence seq1, Sequence seq2,
            int valid1From, int valid1To, int valid2From, int valid2To) {
        int before = 1;
        while (diff.start1() - before >= valid1From
                && diff.start2() - before >= valid2From
                && seq2.stronglyEqual(diff.start2() - before, diff.end2() - before)
                && before < MAX_SHIFT) {
            before++;
        }
        before--;

        int after = 0;
        while (diff.start1() + after < valid1To
                && diff.end2() + after < valid2To
                && seq2.stronglyEqual(diff.start2() + after, diff.end2() + after)
                && after < MAX_SHIFT) {
            after++;
        }

        if (before == 0 && after == 0) return diff;

        int bestDelta = 0;
        int bestScore = -1;
        for (int delta = -before; delta <= after; delta++) {
            int score = seq1.boundaryScore(diff.start1() + delta)
                    + seq2.boundaryScore(diff.start2() + delta)
                    + seq2.boundaryScore(diff.end2() + delta);
            if (score > bestScore) {
                bestScore = score;
                bestDelta = delta;
            }
        }
        return diff.delta(bestDelta);
    }
}
