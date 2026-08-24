package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Line splitting, and the hunk shape the rest of the engine consumes.
 *
 * <h3>This was the differ; now it is a facade over one</h3>
 *
 * <p>It began as a <b>histogram</b> implementation — patience with low-occurrence handling — chosen because
 * anchoring on rare lines keeps moved blocks intact where a shortest-edit-script algorithm shreds them.
 * That reasoning was sound and the implementation was replaced anyway, by
 * {@link LinesDiff}: VS Code's computer reaches the same goal from the other direction, taking an exact or
 * Myers diff and then applying named heuristics ({@link SequenceOptimizations}) that shift and join edits
 * into the shape a person would have drawn. Heuristics over a well-understood base beat a different anchor
 * rule, and they are individually testable in a way an anchor rule is not.</p>
 *
 * <p>Kept as a facade rather than deleted because {@code Hunk} is what {@link TextDiff}, {@link DiffIterable}
 * and the merge stack are written against, and because {@link #lines} is the one definition of how a text
 * splits — which is not a differ's business but has to live somewhere.</p>
 */
public final class LineDiff {

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
     * {@code [3,5)} on the right — which is what lets a viewer put an insertion <em>between</em> two lines
     * rather than having to pick one of them.</p>
     */
    public static List<Hunk> diff(List<String> before, List<String> after) {
        List<Hunk> hunks = new ArrayList<>();
        for (DiffRange range : LinesDiff.compute(before, after).diffs()) {
            hunks.add(new Hunk(range.start1(), range.end1(), range.start2(), range.end2()));
        }
        return hunks;
    }

    /** Convenience over whole texts, split on line boundaries. */
    public static List<Hunk> diff(String before, String after) {
        return diff(lines(before), lines(after));
    }

    /**
     * Splits a text into lines.
     *
     * <p><b>A trailing newline terminates the last line; it does not begin another.</b> So {@code "a\nb\n"}
     * is two lines, not three. Getting it wrong makes every file that ends in a newline — which is most
     * files — report a phantom change on its last line. The corollary is that this loses the distinction
     * between {@code "a\nb\n"} and {@code "a\nb"} entirely, which is why {@link TextDiff} has to restore it
     * at the offset seam where there is enough information to.</p>
     */
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) return lines;

        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }
}
