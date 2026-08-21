package com.crystalgui.ui.elements.workbench;

import com.crystalgui.text.TextPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link QueryLocation} — the trailing {@code :line} on a Go to Class query.
 *
 * <h3>What is actually being pinned</h3>
 *
 * <p>Not "a colon works". The separator list is ported verbatim from IntelliJ because it is every shape a
 * file-and-line takes <b>when pasted from somewhere else</b> — a stack trace, a compiler message, a GitHub
 * URL, a log line. The feature is "paste the thing you copied and it works", and that claim is only true
 * if the whole list is there. A test that checked one separator would pass against a version that had
 * quietly lost the other eleven, and the loss would surface as "it works for stack traces but not for the
 * link my colleague sent me" — which reads as a paste problem, not a parser one.</p>
 *
 * <p>Headless on purpose: this is a regex and an integer, and putting it here asserts that. Nothing in the
 * navigation path should need a GL context to decide what somebody typed.</p>
 */
public class QueryLocationTest {

    private static void at(String query, String name, int row, int column) {
        QueryLocation parsed = QueryLocation.parse(query);
        assertEquals(query + " → name", name, parsed.name());
        assertTrue(query + " → found no location at all", parsed.hasPoint());
        TextPoint point = parsed.point();
        assertEquals(query + " → row", row, point.row());
        assertEquals(query + " → column", column, point.column());
    }

    private static void plain(String query, String name) {
        QueryLocation parsed = QueryLocation.parse(query);
        assertEquals(query + " → name", name, parsed.name());
        assertFalse(query + " → invented a location", parsed.hasPoint());
        assertNull(parsed.point());
    }

    // ── Every separator IntelliJ accepts ────────────────────────────────────────────────────────

    /**
     * <b>All twelve separators reach the same line.</b>
     *
     * <p>Each entry names where that spelling comes from, because that is the argument for keeping it:
     * none of these is a syntax somebody would invent, and every one is something a person has in their
     * clipboard.</p>
     */
    @Test
    public void everyPastedSpellingOfALineNumberIsUnderstood() {
        at("ArrayList:42", "ArrayList", 41, 0);            // a stack trace
        at("ArrayList@42", "ArrayList", 41, 0);
        at("ArrayList,42", "ArrayList", 41, 0);
        at("ArrayList 42", "ArrayList", 41, 0);            // a log line
        at("ArrayList#42", "ArrayList", 41, 0);
        at("ArrayList#L42", "ArrayList", 41, 0);           // a GitHub anchor
        at("ArrayList?l=42", "ArrayList", 41, 0);          // a GitHub query string
        at("ArrayList on line 42", "ArrayList", 41, 0);
        at("ArrayList at line 42", "ArrayList", 41, 0);
        at("ArrayList:line 42", "ArrayList", 41, 0);
        at("ArrayList(42", "ArrayList", 41, 0);            // a compiler message
        at("ArrayList[42", "ArrayList", 41, 0);
    }

    /**
     * <b>A column is the second number, and one-based like the first.</b>
     *
     * <p>{@code Foo.java(42,8)} is javac's own wording, brackets and all — which is why the pattern ends
     * by allowing a closing paren.</p>
     */
    @Test
    public void aSecondNumberIsTheColumn() {
        at("Parser(42,8)", "Parser", 41, 7);
        at("Parser:42:8", "Parser", 41, 7);
    }

    // ── And what must NOT be read as a location ─────────────────────────────────────────────────

    /**
     * <b>An ordinary name is returned untouched.</b>
     *
     * <p>The path nearly every keystroke takes. It also never reaches the regex: the guard tests for a
     * separator character first, so the cost of this feature on a normal query is one scan of a short
     * string.</p>
     */
    @Test
    public void anOrdinaryNameIsLeftAlone() {
        plain("ArrayList", "ArrayList");
        plain("CgTextRenderer", "CgTextRenderer");
        plain("", "");
    }

    /**
     * <b>A separator with no number yet is somebody mid-keystroke, not a location.</b>
     *
     * <p>The name still strips, so the list keeps showing {@code ArrayList} while the colon is typed and
     * before the digits are. Treating it as a location would put the caret at line 1; refusing to strip
     * would empty the list for the frame between {@code :} and {@code 4}, which reads as the search
     * breaking on a keystroke.</p>
     */
    @Test
    public void aSeparatorWithNoNumberStripsButLocatesNothing() {
        plain("ArrayList:", "ArrayList");
        plain("ArrayList#", "ArrayList");
    }

    /**
     * <b>A qualified name is not a line number.</b>
     *
     * <p>The one that would be silently catastrophic. Dots are not separators in the pattern — deliberately
     * — so {@code java.util.ArrayList} survives whole. If a dot were ever added to that list, every
     * qualified query in the application would search for {@code java} instead, and it would still return
     * rows, which is what makes it worth a test rather than a comment.</p>
     */
    @Test
    public void aQualifiedNameIsNotMistakenForALocation() {
        plain("java.util.ArrayList", "java.util.ArrayList");
        at("java.util.ArrayList:42", "java.util.ArrayList", 41, 0);
    }

    /** <b>Whitespace around the whole query is not part of the name.</b> Paste brings it along. */
    @Test
    public void surroundingWhitespaceIsTrimmed() {
        plain("  ArrayList  ", "ArrayList");
        at("  ArrayList:42  ", "ArrayList", 41, 0);
    }

    /**
     * <b>A number that will not parse leaves the name and drops the location.</b>
     *
     * <p>Rather than throwing out of a keystroke handler. The name is still the useful half.</p>
     */
    @Test
    public void anUnparseableNumberIsNotAFailure() {
        plain("ArrayList:99999999999999999999", "ArrayList");
    }

    /** <b>Line 0 and line 1 both mean the first line</b>, since the input is one-based and clamped. */
    @Test
    public void theFirstLineIsReachableFromBothSpellings() {
        at("ArrayList:1", "ArrayList", 0, 0);
        at("ArrayList:0", "ArrayList", 0, 0);
    }
}
