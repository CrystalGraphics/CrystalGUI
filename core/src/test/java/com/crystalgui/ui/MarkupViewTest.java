package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.elements.MarkupView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MarkupView} — the renderer half of the documentation stack.
 *
 * <h3>What these cover, and why only these</h3>
 *
 * <p><b>Rebuilding</b>, because the obvious build is wrong in a way nothing reports: blocks marked as
 * internal children survive {@code clearAllChildren()}, so the view would stack every document it had
 * ever been given and the only symptom is a popup that grows. And <b>where the inline bands land</b>,
 * which is the failure {@code UIText}'s note and {@code DocumentationPopupTest} both record — a range is
 * an offset into a string, so a band computed against the wrong string paints over whatever text moved
 * into those characters. Nothing throws either way.</p>
 *
 * <p>Deliberately not covered: what a paragraph looks like. Sizes, colours and spacing are the
 * stylesheet's, and asserting them here would break on any legitimate restyle.</p>
 */
public class MarkupViewTest extends UiTestBase {

    /**
     * <b>A second document replaces the first; it does not stack on top of it.</b>
     *
     * <p>The trap is {@code clearAllChildren()}, which skips internal children by design — so building
     * the blocks as internal (the instinct, since they are structure the widget owns) leaves every
     * previous document in the tree. The popup would simply get longer with each symbol hovered, which
     * reads as a layout bug rather than as a rebuild that never removed anything.</p>
     */
    @Test
    public void settingASecondDocumentReplacesTheFirstRatherThanAppendingIt() {
        MarkupView view = new MarkupView();
        view.setDocument(MarkupParser.parse("<p>one</p><p>two</p>"));
        int afterFirst = view.getChildren().size();
        assertTrue("the first document produced no blocks at all", afterFirst > 0);

        view.setDocument(MarkupParser.parse("<p>only this</p>"));

        assertEquals("blocks from the previous document survived the rebuild",
                1, view.getChildren().size());
        assertTrue("the surviving block is not the new one: <" + allText(view) + ">",
                allText(view).contains("only this"));
        assertTrue("text from the replaced document is still drawn: <" + allText(view) + ">",
                !allText(view).contains("one"));
    }

    /** An empty document leaves nothing behind — the caller's cue to hide the band. */
    @Test
    public void anEmptyDocumentClearsTheViewAndReportsItself() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p>something</p>"));
        assertTrue("a document with a paragraph reported itself empty", !view.isEmpty());

        view.setDocument(MarkupParser.parse(""));

        assertTrue("an empty document did not report itself empty", view.isEmpty());
        assertEquals("an empty document left blocks behind", 0, view.getChildren().size());
    }

    /**
     * <b>An inline band covers exactly the characters it was written over.</b>
     *
     * <p>Offsets are counted as the run is assembled rather than searched for afterwards. A search would
     * be right here by luck and wrong for any word that repeats, which in prose is most of them — so the
     * fixture repeats one deliberately: {@code code} appears as ordinary prose <em>before</em> the
     * {@code <code>} element, and a search-based implementation marks the first occurrence.</p>
     */
    @Test
    public void anInlineBandCoversTheCharactersItWasWrittenOver() {
        MarkupView view = new MarkupView(
                MarkupParser.parse("<p>the code word is <code>null</code> here</p>"));

        UIText paragraph = firstText(view);
        assertNotNull("no text run was built for the paragraph", paragraph);
        String rendered = paragraph.getText();

        List<TextRange> code = paragraph.highlights().get(MarkupView.CODE_RANGE);
        assertEquals("expected exactly one code band, got " + code, 1, code.size());

        TextRange band = code.get(0);
        // ONE SPACE EACH SIDE, INSIDE THE BAND. That is the chip's padding: `HighlightStyle` permits
        // horizontal padding and it paints, but it inflates the rect without moving a glyph, so the plate
        // grew outwards and swallowed the single space either side of the chip. Real advance is the only
        // padding layout will honour -- so the band covering exactly ` null ` is the assertion, and a band
        // covering `null` would mean the plate is back to touching its neighbours.
        assertEquals("the band does not cover the padded chip: <" + rendered + "> band=" + band,
                " null ", rendered.substring(band.start(), band.end()));
        assertTrue("the word itself was altered, not merely padded: <" + rendered + ">",
                rendered.contains(" null "));
    }

    /**
     * <b>A band is cleared on every block, not merely reassigned where there is something to say.</b>
     *
     * <p>The failure this prevents is not a stale style: a run left over from a previous document is a
     * set of offsets into a string that no longer exists, so it lands on whatever characters moved into
     * them. Here the second document is longer than the first, so a surviving band would sit over real
     * text and look entirely plausible.</p>
     */
    @Test
    public void aBandFromAPreviousDocumentDoesNotSurviveIntoTheNextOne() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p><code>x</code> y</p>"));
        assertEquals("the fixture did not produce the band it is about to check for",
                1, firstText(view).highlights().get(MarkupView.CODE_RANGE).size());

        view.setDocument(MarkupParser.parse("<p>a longer paragraph with no code in it at all</p>"));

        List<TextRange> code = firstText(view).highlights().get(MarkupView.CODE_RANGE);
        assertTrue("a code band from the previous document is still live: " + code, code.isEmpty());
    }

    /**
     * <b>Both styles of a doubly-styled run are reported.</b>
     *
     * <p>{@code MarkupSpan.styles()} is a bitset precisely so {@code <b><code>x</code></b>} does not have
     * to choose, and an enum-shaped renderer would drop one of them at this seam instead. The cascade
     * composes the two rules; what is asserted here is that both bands are registered at all.</p>
     */
    @Test
    public void aRunThatIsBothBoldAndCodeRegistersInBothBands() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p><b><code>id</code></b></p>"));
        UIText paragraph = firstText(view);

        assertEquals("the code band was dropped", 1,
                paragraph.highlights().get(MarkupView.CODE_RANGE).size());
        assertEquals("the strong band was dropped", 1,
                paragraph.highlights().get(MarkupView.STRONG_RANGE).size());
    }

    /**
     * <b>A {@code <pre>} sample becomes its own element and keeps its whitespace.</b>
     *
     * <p>It is a box rather than a band because it needs a background behind the whole block, including
     * the ends of short lines. The indentation is the assertion that the parser's "do not collapse inside
     * pre" decision survives to the element that draws it.</p>
     */
    @Test
    public void aPreSampleBecomesItsOwnBlockWithItsWhitespaceIntact() {
        MarkupView view = new MarkupView(
                MarkupParser.parse("<p>before</p><pre>if (x) {\n    y();\n}</pre>"));

        UIElement sample = null;
        for (UIElement child : view.getChildren()) {
            if (child.hasClass(MarkupView.CODE_BLOCK_CLASS)) sample = child;
        }
        assertNotNull("the <pre> did not become a code block element", sample);

        String text = allText(sample);
        assertTrue("the sample's indentation was collapsed: <" + text + ">", text.contains("    y();"));
        assertTrue("the sample lost its line breaks: <" + text + ">", text.contains("\n"));
    }

    /**
     * <b>A {@code <pre>} sample is lexed when a language has been named, and left alone otherwise.</b>
     *
     * <p>Asserted through the {@code __syntax__} class rather than through the bands, because that class
     * is the half that is easy to omit and impossible to see: a run carrying ranges without it resolves
     * every scheme rule to nothing and draws as plain text, which reads as the lexing not working rather
     * than as a missing selector. The band count then says the tokenizer was actually consulted.</p>
     *
     * <p>{@code KeywordTokenizer} is what answers here — {@code core}'s own engineless tier, since a test
     * in this module has no grammar module behind it. That is the point: the seam is
     * {@link com.crystalgui.text.syntax.SyntaxTokenizer}, and a consumer must not care which side of it
     * answered.</p>
     */
    @Test
    public void aCodeSampleIsLexedOnlyWhenALanguageIsNamed() {
        String doc = "<pre>class A { int x = 1; }</pre>";

        MarkupView plain = new MarkupView();
        plain.setDocument(MarkupParser.parse(doc));
        UIText untouched = firstText(plain);
        assertNotNull("the sample produced no text run", untouched);
        assertTrue("an unnamed language still marked the sample as code",
                !untouched.hasClass(UIText.SYNTAX_CLASS));

        MarkupView java = new MarkupView().setCodeLanguage(Language.JAVA);
        java.setDocument(MarkupParser.parse(doc));
        UIText lexed = firstText(java);
        assertNotNull("the sample produced no text run", lexed);
        assertTrue("the sample was not marked as syntax-coloured, so no scheme rule can reach it",
                lexed.hasClass(UIText.SYNTAX_CLASS));
        assertTrue("no bands were registered, so nothing was lexed",
                !lexed.highlights().entries().isEmpty());
    }

    /**
     * <b>The sample's text is untouched by lexing.</b>
     *
     * <p>Bands are offsets into the run's own string, so anything that rewrote the text to colour it
     * would be painting over the wrong characters. Worth stating because the inline chip <em>does</em>
     * pad its text, and a reader who knows that would reasonably expect this to as well.</p>
     */
    @Test
    public void lexingDoesNotAlterTheSampleText() {
        String sample = "class A { int x = 1; }";
        MarkupView view = new MarkupView().setCodeLanguage(Language.JAVA);
        view.setDocument(MarkupParser.parse("<pre>" + sample + "</pre>"));

        assertEquals("the sample was rewritten", sample, firstText(view).getText());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static UIText firstText(UIElement root) {
        if (root instanceof UIText) return (UIText) root;
        for (UIElement child : root.getChildren()) {
            UIText found = firstText(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String allText(UIElement root) {
        List<String> parts = new ArrayList<>();
        collectText(root, parts);
        return String.join("", parts);
    }

    private static void collectText(UIElement root, List<String> into) {
        if (root instanceof UIText) into.add(((UIText) root).getText());
        for (UIElement child : root.getChildren()) collectText(child, into);
    }
}
