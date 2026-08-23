package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An exact longest-common-subsequence diff, quadratic in time and space.
 *
 * <p>Ported from {@code DynamicProgrammingDiffing} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../algorithms/dynamicProgrammingDiffing.ts}), MIT. <b>Modified:</b> the three parallel 2-D
 * arrays are flat {@code int[]}s, and the direction constants are named.</p>
 *
 * <h3>Why keep a quadratic algorithm when Myers exists</h3>
 *
 * <p>Because it is <b>better</b>, not merely different, and on small inputs the cost does not matter. Myers
 * finds <em>a</em> shortest edit script; this finds the one that also maximises consecutive diagonal runs,
 * which is the difference between a diff that jumps between matched lines and one that keeps blocks
 * together. Upstream runs it below a size threshold and Myers above, so the common case — a file someone is
 * actually reading — gets the better answer.</p>
 *
 * <p>The "prefer consecutive diagonals" term is the whole trick: a match immediately after another match
 * scores the length of the run it extends, so a run of five matched lines outranks five matches scattered
 * among changes even though both are five matches.</p>
 */
public final class DynamicProgrammingDiff {

    private static final int FROM_LEFT = 1;
    private static final int FROM_ABOVE = 2;
    private static final int DIAGONAL = 3;

    /**
     * How much a matched pair is worth.
     *
     * <p>Not always 1. Upstream scores an <em>exactly</em> equal line by its length
     * ({@code 1 + log(1 + length)}, and {@code 0.1} for a blank one) and a merely-trimmed-equal line at
     * {@code 0.99}. So a long line matching exactly outranks several short ones, a blank line is nearly
     * worthless as an anchor, and an exact match always beats a whitespace-only one — all three of which
     * are readability judgements that edit distance cannot express.</p>
     */
    @FunctionalInterface
    public interface EqualityScore {
        double scoreOf(int offset1, int offset2);
    }

    private DynamicProgrammingDiff() {
    }

    public static DiffAlgorithmResult compute(Sequence seq1, Sequence seq2) {
        return compute(seq1, seq2, DiffTimeout.INFINITE);
    }

    public static DiffAlgorithmResult compute(Sequence seq1, Sequence seq2, DiffTimeout timeout) {
        return compute(seq1, seq2, timeout, null);
    }

    public static DiffAlgorithmResult compute(Sequence seq1, Sequence seq2, DiffTimeout timeout,
            EqualityScore score) {
        int width = seq1.length();
        int height = seq2.length();
        if (width == 0 || height == 0) return DiffAlgorithmResult.trivial(seq1, seq2, false);

        double[] lcs = new double[width * height];
        int[] directions = new int[width * height];
        int[] runLengths = new int[width * height];

        for (int s1 = 0; s1 < width; s1++) {
            for (int s2 = 0; s2 < height; s2++) {
                if (!timeout.isValid()) return DiffAlgorithmResult.trivial(seq1, seq2, true);

                int at = s1 * height + s2;
                double horizontal = s1 == 0 ? 0 : lcs[(s1 - 1) * height + s2];
                double vertical = s2 == 0 ? 0 : lcs[s1 * height + (s2 - 1)];

                double extended;
                if (seq1.elementAt(s1) == seq2.elementAt(s2)) {
                    extended = (s1 == 0 || s2 == 0) ? 0 : lcs[(s1 - 1) * height + (s2 - 1)];
                    if (s1 > 0 && s2 > 0 && directions[(s1 - 1) * height + (s2 - 1)] == DIAGONAL) {
                        // PREFER CONSECUTIVE DIAGONALS: extending a run is worth the run's whole length,
                        // so five lines matched in a row beat five matched apart. Without this the answer
                        // is still a longest common subsequence and is scattered across the file.
                        extended += runLengths[(s1 - 1) * height + (s2 - 1)];
                    }
                    extended += score == null ? 1 : score.scoreOf(s1, s2);
                } else {
                    extended = -1;
                }

                double best = Math.max(Math.max(horizontal, vertical), extended);

                if (best == extended) {
                    int previousRun = (s1 > 0 && s2 > 0) ? runLengths[(s1 - 1) * height + (s2 - 1)] : 0;
                    runLengths[at] = previousRun + 1;
                    directions[at] = DIAGONAL;
                } else if (best == horizontal) {
                    directions[at] = FROM_LEFT;
                } else {
                    directions[at] = FROM_ABOVE;
                }

                lcs[at] = best;
            }
        }

        return new DiffAlgorithmResult(backtrack(directions, width, height), false);
    }

    private static List<DiffRange> backtrack(int[] directions, int width, int height) {
        List<DiffRange> result = new ArrayList<>();
        int[] lastAligned = {width, height};

        int s1 = width - 1;
        int s2 = height - 1;
        while (s1 >= 0 && s2 >= 0) {
            int direction = directions[s1 * height + s2];
            if (direction == DIAGONAL) {
                reportAligned(result, lastAligned, s1, s2);
                s1--;
                s2--;
            } else if (direction == FROM_LEFT) {
                s1--;
            } else {
                s2--;
            }
        }
        reportAligned(result, lastAligned, -1, -1);

        Collections.reverse(result);
        return result;
    }

    /** Emits the gap between this aligned pair and the last one seen, walking backwards. */
    private static void reportAligned(List<DiffRange> result, int[] lastAligned, int s1, int s2) {
        if (s1 + 1 != lastAligned[0] || s2 + 1 != lastAligned[1]) {
            result.add(new DiffRange(s1 + 1, lastAligned[0], s2 + 1, lastAligned[1]));
        }
        lastAligned[0] = s1;
        lastAligned[1] = s2;
    }
}
