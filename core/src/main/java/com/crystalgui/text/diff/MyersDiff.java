package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Myers' O(ND) diff.
 *
 * <p>Ported from {@code MyersDiffAlgorithm} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../algorithms/myersDiffAlgorithm.ts}), MIT. <b>Modified:</b> the two negative-index array
 * helpers are written as a single growable pair-of-arrays type rather than two classes, and the timeout is
 * an interface here rather than a class hierarchy.</p>
 *
 * <h3>The shape of it</h3>
 *
 * <p>Walk outward in {@code d}, the number of non-diagonal steps taken. {@code V[k]} holds the furthest
 * {@code x} reachable on diagonal {@code k} using exactly {@code d} of them; a "snake" is the run of equal
 * elements you get for free after each step. The first {@code d} that reaches the far corner is the length
 * of the shortest edit script, and the path back through the snakes is the diff.</p>
 *
 * <p><b>Quadratic in space</b>, which is why the caller picks it only above the size where the exact
 * dynamic-programming version is affordable, and why the timeout exists at all.</p>
 *
 * <p>Two departures from the published algorithm, both from upstream and both worth keeping:</p>
 *
 * <ul>
 *   <li>The paper iterates {@code k} from {@code -d} to {@code d}. Diagonals that have already run off the
 *       end of either sequence cannot influence the result, so the bounds are clamped to the sequence
 *       lengths — this is pure saved work, not an approximation.</li>
 *   <li>A snake of length zero contributes no path node; the previous one is carried forward instead. That
 *       keeps the reconstructed path to actual runs of equal text.</li>
 * </ul>
 */
public final class MyersDiff {

    private MyersDiff() {
    }

    public static DiffAlgorithmResult compute(Sequence seq1, Sequence seq2) {
        return compute(seq1, seq2, DiffTimeout.INFINITE);
    }

    public static DiffAlgorithmResult compute(Sequence seqX, Sequence seqY, DiffTimeout timeout) {
        // Common enough that the early return is a real speed-up, not a guard.
        if (seqX.length() == 0 || seqY.length() == 0) {
            return DiffAlgorithmResult.trivial(seqX, seqY, false);
        }

        IntsByDiagonal v = new IntsByDiagonal();
        v.set(0, xAfterSnake(seqX, seqY, 0, 0));

        PathsByDiagonal paths = new PathsByDiagonal();
        paths.set(0, v.get(0) == 0 ? null : new SnakePath(null, 0, 0, v.get(0)));

        int d = 0;
        int k = 0;
        boolean reachedEnd = false;

        while (!reachedEnd) {
            d++;
            if (!timeout.isValid()) return DiffAlgorithmResult.trivial(seqX, seqY, true);

            int lowerBound = -Math.min(d, seqY.length() + (d % 2));
            int upperBound = Math.min(d, seqX.length() + (d % 2));

            for (k = lowerBound; k <= upperBound; k += 2) {
                // The furthest x reachable on this diagonal, arriving either from above (a vertical step,
                // i.e. an insertion) or from the left (a horizontal step, i.e. a deletion). -1 marks the
                // direction that does not exist at the edge of the band.
                int fromTop = k == upperBound ? -1 : v.get(k + 1);
                int fromLeft = k == lowerBound ? -1 : v.get(k - 1) + 1;

                int x = Math.min(Math.max(fromTop, fromLeft), seqX.length());
                int y = x - k;
                if (x > seqX.length() || y > seqY.length()) continue;

                int newMaxX = xAfterSnake(seqX, seqY, x, y);
                v.set(k, newMaxX);

                SnakePath previous = x == fromTop ? paths.get(k + 1) : paths.get(k - 1);
                paths.set(k, newMaxX != x ? new SnakePath(previous, x, y, newMaxX - x) : previous);

                if (v.get(k) == seqX.length() && v.get(k) - k == seqY.length()) {
                    reachedEnd = true;
                    break;
                }
            }
        }

        return new DiffAlgorithmResult(reconstruct(paths.get(k), seqX.length(), seqY.length()), false);
    }

    /** Walks the path back, emitting the gaps between snakes as the differing spans. */
    private static List<DiffRange> reconstruct(SnakePath from, int length1, int length2) {
        List<DiffRange> result = new ArrayList<>();
        SnakePath path = from;
        int lastAligned1 = length1;
        int lastAligned2 = length2;

        while (true) {
            int endX = path != null ? path.x + path.length : 0;
            int endY = path != null ? path.y + path.length : 0;
            if (endX != lastAligned1 || endY != lastAligned2) {
                result.add(new DiffRange(endX, lastAligned1, endY, lastAligned2));
            }
            if (path == null) break;
            lastAligned1 = path.x;
            lastAligned2 = path.y;
            path = path.previous;
        }

        Collections.reverse(result);
        return result;
    }

    private static int xAfterSnake(Sequence seqX, Sequence seqY, int x, int y) {
        while (x < seqX.length() && y < seqY.length() && seqX.elementAt(x) == seqY.elementAt(y)) {
            x++;
            y++;
        }
        return x;
    }

    private record SnakePath(SnakePath previous, int x, int y, int length) {
    }

    /**
     * An int array indexed by diagonal, so by an index that may be negative.
     *
     * <p>Two arrays rather than one with an offset, because the band grows outward in both directions and
     * the negative half is often the smaller one — a single centred array would allocate for the worst case
     * on both sides at once.</p>
     */
    private static final class IntsByDiagonal {
        private int[] positive = new int[16];
        private int[] negative = new int[16];

        int get(int index) {
            if (index < 0) {
                int i = -index - 1;
                return i < negative.length ? negative[i] : 0;
            }
            return index < positive.length ? positive[index] : 0;
        }

        void set(int index, int value) {
            if (index < 0) {
                int i = -index - 1;
                if (i >= negative.length) negative = grow(negative, i);
                negative[i] = value;
            } else {
                if (index >= positive.length) positive = grow(positive, index);
                positive[index] = value;
            }
        }

        private static int[] grow(int[] array, int needed) {
            int[] bigger = new int[Math.max(needed + 1, array.length * 2)];
            System.arraycopy(array, 0, bigger, 0, array.length);
            return bigger;
        }
    }

    /** The same, for path nodes. @see IntsByDiagonal */
    private static final class PathsByDiagonal {
        private SnakePath[] positive = new SnakePath[16];
        private SnakePath[] negative = new SnakePath[16];

        SnakePath get(int index) {
            if (index < 0) {
                int i = -index - 1;
                return i < negative.length ? negative[i] : null;
            }
            return index < positive.length ? positive[index] : null;
        }

        void set(int index, SnakePath value) {
            if (index < 0) {
                int i = -index - 1;
                if (i >= negative.length) negative = grow(negative, i);
                negative[i] = value;
            } else {
                if (index >= positive.length) positive = grow(positive, index);
                positive[index] = value;
            }
        }

        private static SnakePath[] grow(SnakePath[] array, int needed) {
            SnakePath[] bigger = new SnakePath[Math.max(needed + 1, array.length * 2)];
            System.arraycopy(array, 0, bigger, 0, array.length);
            return bigger;
        }
    }
}
