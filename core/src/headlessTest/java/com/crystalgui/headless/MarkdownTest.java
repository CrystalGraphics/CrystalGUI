package com.crystalgui.headless;

import com.crystalgui.text.markup.MarkupBlock;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.markup.Markdown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>Markdown, converted to the HTML the parser already reads.</b>
 *
 * <p>JSDoc descriptions are Markdown where javadoc's are HTML. The seam a symbol's documentation
 * crosses is a string, and the popup parses it at the far end — so the two languages need one output
 * format rather than two parsers, and HTML is already it.</p>
 *
 * <p>Every assertion here goes through {@link MarkupParser} as well, because the output is only correct
 * if the parser agrees: emitting a tag it does not know is a document nothing can read, and the failure
 * is silent — the tag is dropped and its content kept, which looks exactly like missing markup.</p>
 */
public class MarkdownTest {

    private static MarkupDocument parse(String markdown) {
        return MarkupParser.parse(Markdown.toHtml(markdown));
    }

    private static String kinds(MarkupDocument document) {
        StringBuilder out = new StringBuilder();
        for (MarkupBlock block : document.blocks()) {
            if (out.length() > 0) out.append(',');
            out.append(block.kind());
        }
        return out.toString();
    }

    /** Paragraphs are separated by a blank line, and a wrapped line is still one paragraph. */
    @Test
    public void blankLinesSeparateParagraphs() {
        MarkupDocument document = parse("First one,\nwrapped across lines.\n\nSecond one.\n");
        assertEquals("PARAGRAPH,PARAGRAPH", kinds(document));
        assertEquals("First one, wrapped across lines.", document.blocks().get(0).text());
        assertEquals("Second one.", document.blocks().get(1).text());
    }

    @Test
    public void headingsTakeTheirLevel() {
        MarkupDocument document = parse("# One\n\n### Three\n\n## Two ##\n");
        assertEquals("HEADING,HEADING,HEADING", kinds(document));
        assertEquals(1, document.blocks().get(0).level());
        assertEquals(3, document.blocks().get(1).level());
        assertEquals("a closing run of hashes is a fence, not content",
                "Two", document.blocks().get(2).text());
    }

    /**
     * <b>Emphasis, and the one that matters most in a doc comment.</b>
     *
     * <p>Underscores inside a word are not emphasis — doc comments are full of {@code READ_WRITE} and
     * {@code snake_case}, and treating the inner ones as markup turns a constant into a word with a
     * slanted middle and the underscores gone.</p>
     */
    @Test
    public void emphasisAndTheUnderscoreRule() {
        assertTrue(Markdown.toHtml("a **bold** word").contains("<b>bold</b>"));
        assertTrue(Markdown.toHtml("a *slanted* word").contains("<i>slanted</i>"));
        assertTrue(Markdown.toHtml("a __bold__ word").contains("<b>bold</b>"));
        assertTrue(Markdown.toHtml("an _emphasised_ word").contains("<i>emphasised</i>"));

        String constant = Markdown.toHtml("the READ_WRITE and READ_ONLY modes");
        assertFalse("an underscore inside a word is part of the word: " + constant,
                constant.contains("<i>"));
        assertTrue(constant.contains("READ_WRITE"));
        assertTrue(constant.contains("READ_ONLY"));
    }

    /**
     * <b>A code span is read before anything else.</b>
     *
     * <p>Everything between backticks is content. A pass that handled emphasis first would eat the
     * markers inside one before the span was ever seen — and the text still renders, in the wrong face,
     * with the markers gone, which is the kind of damage nobody reports.</p>
     */
    @Test
    public void aCodeSpanIsContentAndComesFirst() {
        String html = Markdown.toHtml("call `a * b` and `__init__` here");
        assertTrue(html, html.contains("<code>a * b</code>"));
        assertTrue(html, html.contains("<code>__init__</code>"));
        assertFalse("emphasis was applied inside a code span: " + html, html.contains("<i>"));
        assertFalse("emphasis was applied inside a code span: " + html, html.contains("<b>"));
    }

    /** Angle brackets inside code are content, or the parser reads them as tags and drops them. */
    @Test
    public void codeEscapesItsAngleBrackets() {
        MarkupDocument document = parse("```\nList<String> names;\n```\n");
        MarkupBlock code = document.blocks().get(0);
        assertEquals(MarkupBlock.Kind.CODE, code.kind());
        assertTrue("the type argument was read as a tag and dropped: " + code.text(),
                code.text().contains("List<String>"));
    }

    /** A fenced block keeps its line breaks; that is the whole point of it. */
    @Test
    public void aFencedBlockKeepsItsLines() {
        MarkupDocument document = parse("```js\nconst a = 1;\nconst b = 2;\n```\n");
        MarkupBlock code = document.blocks().get(0);
        assertEquals(MarkupBlock.Kind.CODE, code.kind());
        assertTrue(code.text(), code.text().contains("const a = 1;"));
        assertTrue(code.text(), code.text().contains("const b = 2;"));
        assertTrue("the two statements ran together into one line: " + code.text(),
                code.text().indexOf('\n') > 0);
    }

    @Test
    public void indentedCodeIsACodeBlockToo() {
        MarkupDocument document = parse("Text.\n\n    const a = 1;\n\nMore.\n");
        assertEquals("PARAGRAPH,CODE,PARAGRAPH", kinds(document));
    }

    @Test
    public void bulletsAndNumbersAreLists() {
        MarkupDocument bullets = parse("- one\n- two\n* three\n");
        assertEquals("LIST", kinds(bullets));
        assertEquals(3, bullets.blocks().get(0).children().size());

        MarkupDocument ordered = parse("1. one\n2. two\n");
        assertEquals("LIST", kinds(ordered));
        assertEquals("an ordered list must say it is ordered", 1, ordered.blocks().get(0).level());
    }

    /** An item wrapped across two lines is one item, which is how anything long gets written. */
    @Test
    public void anItemMayWrap() {
        MarkupDocument document = parse("- a first item that\n  runs onto a second line\n- second\n");
        MarkupBlock list = document.blocks().get(0);
        assertEquals(2, list.children().size());
        assertTrue(list.children().get(0).text(),
                list.children().get(0).text().contains("runs onto a second line"));
    }

    @Test
    public void aNestedListIsNested() {
        MarkupDocument document = parse("- outer\n  - inner\n- after\n");
        MarkupBlock list = document.blocks().get(0);
        assertEquals("the nested list must not become a sibling item", 2, list.children().size());
    }

    @Test
    public void aBlockquoteIsAQuote() {
        assertEquals("QUOTE", kinds(parse("> quoted words\n")));
    }

    /**
     * <b>A rule ends a paragraph and emits nothing.</b>
     *
     * <p>{@code <hr>} is not a tag the parser knows, and the separation a rule draws is one the blank
     * line already provides. Emitting it anyway would put a literal {@code ---} in the prose.</p>
     */
    @Test
    public void aHorizontalRuleLeavesNoText() {
        MarkupDocument document = parse("Above.\n\n---\n\nBelow.\n");
        assertEquals("PARAGRAPH,PARAGRAPH", kinds(document));
        assertFalse(document.blocks().get(0).text().contains("-"));
        assertFalse(document.blocks().get(1).text().contains("-"));
    }

    /**
     * <b>A table is recognised by its DIVIDER.</b>
     *
     * <p>A header row alone is indistinguishable from a paragraph containing pipes, and a sentence with
     * a pipe in it is ordinary prose — while {@code |---|---|} is not a line anybody writes by
     * accident.</p>
     */
    @Test
    public void aTableIsRecognisedByItsDivider() {
        MarkupDocument document = parse(
                "| Column | Meaning |\n| ------ | ------- |\n| one | the first |\n");
        MarkupBlock table = document.blocks().get(0);
        assertEquals(MarkupBlock.Kind.TABLE, table.kind());
        assertEquals("a header row and one body row", 2, table.children().size());
        assertEquals("the header cells must be headers",
                1, table.children().get(0).children().get(0).level());
        assertEquals("one", table.children().get(1).children().get(0).text());
    }

    /** A pipe in a sentence is a sentence, not a table. */
    @Test
    public void aPipeInProseIsNotATable() {
        assertEquals("PARAGRAPH", kinds(parse("Use a | b to mean either.\n")));
    }

    @Test
    public void aMarkdownLinkBecomesALink() {
        String html = Markdown.toHtml("see [the docs](https://example.com) for more");
        assertTrue(html, html.contains("<a href=\"https://example.com\">the docs</a>"));
    }

    /**
     * <b>An inline tag is a reference, not markdown.</b>
     *
     * <p>JSDoc borrows javadoc's spelling, so it has to be recognised here or the braces reach the
     * reader as text. The {@code js:} scheme is what {@code EditorLanguageFeatures} strips before
     * handing the rest to whichever engine owns the document.</p>
     */
    @Test
    public void anInlineTagBecomesAReference() {
        assertTrue(Markdown.toHtml("see {@link summarise} for it")
                .contains("<a href=\"js:summarise\">summarise</a>"));
        assertTrue("the pipe spelling is equally legal",
                Markdown.toHtml("see {@link summarise|the joiner}")
                        .contains(">the joiner</a>"));
        assertTrue("the space spelling is equally legal",
                Markdown.toHtml("see {@link summarise the joiner}")
                        .contains(">the joiner</a>"));
        assertTrue("an external target keeps its own scheme",
                Markdown.toHtml("see {@link https://example.com|here}")
                        .contains("href=\"https://example.com\""));
    }

    /**
     * <b>Raw HTML passes through, and a comparison is still a comparison.</b>
     *
     * <p>Markdown allows inline HTML and JSDoc authors use it. Prose survives because the parser only
     * reads {@code <} as a tag when a letter or a slash follows it.</p>
     */
    @Test
    public void rawHtmlSurvivesAndSoDoesArithmetic() {
        assertTrue(Markdown.toHtml("a <b>bold</b> word").contains("<b>bold</b>"));
        MarkupDocument document = parse("when a < b and c > d\n");
        assertEquals("a comparison was read as markup: " + document.blocks().get(0).text(),
                "when a < b and c > d", document.blocks().get(0).text());
    }

    /** Nothing in, nothing out — and no crash on the empty case every caller eventually passes. */
    @Test
    public void emptyInputIsEmptyOutput() {
        assertEquals("", Markdown.toHtml(""));
        assertEquals("", Markdown.toHtml(null));
        assertTrue(MarkupParser.parse(Markdown.toHtml("   \n\n  \n")).blocks().isEmpty());
    }

    /**
     * <b>A markdown image is its alt text, and is read before the link it looks like.</b>
     *
     * <p>The syntax is a link with a {@code !} in front, so a link handler reaching it first turns
     * {@code ![alt](src)} into a stray exclamation mark followed by a followable link to a picture
     * nothing can open — which is worse than the image being absent, because the reader is offered
     * something that cannot work.</p>
     */
    @Test
    public void anImageIsItsAltText() {
        String html = Markdown.toHtml("see ![the diagram](x.png) here");
        assertTrue(html, html.contains("the diagram"));
        assertFalse("the image was read as a link: " + html, html.contains("<a "));
        assertFalse("the marker leaked to the reader: " + html, html.contains("!["));
    }
}
