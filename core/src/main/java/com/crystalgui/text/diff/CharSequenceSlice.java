package com.crystalgui.text.diff;

import java.util.List;

/**
 * The characters of a run of lines, as a {@link Sequence}, for refining a line change into word-level marks.
 *
 * <p>Ported from {@code LinesSliceCharSequence} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../defaultLinesDiffComputer/linesSliceCharSequence.ts}), MIT. <b>Modified:</b> the offset→position
 * mapping is a line-start table rather than an object per position, and only the parts the refinement needs
 * are ported.</p>
 *
 * <h3>The boundary scores are the whole feature</h3>
 *
 * <p>A character diff of two similar lines has many equally-minimal answers, and almost all of them cut
 * words in half. The scores decide where a change is allowed to land, and upstream's table is a set of
 * judgements about reading rather than about edit distance:</p>
 *
 * <ul>
 *   <li>a <b>separator</b> — a bracket, comma, operator — scores <b>30</b>, by far the best place to cut</li>
 *   <li>a <b>space</b> scores 3, {@code Other} 2, and being <em>inside a word</em> scores <b>0</b></li>
 *   <li>a <b>category change</b> is worth 10 on its own, with one extra for lowercase→uppercase — which is
 *       what makes a change inside {@code observableValue} land on the {@code V} rather than mid-word</li>
 *   <li>a <b>line break</b> before the change scores <b>150</b>, dominating everything: given the choice,
 *       a change starts at the beginning of a line</li>
 *   <li>and never between {@code \r} and {@code \n}, which scores 0 outright</li>
 * </ul>
 */
public final class CharSequenceSlice implements Sequence {

    // Boundary categories, and the score each contributes. Upstream's numbers.
    private static final int WORD_LOWER = 0;
    private static final int WORD_UPPER = 1;
    private static final int WORD_NUMBER = 2;
    private static final int END = 3;
    private static final int OTHER = 4;
    private static final int SEPARATOR = 5;
    private static final int SPACE = 6;
    private static final int LINE_BREAK_CR = 7;
    private static final int LINE_BREAK_LF = 8;

    private static final int[] CATEGORY_SCORE = {0, 0, 0, 10, 2, 30, 3, 10, 10};

    private final char[] chars;
    private final int[] lineStarts;
    private final int firstLine;

    private CharSequenceSlice(char[] chars, int[] lineStarts, int firstLine) {
        this.chars = chars;
        this.lineStarts = lineStarts;
        this.firstLine = firstLine;
    }

    /** The characters of {@code lines[fromLine, toLine)}, newline-separated. */
    public static CharSequenceSlice of(List<String> lines, int fromLine, int toLine) {
        int total = 0;
        for (int i = fromLine; i < toLine; i++) total += lines.get(i).length() + 1;
        if (total > 0) total--; // no trailing separator after the last line

        char[] chars = new char[Math.max(total, 0)];
        int[] lineStarts = new int[Math.max(toLine - fromLine, 0)];
        int at = 0;
        for (int i = fromLine; i < toLine; i++) {
            lineStarts[i - fromLine] = at;
            String line = lines.get(i);
            line.getChars(0, line.length(), chars, at);
            at += line.length();
            if (i + 1 < toLine) chars[at++] = '\n';
        }
        return new CharSequenceSlice(chars, lineStarts, fromLine);
    }

    @Override
    public int length() {
        return chars.length;
    }

    @Override
    public int elementAt(int offset) {
        return chars[offset];
    }

    @Override
    public boolean stronglyEqual(int offset1, int offset2) {
        return chars[offset1] == chars[offset2];
    }

    /** The line this offset falls on, in the ORIGINAL text's numbering. */
    public int lineOf(int offset) {
        // An empty slice has no lines to index -- a pure insertion refines against nothing on one side.
        if (lineStarts.length == 0) return firstLine;
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= offset) low = mid;
            else high = mid - 1;
        }
        return firstLine + low;
    }

    /** The column this offset falls at, within its line. */
    public int columnOf(int offset) {
        if (lineStarts.length == 0) return 0;
        int line = lineOf(offset) - firstLine;
        return offset - lineStarts[line];
    }

    @Override
    public int boundaryScore(int offset) {
        int previous = categoryOf(offset > 0 ? chars[offset - 1] : -1);
        int next = categoryOf(offset < chars.length ? chars[offset] : -1);

        // Never split a CRLF pair: the two halves are one line ending, and a mark between them would be
        // a change to a character nobody can see.
        if (previous == LINE_BREAK_CR && next == LINE_BREAK_LF) return 0;
        // A change that can start at the beginning of a line should.
        if (previous == LINE_BREAK_LF) return 150;

        int score = 0;
        if (previous != next) {
            score += 10;
            // camelCase: the hump is a real boundary to a reader even though no separator is there.
            if (previous == WORD_LOWER && next == WORD_UPPER) score += 1;
        }
        return score + CATEGORY_SCORE[previous] + CATEGORY_SCORE[next];
    }

    /**
     * The whole word containing this offset, as {@code {start, end}}, or null if it is not in one.
     *
     * <p>Ported from {@code LinesSliceCharSequence.findWordContaining}. A "word" here is a run of letters,
     * digits and underscores — deliberately not the editor's own word rule, because this is about what a
     * <em>reader</em> sees as one token in a diff, not about where a caret should stop.</p>
     */
    public int[] wordAround(int offset) {
        if (offset < 0 || offset >= chars.length || !isWordChar(chars[offset])) return null;
        int start = offset;
        while (start > 0 && isWordChar(chars[start - 1])) start--;
        int end = offset;
        while (end < chars.length && isWordChar(chars[end])) end++;
        return new int[] {start, end};
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int categoryOf(int c) {
        if (c == '\n') return LINE_BREAK_LF;
        if (c == '\r') return LINE_BREAK_CR;
        if (c == ' ' || c == '\t') return SPACE;
        if (c >= 'a' && c <= 'z') return WORD_LOWER;
        if (c >= 'A' && c <= 'Z') return WORD_UPPER;
        if (c >= '0' && c <= '9') return WORD_NUMBER;
        if (c == -1) return END;
        if (c == '_') return WORD_LOWER;
        // Everything else printable is treated as a separator -- brackets, commas, operators. These are
        // the best cut points in code, which is why they score highest.
        return Character.isLetterOrDigit(c) ? OTHER : SEPARATOR;
    }
}
