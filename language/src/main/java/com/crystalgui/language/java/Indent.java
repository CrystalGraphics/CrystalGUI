package com.crystalgui.language.java;

/**
 * <b>Where a line begins, and how to move written text to a different column</b> — the whitespace half of
 * every correction that carries source from one place to another.
 *
 * <p>Extracted from the four copies of {@code indentAt} that had accumulated one per family. That much was
 * only tidiness; {@link #reindent} is not.</p>
 */
final class Indent {

    private Indent() {
    }

    /** What one level of indentation looks like in a file: a tab, or some number of spaces. */
    record Style(boolean tabs, int size) {
    }

    /** Four spaces — what a file with nothing indented in it is assumed to want. */
    private static final Style DEFAULT_STYLE = new Style(false, 4);

    /** Nobody indents by more than this; a line further in is continuation, not a level. */
    private static final int WIDEST_LEVEL = 8;

    /**
     * The indentation <b>this file already uses</b>, read off its own text.
     *
     * <p>Replaces a hard-coded four spaces that carried a note promising the editor's setting would arrive
     * "with the host-side context". It did not, and meanwhile a tab-indented script had spaces generated
     * into it by every correction that writes a line. Asking the document is what both references do as
     * their fallback — VS Code calls it {@code editor.detectIndentation} and it is on by default — and it
     * needs no new seam, which is why it beat waiting for one.</p>
     *
     * <p>The <b>first indented line wins</b>, deliberately: a whole-file histogram is the better answer for
     * a file with mixed indentation, and a file with mixed indentation has no answer worth computing
     * carefully. A leading tab means tabs; otherwise the count of spaces, ignoring anything past
     * {@value #WIDEST_LEVEL}, which is a wrapped continuation rather than a level.</p>
     */
    static Style detect(String source) {
        for (int at = 0; at < source.length(); ) {
            int end = source.indexOf('\n', at);
            if (end < 0) end = source.length();
            if (at < end) {
                if (source.charAt(at) == '\t') return new Style(true, 4);
                int spaces = 0;
                while (at + spaces < end && source.charAt(at + spaces) == ' ') spaces++;
                // A LINE THAT IS ONLY WHITESPACE says nothing about indentation, and blank-but-spaced
                // lines are common enough that taking one would answer with the previous level's width.
                if (spaces > 0 && at + spaces < end && spaces <= WIDEST_LEVEL) {
                    return new Style(false, spaces);
                }
            }
            at = end + 1;
        }
        return DEFAULT_STYLE;
    }

    /** The leading whitespace of the line {@code position} is on. */
    static String at(String source, int position) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int at = lineStart;
        while (at < source.length() && at < position
                && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) {
            at++;
        }
        return source.substring(lineStart, at);
    }

    /**
     * {@code text} moved to sit at {@code indent}, <b>keeping its own shape</b>.
     *
     * <h3>The shift is by the minimum common indent, never by trimming each line</h3>
     *
     * <p>Three separate intentions carried a body from one place to another by writing
     * {@code indent + line.trim()} per line, which does not re-indent text — it <em>flattens</em> it. A
     * branch containing a nested block came out with the {@code if}, its contents and its closing brace all
     * at one column: legal Java, unreadable, and produced by the three corrections whose whole argument is
     * that they preserve the body as written.</p>
     *
     * <p>So every line moves by the same amount, and that amount is the smallest indent any of them has.
     * The first line is excluded from the measurement and stripped outright, because callers hand over text
     * starting at a node's first character — where the original indent was consumed by the line above and
     * there is none left to measure.</p>
     *
     * <p>Trailing whitespace goes, which is why a blank line comes back empty rather than as {@code indent}.
     * That also drops the carriage returns a CRLF file would otherwise smuggle into generated source.</p>
     */
    static String reindent(String text, String indent) {
        String[] lines = text.split("\n", -1);
        int shift = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            String line = withoutTrailingSpace(lines[i]);
            if (!line.isEmpty()) shift = Math.min(shift, leadingSpace(line));
        }
        if (shift == Integer.MAX_VALUE) shift = 0;

        StringBuilder built = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = withoutTrailingSpace(lines[i]);
            if (i > 0) built.append('\n');
            if (line.isEmpty()) continue;
            int drop = i == 0 ? leadingSpace(line) : Math.min(shift, leadingSpace(line));
            built.append(indent).append(line, drop, line.length());
        }
        return built.toString();
    }

    private static int leadingSpace(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) at++;
        return at;
    }

    private static String withoutTrailingSpace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }
}
