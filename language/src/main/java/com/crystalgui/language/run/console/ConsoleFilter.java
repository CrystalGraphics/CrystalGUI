package com.crystalgui.language.run.console;

import java.util.List;

/**
 * Finds the navigable spans in a line of console output.
 *
 * <h3>IntelliJ's {@code Filter}, and the reason it is an SPI rather than a special case</h3>
 *
 * <p>IntelliJ's console knows nothing about stack frames. It runs a chain of {@code Filter}s over each
 * line, each returning {@code ResultItem(highlightStartOffset, highlightEndOffset, HyperlinkInfo)} — and
 * that is why the same console links a compiler's {@code file:line}, a JUnit failure and a URL without a
 * line of code about any of them. A GLSL filter and a JS filter then arrive in M10/M11 as one class each,
 * with nothing here to change.</p>
 *
 * <h3>This is a different question from a message's origin</h3>
 *
 * <p>{@link RunMessage} already carries a {@code file} and a {@code line} — the <b>origin</b>, resolved
 * from the first stack frame the script owns. That answers <i>where was this printed from</i>. It does
 * not answer <i>what does this text point at</i>: a stack trace has <b>one</b> origin, the reporter, and
 * twenty frames in its text each pointing somewhere different. Navigating a trace by its origin lands
 * every frame on the same line.</p>
 *
 * <h3>A pure function of the text, deliberately</h3>
 *
 * <p>Spans are recomputed from a row's own string whenever they are wanted, and never stored. Storing
 * them would mean holding document offsets, and <b>the ring deletes from the front of the document</b> —
 * an edit, after which every held offset describes the wrong text. That failure is silent: the transcript
 * keeps working and the links quietly start opening the wrong lines. Recomputation cannot desync because
 * there is nothing to desync, and the cost is a regex over the handful of rows actually on screen.</p>
 */
public interface ConsoleFilter {

    /** Every navigable span in {@code text}, in column offsets relative to the line's own start. */
    List<Link> apply(String text);

    /**
     * One navigable span — where it is, and where it points.
     *
     * <p>Carries the file's <b>simple name</b> and nothing more, because that is genuinely all the text
     * says. Turning {@code RunTest.java} into a workspace file is a question about the workspace, so it
     * belongs to whoever has one — see {@code RunPanels}. A filter that resolved would need the workspace
     * to be testable, and a frame naming a JDK class would have to invent an answer rather than admit it
     * has none.</p>
     *
     * @param start    column of the first character of the span, inclusive
     * @param end      column one past its last character
     * @param fileName the source file's simple name, e.g. {@code RunTest.java}
     * @param line     the 1-based line number the span names
     */
    record Link(int start, int end, String fileName, int line) {

        public Link {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("empty or reversed link span: " + start + ".." + end);
            }
            if (fileName == null || fileName.isEmpty()) {
                throw new IllegalArgumentException("a link with no file name points nowhere");
            }
        }

        /** Whether {@code column} falls inside this span — the hit test, in one place. */
        public boolean contains(int column) {
            return column >= start && column < end;
        }
    }
}
