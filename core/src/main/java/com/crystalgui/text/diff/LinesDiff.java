package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * The line differ: picks an algorithm by size, then applies the readability heuristics.
 *
 * <p>Ported from {@code DefaultLinesDiffComputer} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../defaultLinesDiffComputer/defaultLinesDiffComputer.ts}), MIT. <b>Modified:</b> the
 * character-level refinement, the whitespace re-scan and moved-block detection are separate stages of this
 * codebase's port and are not here yet; a {@link ComparisonPolicy} chooses the hashing, where upstream
 * always trims.</p>
 *
 * <h3>Two algorithms, and the threshold is not a compromise</h3>
 *
 * <p>Below {@value #EXACT_LIMIT} lines total the exact dynamic-programming diff runs; above it, Myers. That
 * is not "the good one when we can afford it" so much as an admission that the two answer different
 * questions: Myers finds <em>a</em> shortest edit script, while the DP version maximises consecutive
 * diagonal runs and so keeps blocks together. The file a person is actually reading is nearly always under
 * the threshold, so the better answer is the common one.</p>
 *
 * <h3>The equality score is where the readability judgements live</h3>
 *
 * <p>The DP pass does not score every match as 1. An <b>exactly</b> equal line is worth
 * {@code 1 + log(1 + length)} — so a long line matching exactly outranks several short ones — a
 * <b>blank</b> line is worth {@code 0.1}, because a blank line anchors nothing, and a line equal only after
 * normalisation is worth {@code 0.99}, so an exact match always beats a whitespace-only one. Those three
 * numbers encode judgements that edit distance cannot express, and they are upstream's.</p>
 */
public final class LinesDiff {

    /** Combined line count below which the exact algorithm is used. Upstream's number. */
    public static final int EXACT_LIMIT = 1700;

    /** The same idea for the character pass, where the slices are much smaller. Upstream's number. */
    public static final int CHAR_EXACT_LIMIT = 500;

    private LinesDiff() {
    }

    public static DiffAlgorithmResult compute(List<String> lines1, List<String> lines2) {
        return compute(lines1, lines2, ComparisonPolicy.DEFAULT, DiffTimeout.INFINITE);
    }

    public static DiffAlgorithmResult compute(List<String> lines1, List<String> lines2,
            ComparisonPolicy policy, DiffTimeout timeout) {
        // THE ROUGH PASS IS ALWAYS WHITESPACE-BLIND, whatever the caller asked for -- upstream hashes the
        // trimmed line unconditionally, and IntelliJ runs its first pass under IGNORE_WHITESPACES for the
        // same reason. Reindentation is the commonest edit in real code, and anchoring on exact text
        // through one leaves the algorithm nothing to match, so it anchors on braces and blanks instead
        // and produces a diff that is minimal and unreadable. Exactness is restored afterwards.
        ComparisonPolicy rough = policy == ComparisonPolicy.IGNORE_WHITESPACES
                ? ComparisonPolicy.IGNORE_WHITESPACES
                : ComparisonPolicy.TRIM_WHITESPACES;
        LineSequence[] pair = LineSequence.pair(lines1, lines2, rough);
        LineSequence seq1 = pair[0];
        LineSequence seq2 = pair[1];

        DiffAlgorithmResult raw;
        if (seq1.length() + seq2.length() < EXACT_LIMIT) {
            raw = DynamicProgrammingDiff.compute(seq1, seq2, timeout, (offset1, offset2) -> {
                String line1 = lines1.get(offset1);
                String line2 = lines2.get(offset2);
                if (!line1.equals(line2)) return 0.99;
                return line2.isEmpty() ? 0.1 : 1 + Math.log(1 + line2.length());
            });
        } else {
            raw = MyersDiff.compute(seq1, seq2, timeout);
        }

        List<DiffRange> diffs = SequenceOptimizations.optimize(seq1, seq2, raw.diffs());
        diffs = SequenceOptimizations.joinShortMatchesBetween(seq1, diffs);
        diffs = restoreExactness(lines1, lines2, diffs, policy);
        return new DiffAlgorithmResult(diffs, raw.hitTimeout());
    }

    /**
     * The same, plus the character-level changes inside each block.
     *
     * <p>Ported from {@code DefaultLinesDiffComputer.refineDiff}. Each changed block is re-run through the
     * <em>same</em> algorithms over its characters — which is why a view's word marks can never disagree
     * with its line bands, and why this is not a second feature with a second implementation to keep in
     * step.</p>
     */
    public static List<DetailedDiff> computeDetailed(List<String> lines1, List<String> lines2,
            ComparisonPolicy policy, DiffTimeout timeout) {
        List<DetailedDiff> result = new ArrayList<>();
        for (DiffRange block : compute(lines1, lines2, policy, timeout).diffs()) {
            result.add(new DetailedDiff(block, refine(lines1, lines2, block, timeout)));
        }
        return result;
    }

    public static List<DetailedDiff> computeDetailed(List<String> lines1, List<String> lines2) {
        return computeDetailed(lines1, lines2, ComparisonPolicy.DEFAULT, DiffTimeout.INFINITE);
    }

    /** The character diff within one changed block, in the two texts' own (line, column) coordinates. */
    public static List<InnerRange> refine(List<String> lines1, List<String> lines2, DiffRange block,
            DiffTimeout timeout) {
        // A PURE INSERTION OR DELETION HAS NOTHING FINER TO SAY. There is no counterpart text to compare
        // against, so any inner range would be the whole block restated -- which a view would then draw as
        // a word mark over every character of an added line, on top of the band already there.
        if (block.isEmpty1() || block.isEmpty2()) return List.of();

        CharSequenceSlice slice1 = CharSequenceSlice.of(lines1, block.start1(), block.end1());
        CharSequenceSlice slice2 = CharSequenceSlice.of(lines2, block.start2(), block.end2());

        DiffAlgorithmResult raw = slice1.length() + slice2.length() < CHAR_EXACT_LIMIT
                ? DynamicProgrammingDiff.compute(slice1, slice2, timeout)
                : MyersDiff.compute(slice1, slice2, timeout);

        List<DiffRange> charDiffs = SequenceOptimizations.optimize(slice1, slice2, raw.diffs());
        // WHOLE WORDS, or the marks report the letters that differ rather than the token that changed.
        charDiffs = SequenceOptimizations.extendToWholeWords(slice1, slice2, charDiffs);

        List<InnerRange> inner = new ArrayList<>();
        for (DiffRange d : charDiffs) {
            inner.add(new InnerRange(
                    slice1.lineOf(d.start1()), slice1.columnOf(d.start1()),
                    slice1.lineOf(d.end1()), slice1.columnOf(d.end1()),
                    slice2.lineOf(d.start2()), slice2.columnOf(d.start2()),
                    slice2.lineOf(d.end2()), slice2.columnOf(d.end2())));
        }
        return inner;
    }

    /**
     * Splits the agreed spans wherever the lines are not actually equal under the caller's policy.
     *
     * <p><b>Without this the rough pass is a lie.</b> It hashed the trimmed line, so under
     * {@link ComparisonPolicy#DEFAULT} a reindented line comes back inside an <em>unchanged</em> span — and
     * a merge built on that does not merely mis-draw the change, it concludes nobody touched those lines
     * and silently discards a reindent that competed with a real edit.</p>
     *
     * <p>This is {@code correctChangesSecondStep}'s job in IntelliJ and {@code scanForWhitespaceChanges}'s
     * in VS Code, done the cheap way available before character-level refinement exists: <b>split, never
     * re-pair</b>. The heuristics have already chosen the alignment; all that is missing is admitting that
     * some aligned pairs differ. Splitting cannot lose a match, which is exactly the failure the fuller
     * re-alignment version was measured to have when ported without its prerequisites.</p>
     */
    private static List<DiffRange> restoreExactness(List<String> lines1, List<String> lines2,
            List<DiffRange> diffs, ComparisonPolicy policy) {
        if (policy == ComparisonPolicy.TRIM_WHITESPACES || policy == ComparisonPolicy.IGNORE_WHITESPACES) {
            return diffs;
        }

        List<DiffRange> result = new ArrayList<>();
        int at1 = 0;
        int at2 = 0;
        for (DiffRange changed : diffs) {
            splitAgreed(lines1, lines2, policy, at1, changed.start1(), at2, result);
            result.add(changed);
            at1 = changed.end1();
            at2 = changed.end2();
        }
        splitAgreed(lines1, lines2, policy, at1, lines1.size(), at2, result);
        return result;
    }

    /** Emits, as changed, each run of unequal lines inside one agreed span. */
    private static void splitAgreed(List<String> lines1, List<String> lines2, ComparisonPolicy policy,
            int from1, int to1, int from2, List<DiffRange> into) {
        int runStart = -1;
        for (int i = from1; i < to1; i++) {
            boolean equal = policy.linesEqual(lines1.get(i), lines2.get(from2 + (i - from1)));
            if (!equal && runStart < 0) {
                runStart = i;
            } else if (equal && runStart >= 0) {
                into.add(new DiffRange(runStart, i, from2 + (runStart - from1), from2 + (i - from1)));
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            into.add(new DiffRange(runStart, to1, from2 + (runStart - from1), from2 + (to1 - from1)));
        }
    }
}
