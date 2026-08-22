package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A line-level diff between two texts — Phase 6.6. <b>Histogram, not Myers.</b>
 *
 * <h3>Why histogram</h3>
 *
 * <p>Git ships four algorithms and they split into two families. Myers and minimal optimise for the
 * <b>fewest changed lines</b>; patience and histogram anchor on lines that appear <b>exactly once</b>,
 * which keeps moved and reordered blocks intact instead of scrambling them into the smallest possible
 * edit. Histogram is patience with low-occurrence elements handled, and it is faster than patience,
 * comparable to Myers, and more readable than either — an empirical study across Git repositories found
 * it describes code changes more effectively than Myers.</p>
 *
 * <p><b>For something whose entire job is showing a person what changed, "fewest lines" is the wrong
 * objective.</b> The classic failure is a moved function: Myers happily matches the closing brace of one
 * against the closing brace of another and produces a diff that is minimal and unreadable. Anchoring on
 * unique lines is what stops that.</p>
 *
 * <h3>The algorithm</h3>
 *
 * <p>Recursive, and each step does the same three things:</p>
 *
 * <ol>
 *   <li><b>Trim</b> the common prefix and suffix. Cheap, and on a real edit it removes nearly everything.</li>
 *   <li><b>Find the best anchor</b> — the longest common region whose lines are as rare as possible in the
 *       left text. A line occurring once is an ideal anchor; one occurring fifty times (a lone brace, a
 *       blank) is a terrible one, and the count is exactly how "terrible" is measured. This is histogram's
 *       whole contribution over patience, which only ever accepts a count of one and gives up otherwise.</li>
 *   <li><b>Recurse</b> either side of it.</li>
 * </ol>
 *
 * <p>With no anchor at all — a region where every line is common and none is rare — it falls back to
 * "replace the whole region", which is both correct and what a reader wants there anyway.</p>
 */
public final class LineDiff {

    /**
     * A line occurring more often than this is never chosen as an anchor.
     *
     * <p>Git's own limit, and the number is doing real work: without a ceiling, a file of ten thousand
     * closing braces makes the occurrence scan quadratic. Above it the region is treated as unanchorable
     * and replaced wholesale, which is the readable answer for a region with no landmarks in it.</p>
     */
    private static final int MAX_OCCURRENCES = 64;

    private LineDiff() {
    }

    /** One run of lines that differ. Offsets are into the ORIGINAL text. @see #diff */
    public record Hunk(int fromLine, int toLine, int newFromLine, int newToLine) {

        public boolean isInsertion() {
            return fromLine == toLine;
        }

        public boolean isDeletion() {
            return newFromLine == newToLine;
        }
    }

    /**
     * The lines that differ, in order.
     *
     * <p>Line ranges are half-open, so an insertion at line 3 is {@code [3,3)} on the left and
     * {@code [3,5)} on the right — which is what lets a viewer put an insertion <em>between</em> two
     * lines rather than having to pick one of them.</p>
     */
    public static List<Hunk> diff(List<String> before, List<String> after) {
        List<Hunk> hunks = new ArrayList<>();
        walk(before, after, 0, before.size(), 0, after.size(), hunks);
        return hunks;
    }

    /** Convenience over whole texts, split on line boundaries. */
    public static List<Hunk> diff(String before, String after) {
        return diff(lines(before), lines(after));
    }

    /**
     * Splits without swallowing a trailing newline's empty line.
     *
     * <p>{@code "a\nb\n"} is two lines, not three: the final newline terminates the last line rather than
     * beginning another. Getting this wrong makes every file ending in a newline report a phantom change
     * on its last line, which is most files.</p>
     */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            out.add(text.substring(start, i));
            start = i + 1;
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    // ── The recursion ───────────────────────────────────────────────────────────────────────────

    private static void walk(List<String> before, List<String> after,
                             int leftFrom, int leftTo, int rightFrom, int rightTo, List<Hunk> hunks) {
        // 1. Common prefix and suffix. On a real edit this removes nearly the whole file.
        while (leftFrom < leftTo && rightFrom < rightTo
                && before.get(leftFrom).equals(after.get(rightFrom))) {
            leftFrom++;
            rightFrom++;
        }
        while (leftTo > leftFrom && rightTo > rightFrom
                && before.get(leftTo - 1).equals(after.get(rightTo - 1))) {
            leftTo--;
            rightTo--;
        }

        if (leftFrom == leftTo && rightFrom == rightTo) return;

        // A pure insertion or a pure deletion has nothing to anchor on and needs nothing.
        if (leftFrom == leftTo || rightFrom == rightTo) {
            hunks.add(new Hunk(leftFrom, leftTo, rightFrom, rightTo));
            return;
        }

        // 2. The rarest common region.
        Anchor anchor = findAnchor(before, after, leftFrom, leftTo, rightFrom, rightTo);
        if (anchor == null) {
            // Nothing to hang the region on: replace it whole, which is also what reads best.
            hunks.add(new Hunk(leftFrom, leftTo, rightFrom, rightTo));
            return;
        }

        // 3. Either side of it. The anchor itself is common by construction and never a hunk.
        walk(before, after, leftFrom, anchor.leftStart, rightFrom, anchor.rightStart, hunks);
        walk(before, after, anchor.leftStart + anchor.length, leftTo,
                anchor.rightStart + anchor.length, rightTo, hunks);
    }

    private record Anchor(int leftStart, int rightStart, int length) {
    }

    /**
     * The longest common run whose <b>rarest</b> line is as rare as possible.
     *
     * <p>Rarity beats length, and that ordering is the point: a two-line run of unique lines is a far
     * better landmark than a twenty-line run of blanks and braces, because the unique run can only mean
     * one thing. Length breaks ties.</p>
     */
    private static Anchor findAnchor(List<String> before, List<String> after,
                                     int leftFrom, int leftTo, int rightFrom, int rightTo) {
        Map<String, List<Integer>> occurrences = new HashMap<>();
        for (int i = leftFrom; i < leftTo; i++) {
            List<Integer> at = occurrences.computeIfAbsent(before.get(i), ignored -> new ArrayList<>());
            // Capped rather than unbounded: a line past the ceiling can never be chosen, so recording
            // more of them buys nothing and makes the scan quadratic on a file of closing braces.
            if (at.size() <= MAX_OCCURRENCES) at.add(i);
        }

        Anchor best = null;
        int bestCount = Integer.MAX_VALUE;

        for (int right = rightFrom; right < rightTo; right++) {
            List<Integer> candidates = occurrences.get(after.get(right));
            if (candidates == null || candidates.size() > MAX_OCCURRENCES) continue;

            for (int left : candidates) {
                if (left < leftFrom || left >= leftTo) continue;

                // Grow the run in both directions from this pairing.
                int start = 0;
                while (left - start - 1 >= leftFrom && right - start - 1 >= rightFrom
                        && before.get(left - start - 1).equals(after.get(right - start - 1))) {
                    start++;
                }
                int end = 1;
                while (left + end < leftTo && right + end < rightTo
                        && before.get(left + end).equals(after.get(right + end))) {
                    end++;
                }

                int runLeft = left - start;
                int runRight = right - start;
                int length = start + end;

                // The run is only as good as its COMMONEST line: one blank inside an otherwise unique
                // run makes the whole run ambiguous, so the worst line decides.
                int rarity = 0;
                for (int i = 0; i < length; i++) {
                    List<Integer> here = occurrences.get(before.get(runLeft + i));
                    rarity = Math.max(rarity, here == null ? MAX_OCCURRENCES + 1 : here.size());
                }

                if (rarity < bestCount || (rarity == bestCount && best != null && length > best.length)) {
                    bestCount = rarity;
                    best = new Anchor(runLeft, runRight, length);
                }
            }
        }
        return best;
    }
}
