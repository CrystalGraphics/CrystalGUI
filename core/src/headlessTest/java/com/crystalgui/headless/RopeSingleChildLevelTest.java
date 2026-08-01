package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * <b>A level holding exactly one child must not collapse.</b>
 *
 * <p>{@code Rope.build} groups leaves {@code MAX_CHILDREN} at a time, so any level whose node count is
 * {@code ≡ 1 (mod 8)} ends in a one-child {@code Internal}. {@code fromChildren} used to return that
 * child bare, dropping the rebuilt subtree by a level — and {@code concat}'s two unequal-height branches
 * both read a height mismatch as "the join grew a level" and cast to {@code Internal}. A join that
 * <em>shrank</em> one failed that cast: {@code Leaf cannot be cast to Internal}, thrown out of
 * {@code slice} and therefore out of {@code Rope.line}.</p>
 *
 * <p>It was not theoretical and not confined to the rope. {@code new TextEditor(text)} calls
 * {@code ProjectedLines.rebuild}, which reads every row — so <b>constructing an editor over an ordinary
 * file threw</b>, at about 8.2 KB, 16.4 KB and every further size that lands on the same boundary. Found
 * by a throwaway performance probe that did nothing but build an editor over 500 lines, which is the only
 * reason it surfaced at all: the sizes in between are fine, so a suite that happens to use short documents
 * sees none of it.</p>
 */
public class RopeSingleChildLevelTest {

    private static String document(int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            out.append("    void method").append(i).append("() { call(); }\n");
        }
        return out.toString();
    }

    /** Every row of every document size must be readable. */
    @Test
    public void sliceNeverCrashes() {
        StringBuilder failures = new StringBuilder();
        for (int lines = 1; lines <= 600; lines++) {
            String text = document(lines);
            Rope rope = Rope.of(text);
            try {
                for (int row = 0; row < rope.lineCount(); row++) rope.line(row);
            } catch (RuntimeException e) {
                failures.append("\n  ").append(lines).append(" lines (").append(text.length())
                        .append(" chars): ").append(e.getClass().getSimpleName())
                        .append(": ").append(e.getMessage());
                if (failures.length() > 1200) break;
            }
        }
        assertEquals("Rope.line must not throw:" + failures, 0, failures.length());
    }

    /** And the text it returns must be right, not merely non-throwing. */
    @Test
    public void sliceReturnsTheRightText() {
        for (int lines = 1; lines <= 600; lines++) {
            String text = document(lines);
            Rope rope = Rope.of(text);
            String[] expected = text.split("\n", -1);
            for (int row = 0; row < rope.lineCount(); row++) {
                assertEquals(lines + " lines, row " + row, expected[row], rope.line(row));
            }
        }
    }

    /** Arbitrary slices, not only whole rows. */
    @Test
    public void arbitrarySlicesAreCorrect() {
        String text = document(400);
        Rope rope = Rope.of(text);
        int n = text.length();
        for (int start = 0; start < n; start += 37) {
            for (int len : new int[] { 1, 13, 250, 4096 }) {
                int end = Math.min(n, start + len);
                assertEquals("slice(" + start + "," + end + ")",
                        text.substring(start, end), rope.slice(start, end).toString());
            }
        }
    }
}
