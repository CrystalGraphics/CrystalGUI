package com.crystalgui.headless;

import java.util.List;

import org.junit.Test;

import com.crystalgui.text.markup.MarkupBlock;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.markup.MarkupSpan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The HTML subset documentation is written in, into blocks and styled runs.
 *
 * <p><b>In {@code headlessTest} on purpose.</b> This is text in and a value type out — no fonts, no GL,
 * no window — and a dedicated server parses documentation for the same reason it holds a document. If it
 * ever needs a {@code StyleSheet} or a measurement it has stopped being a parser.</p>
 */
public class MarkupParserTest {

    private static List<MarkupBlock> blocks(String html) {
        return MarkupParser.parse(html).blocks();
    }

    private static MarkupSpan onlySpan(MarkupBlock block) {
        assertEquals("one span expected", 1, block.spans().size());
        return block.spans().get(0);
    }

    // ── Blocks ──────────────────────────────────────────────────────────────────────────────────

    /** The shape every doc comment has: prose, then a `<p>`, then more prose. */
    @Test
    public void paragraphsSplitOnP() {
        List<MarkupBlock> out = blocks("First sentence.<p>Second one.");

        assertEquals(2, out.size());
        assertEquals(MarkupBlock.Kind.PARAGRAPH, out.get(0).kind());
        assertEquals("First sentence.", out.get(0).text());
        assertEquals("Second one.", out.get(1).text());
    }

    /**
     * <b>{@code <pre>} keeps its whitespace, and nothing else does.</b>
     *
     * <p>The one rule that stops the collapsing being a pass over the finished document: a doc comment is
     * wrapped by its author at whatever column their editor used, so those newlines are not structure —
     * and inside a code sample the identical characters are the content.</p>
     */
    @Test
    public void preservesWhitespaceInsidePreAndNowhereElse() {
        List<MarkupBlock> out = blocks(
                "Wrapped across\n    two lines.<pre>if (x) {\n    y();\n}</pre>");

        assertEquals(2, out.size());
        assertEquals("the author's wrapping is not structure",
                "Wrapped across two lines.", out.get(0).text());
        assertEquals(MarkupBlock.Kind.CODE, out.get(1).kind());
        assertEquals("if (x) {\n    y();\n}", out.get(1).text());
    }

    /** Leading and trailing blank lines go; the indentation inside does not. */
    @Test
    public void aCodeBlockLosesItsBlankEdgesOnly() {
        List<MarkupBlock> out = blocks("<pre>\n\n    indented\n\n</pre>");

        assertEquals(1, out.size());
        assertEquals("    indented", out.get(0).text());
    }

    @Test
    public void listsBecomeItems() {
        List<MarkupBlock> out = blocks("<ul><li>one</li><li>two</li></ul>");

        assertEquals(1, out.size());
        MarkupBlock list = out.get(0);
        assertEquals(MarkupBlock.Kind.LIST, list.kind());
        assertEquals(2, list.children().size());
        assertEquals(MarkupBlock.Kind.ITEM, list.children().get(0).kind());
        assertEquals("one", list.children().get(0).text());
        assertEquals("two", list.children().get(1).text());
    }

    /** An ordered list says so, which is the only thing `level` carries for a list. */
    @Test
    public void anOrderedListIsMarked() {
        assertEquals(1, blocks("<ol><li>one</li></ol>").get(0).level());
        assertEquals(0, blocks("<ul><li>one</li></ul>").get(0).level());
    }

    /** A list left open by a missing close tag still reaches the document. */
    @Test
    public void anUnclosedListIsStillEmitted() {
        List<MarkupBlock> out = blocks("<ul><li>one<li>two");

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).children().size());
    }

    @Test
    public void headingsCarryTheirLevel() {
        List<MarkupBlock> out = blocks("<h3>Why this exists</h3>Because.");

        assertEquals(MarkupBlock.Kind.HEADING, out.get(0).kind());
        assertEquals(3, out.get(0).level());
        assertEquals("Why this exists", out.get(0).text());
    }

    // ── Inline styles ───────────────────────────────────────────────────────────────────────────

    @Test
    public void inlineCodeIsAStyleOnItsRun() {
        List<MarkupBlock> out = blocks("Call <code>size()</code> for that.");

        List<MarkupSpan> spans = out.get(0).spans();
        assertEquals(3, spans.size());
        assertTrue(spans.get(1).has(MarkupSpan.CODE));
        assertEquals("size()", spans.get(1).text());
        assertTrue("the prose either side is not code", !spans.get(0).has(MarkupSpan.CODE));
    }

    /**
     * <b>Styles compose rather than replace</b>, which is why they are a bitset.
     *
     * <p>{@code <b><code>x</code></b>} is bold and monospaced at once. An enum would force a choice at
     * parse time, where the information to choose does not exist.</p>
     */
    @Test
    public void nestedStylesCompose() {
        MarkupSpan span = blocks("<b><code>both</code></b>").get(0).spans().get(0);

        assertTrue(span.has(MarkupSpan.CODE));
        assertTrue(span.has(MarkupSpan.STRONG));
        assertTrue(span.has(MarkupSpan.CODE | MarkupSpan.STRONG));
    }

    @Test
    public void aLinkCarriesItsTarget() {
        MarkupSpan span = blocks("<a href=\"https://example.test/x\">there</a>").get(0).spans().get(0);

        assertTrue(span.has(MarkupSpan.LINK));
        assertEquals("there", span.text());
        assertEquals("https://example.test/x", span.target());
    }

    /**
     * A {@code >} inside a quoted attribute does not end the tag.
     *
     * <p>The characteristic bug of scanning to the next {@code >} instead of running the state machine,
     * and the reason the attribute states exist here at all when only {@code href} is kept.</p>
     */
    @Test
    public void aQuotedAttributeMayContainTheClosingBracket() {
        List<MarkupBlock> out = blocks("<a href=\"a>b\">text</a> after");

        assertEquals("a>b", out.get(0).spans().get(0).target());
        assertEquals("text after", out.get(0).text());
    }

    // ── Entities ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void namedAndNumericEntitiesDecode() {
        assertEquals("<T> & \"x\" …",
                blocks("&lt;T&gt; &amp; &quot;x&quot; &hellip;").get(0).text());
        assertEquals("AB", blocks("&#65;&#x42;").get(0).text());
    }

    /**
     * <b>A bare {@code &} in prose survives.</b>
     *
     * <p>The spec emits it as character data when what follows is not a reference, and documentation is
     * full of "read &amp; write" written without the escape. Bounding the search matters as much: without
     * it, an {@code &} takes everything up to the next semicolon anywhere in the document.</p>
     */
    @Test
    public void aBareAmpersandIsNotAnEntity() {
        assertEquals("read & write; and more", blocks("read & write; and more").get(0).text());
    }

    /** Same rule for a {@code <} that is not a tag — `a < b` is prose, not markup. */
    @Test
    public void aBareLessThanIsNotATag() {
        assertEquals("if a < b then", blocks("if a < b then").get(0).text());
    }

    // ── Degradation ─────────────────────────────────────────────────────────────────────────────

    /** An unknown tag is dropped and its content kept, which is the right failure for a hover. */
    @Test
    public void anUnknownTagDegradesToItsContent() {
        assertEquals("kept", blocks("<marquee>kept</marquee>").get(0).text());
    }

    /** Comments and doctypes are consumed, not printed. */
    @Test
    public void commentsAreDropped() {
        assertEquals("before after", blocks("before <!-- a note --> after").get(0).text());
    }

    /** A close with no open is the author's error and is not repaired into a crash. */
    @Test
    public void anUnbalancedCloseIsIgnored() {
        assertEquals("text", blocks("text</b>").get(0).text());
    }

    @Test
    public void nullAndBlankGiveTheEmptyDocument() {
        assertTrue(MarkupParser.parse(null).isEmpty());
        assertTrue(MarkupParser.parse("   \n  ").isEmpty());
        assertEquals(MarkupDocument.EMPTY, MarkupParser.parse(""));
    }

    // ── The real thing ──────────────────────────────────────────────────────────────────────────

    /**
     * {@code java.lang.String}'s own opening, verbatim from the JDK.
     *
     * <p>The input this exists for, and the one the popup renders as a wall today: two paragraphs, an
     * inline {@code <code>} and two {@code <pre>} samples.</p>
     */
    @Test
    public void theJdkStringComment() {
        String html = "The <code>String</code> class represents character strings. All string literals\n"
                + " in Java programs, such as <code>\"abc\"</code>, are implemented as instances of this\n"
                + " class.\n"
                + " <p>\n"
                + " Strings are constant; their values cannot be changed after they are created.\n"
                + " For example:\n"
                + " <blockquote><pre>\n"
                + "     String str = \"abc\";\n"
                + " </pre></blockquote><p>\n"
                + " is equivalent to:\n"
                + " <blockquote><pre>\n"
                + "     char data[] = {'a', 'b', 'c'};\n"
                + " </pre></blockquote>";

        List<MarkupBlock> out = blocks(html);

        List<MarkupBlock> code = out.stream()
                .filter(block -> block.kind() == MarkupBlock.Kind.CODE).toList();
        assertEquals("both samples are code blocks", 2, code.size());
        assertEquals("     String str = \"abc\";", code.get(0).text());
        assertEquals("     char data[] = {'a', 'b', 'c'};", code.get(1).text());

        MarkupBlock first = out.get(0);
        assertEquals(MarkupBlock.Kind.PARAGRAPH, first.kind());
        assertTrue("the class name is inline code", first.spans().stream()
                .anyMatch(span -> span.has(MarkupSpan.CODE) && "String".equals(span.text())));
        assertTrue("and the prose around it is not", first.spans().stream()
                .anyMatch(span -> !span.has(MarkupSpan.CODE)));
        assertTrue("the author's wrapping is gone", !first.text().contains("\n"));
    }
}
