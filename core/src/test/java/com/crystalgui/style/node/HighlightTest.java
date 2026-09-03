package com.crystalgui.style.node;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.text.TextRange;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.*;

/**
 * P6.1.1 — the CSS Custom Highlight API: {@code ::highlight(name)} plus a per-element range registry.
 *
 * <h3>Why this shape, and not a list of styled spans</h3>
 * <p>The first implementation of this feature took a {@code List<TextSpan>} carrying literal colours from
 * Java. That is not something the web has: the DOM styles ranges of text in exactly two ways — inline
 * child elements (rich text as <em>content</em>), or the Custom Highlight API (decoration <em>over</em>
 * content). A span list on a text node is neither, and it put colours in Java, which this project's own
 * rules forbid.</p>
 *
 * <p>So the ranges live in Java, the styling lives in CSS, and the property set is the spec's: things
 * that cannot affect layout. That last restriction is the interesting one and is pinned below.</p>
 */
public class HighlightTest extends UiDocumentTestBase {

    private static final String SENTENCE = "the quick brown fox";

    private UIElement root;

    private UIText build(String content, String css) {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIText text = new UIText(content);
        root.append(text);
        document.append(root);
        if (css != null) document.styleEngine().addStylesheet(StyleSheet.parse(css));
        settle();
        settle();
        return text;
    }

    private void settle() {
        frame();
    }

    // ── text-decoration-line on the element itself ───────────────────────────

    /**
     * <b>An element's own {@code text-decoration-line} must reach the paint.</b>
     *
     * <p>It resolved through the cascade and inherited correctly and then went nowhere: decorations were
     * only ever read off a {@code ::highlight()} style, so {@code text-decoration-line: underline} on a
     * label was a no-op that looked like a missing CSS feature. Asserting the computed value passes
     * against exactly that version, which is why this asserts the <b>span</b> instead.</p>
     */
    @Test
    public void anElementsOwnUnderlineReachesTheShapedText() {
        UIText plain = build(SENTENCE, null);
        assertEquals("no decoration, no spans -- ordinary labels stay on the unspanned path",
                0, plain.styleSpanCount());

        UIText underlined = build(SENTENCE, "text { text-decoration-line: underline; }");
        assertTrue("an underline must produce a span to carry it",
                underlined.styleSpanCount() > 0);
    }

    /** The decoration also covers the parts no highlight claims, rather than only the gaps between them. */
    @Test
    public void anUnderlineSurvivesAlongsideHighlights() {
        UIText text = build(SENTENCE,
                "text { text-decoration-line: underline; }"
                        + " text::highlight(word) { color: #FF0000; }");
        text.highlights().set("word", java.util.List.of(TextRange.of(4, 9)));
        settle();
        settle();

        // Before, during and after the highlight: three runs, and the two outer ones carry the underline.
        assertTrue("the underline must still cover the unhighlighted text, got "
                + text.styleSpanCount() + " spans", text.styleSpanCount() >= 3);
    }

    // ── The selector ─────────────────────────────────────────────────────────

    /**
     * <b>A rule with a pseudo-element must not style the element itself.</b>
     *
     * <p>{@code text::highlight(kw)} selects the highlight overlay of a {@code text}, not the
     * {@code text}. If the compound matched the element, every {@code ::highlight()} colour would repaint
     * the whole paragraph — and it would look plausible, because the highlighted words would be the right
     * colour too.</p>
     */
    @Test
    public void aHighlightRuleDoesNotStyleTheOriginatingElement() {
        UIText text = build(SENTENCE, "text { color: #FFFFFF; } text::highlight(kw) { color: #FF0000; }");
        assertEquals("the element keeps its own colour",
                0xFFFFFFFF, text.getStyle().getGeneralGroup().color());
    }

    @Test
    public void theStyleIsReachableUnderItsName() {
        UIText text = build(SENTENCE, "text::highlight(kw) { color: #FF0000; }");
        var style = document.styleEngine().highlightStyle(text, "kw");
        assertFalse(style.isEmpty());
        assertEquals(0xFFFF0000, style.color(0));
        assertTrue("an unstyled name resolves empty rather than null",
                document.styleEngine().highlightStyle(text, "nope").isEmpty());
    }

    /** Descendant combinators still work: the pseudo-element only ever sits on the rightmost compound. */
    @Test
    public void ancestorsStillConstrainTheMatch() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement scoped = new UIElement();
        scoped.addClass("code");
        UIText inside = new UIText(SENTENCE);
        UIText outside = new UIText(SENTENCE);
        scoped.append(inside);
        root.append(scoped);
        root.append(outside);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(".code text::highlight(kw) { color: #FF0000; }"));
        settle();

        assertFalse(document.styleEngine().highlightStyle(inside, "kw").isEmpty());
        assertTrue("outside the .code subtree it must not apply",
                document.styleEngine().highlightStyle(outside, "kw").isEmpty());
    }

    /** Pseudo-elements count in the TYPE component of specificity (weight 1), not the class component —
     * easy to get wrong by analogy with pseudo-classes, which really are 10. */
    @Test
    public void aPseudoElementWeighsOneNotTen() {
        var withPseudo = com.crystalgui.style.selector.Selector.parse("text::highlight(kw)");
        var withPseudoClass = com.crystalgui.style.selector.Selector.parse("text:hover");
        assertEquals(2, withPseudo.specificity());
        assertEquals(11, withPseudoClass.specificity());
    }

    @Test
    public void anUnsupportedPseudoElementIsRejectedAtParseTime() {
        try {
            com.crystalgui.style.selector.Selector.parse("text::before");
            fail("::before has no equivalent here and must not silently never match");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("::highlight"));
        }
    }

    // ── The spec's property restriction ──────────────────────────────────────

    /**
     * <b>Layout-affecting properties are refused, and that is the whole point of the mechanism.</b>
     *
     * <p>CSS Pseudo-Elements 4 restricts highlight pseudo-elements to properties that <i>"do not affect
     * layout"</i>. It is not a simplification we chose: a highlight that could set {@code font-size}
     * would reflow the text it highlights, so typing in a search box would reshuffle the lines being
     * searched. Dropping the declaration with a warning beats accepting one that does nothing.</p>
     */
    @Test
    public void layoutAffectingPropertiesAreRefused() {
        UIText text = build(SENTENCE, "text::highlight(kw) { color: #FF0000; font-size: 40px; }");
        var style = document.styleEngine().highlightStyle(text, "kw");

        assertEquals("the allowed half still applies", 0xFFFF0000, style.color(0));
        assertEquals("and the element's own font-size is untouched",
                16f, text.getStyle().getGeneralGroup().fontSize(), 0.01f);
    }

    /**
     * <b>{@code font-style} and {@code font-weight} on a highlight are allowed here, and CSS forbids
     * them.</b> A deliberate divergence, argued in full at {@code HighlightStyle.ALLOWED}.
     *
     * <p>The spec's rule protects a property this engine does not have: on the web a highlight is a pure
     * overlay painted over already-laid-out text, so a wider face would move the very glyphs being
     * highlighted. Here a highlight <em>already</em> re-shapes — a span boundary is a shaping-run
     * boundary — so the premise is false for us, and refusing them would mean an editor colour scheme
     * that cannot say what IntelliJ's, VS Code's and Zed's all say. Every reference scheme italicises
     * comments.</p>
     */
    @Test
    public void aHighlightMaySetItalicAndBold() {
        UIText text = build(SENTENCE,
                "text::highlight(comment) { font-style: italic; }"
                        + "text::highlight(kw) { font-weight: bold; }");

        var comment = document.styleEngine().highlightStyle(text, "comment");
        assertTrue("italic must survive the highlight filter", comment.isItalic(false));
        assertFalse("and must not drag bold along with it", comment.isBold(false));

        var keyword = document.styleEngine().highlightStyle(text, "kw");
        assertTrue(keyword.isBold(false));
    }

    /**
     * A highlight silent about weight must not make its range lighter than the text around it — otherwise
     * a search match inside a bold label goes thin for exactly the characters it found.
     */
    @Test
    public void aHighlightThatSaysNothingAboutWeightInheritsTheElements() {
        UIText text = build(SENTENCE, "text::highlight(hit) { color: #FF0000; }");
        var style = document.styleEngine().highlightStyle(text, "hit");

        assertTrue("bold carries through a highlight that does not mention it", style.isBold(true));
        assertTrue("and so does italic", style.isItalic(true));
    }

    @Test
    public void decorationWithoutColourLeavesTheTextReadable() {
        UIText text = build(SENTENCE, "text::highlight(hit) { text-decoration-line: underline; }");
        var style = document.styleEngine().highlightStyle(text, "hit");

        assertEquals(1, style.decorations().size());
        assertEquals("a highlight that sets no colour inherits the element's",
                0xFF00FF00, style.color(0xFF00FF00));
    }

    /**
     * <b>{@code background-color} on a highlight is paintable now, and this test flipped as intended.</b>
     *
     * <p>It used to assert the opposite, with a javadoc saying "this test is meant to flip. When
     * per-range geometry lands, move {@code BACKGROUND_COLOR} from {@code NOT_YET_PAINTABLE} to
     * {@code ALLOWED} and invert this assertion." That is what happened — and the geometry it was waiting
     * for turned out to need no new machinery at all: shaping already breaks a run at every span
     * boundary, so a highlighted range <em>is</em> one or more {@code CgShapedRun}s and each carries its
     * own source range and advance. {@code UIText.paintHighlightBands} walks them.</p>
     *
     * <p>{@code text-shadow} stays refused, and for the reason that always applied to it rather than to
     * both: it is a second <em>draw</em> of one range, not a rect behind it.</p>
     */
    @Test
    public void backgroundColorOnAHighlightIsPaintable() {
        assertTrue("background-color must be in ALLOWED now",
                com.crystalgui.style.HighlightStyle.ALLOWED
                        .contains(com.crystalgui.style.property.StylePropertyRegistry.BACKGROUND_COLOR));
        assertFalse("and must no longer be listed as unpaintable",
                com.crystalgui.style.HighlightStyle.NOT_YET_PAINTABLE
                        .contains(com.crystalgui.style.property.StylePropertyRegistry.BACKGROUND_COLOR));

        UIText text = build(SENTENCE,
                "text::highlight(hit) { background-color: #5A4A00; text-decoration-line: underline; }");
        var style = document.styleEngine().highlightStyle(text, "hit");

        assertEquals("the decoration still applies", 1, style.decorations().size());
        assertEquals("and the band reaches the resolved style", 0xFF5A4A00, style.backgroundColor());
    }

    /** No rule, no band — and zero rather than a null, so a painter needs no null check. */
    @Test
    public void anUnstyledHighlightHasNoBand() {
        UIText text = build(SENTENCE, "text::highlight(hit) { color: #FF0000; }");
        assertEquals(0, document.styleEngine().highlightStyle(text, "hit").backgroundColor());
    }

    /** {@code text-shadow} is still a second draw of a range, which a span cannot express. */
    @Test
    public void textShadowOnAHighlightIsStillRefused() {
        assertTrue("text-shadow remains unpaintable — see HighlightStyle",
                com.crystalgui.style.HighlightStyle.NOT_YET_PAINTABLE
                        .contains(com.crystalgui.style.property.StylePropertyRegistry.TEXT_SHADOW));
    }

    /** CSS spells strikethrough `line-through`, and multiple keywords are legal in one declaration. */
    @Test
    public void textDecorationLineParsesCssKeywordsAndCombinations() {
        UIText text = build(SENTENCE,
                "text::highlight(kw) { text-decoration-line: underline line-through; }");
        assertEquals(2, document.styleEngine().highlightStyle(text, "kw").decorations().size());
    }

    // ── The registry ─────────────────────────────────────────────────────────

    @Test
    public void rangesRoundTripAndClear() {
        UIText text = build(SENTENCE, "text::highlight(kw) { color: #FF0000; }");
        assertTrue(text.highlights().isEmpty());

        text.highlights().set("kw", TextRange.of(4, 9));
        settle();
        assertEquals(List.of(TextRange.of(4, 9)), text.highlights().get("kw"));

        text.highlights().clear();
        settle();
        assertTrue(text.highlights().isEmpty());
    }

    /** Overlap within one name is ambiguous — two styles for one character under a single rule. Across
     * names it is allowed and resolved by registration order. */
    @Test
    public void overlappingRangesWithinOneNameAreRejected() {
        UIText text = build(SENTENCE, null);
        try {
            text.highlights().set("kw", TextRange.of(0, 6), TextRange.of(3, 9));
            fail("overlapping ranges under one name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("overlap"));
        }
    }

    /** A name no stylesheet mentions is legal and inert — a highlighter must not need to know the
     * theme. Critically it must also leave the element on the unspanned shaping path. */
    @Test
    public void anUnstyledHighlightNameIsInert() {
        UIText text = build(SENTENCE, null);
        text.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP));
        settle();
        float plainWidth = text.box().width();

        text.highlights().set("nothing-styles-this", TextRange.of(4, 9));
        settle();
        settle();

        assertEquals(plainWidth, text.box().width(), 0f);
    }

    /**
     * <b>Un-highlighted text stays on the unspanned shaping path.</b>
     *
     * <p>Because we re-shape rather than overlaying (see {@code UIText}'s known-divergence note), a span
     * boundary is a shaping-run boundary and separately-shaped runs lose the kerning across them. Routing
     * plain text through a one-span document would therefore shift the measured width of every existing
     * label in the engine. Applying highlights and clearing them must land back on exactly the original
     * number.</p>
     */
    @Test
    public void applyingAndClearingHighlightsRestoresTheExactPlainWidth() {
        UIText text = build(SENTENCE, "text::highlight(kw) { color: #FF0000; }");
        text.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP));
        settle();
        float plainWidth = text.box().width();
        assertTrue("fixture must actually have measured something", plainWidth > 0f);

        text.highlights().set("kw", TextRange.of(4, 9));
        settle();
        settle();

        text.highlights().clear();
        settle();
        settle();

        assertEquals(plainWidth, text.box().width(), 0f);
    }

    // ── The two crash paths ──────────────────────────────────────────────────

    /** Text and ranges are set from two places, so one is always second. Shortening the text below a
     * registered range must clip, not throw from inside a later paint. */
    @Test
    public void shorteningTheTextClipsItsRangesInsteadOfThrowing() {
        UIText text = build(SENTENCE, "text::highlight(kw) { color: #FF0000; }");
        text.highlights().set("kw", TextRange.of(10, 19));
        settle();

        text.setText("short");
        settle();
        settle();

        assertEquals("short", text.getText());
        assertTrue("still laid out rather than having thrown", heightOf(text) > 0f);
    }

    /** The same hazard from the other side: `text-overflow: ellipsis` paints a prefix. */
    @Test
    public void truncatedTextClipsItsRanges() {
        UIText text = build("a considerably longer sentence than will ever fit in the box",
                "text::highlight(kw) { color: #FF0000; }");
        text.layout(l -> l.width(60));
        text.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP).textOverflow(TextOverflow.ELLIPSIS));
        text.highlights().set("kw", TextRange.of(30, 50));
        settle();
        settle();

        assertNotEquals("fixture must genuinely be truncating", text.getText(), text.displayedText());
        assertTrue("and the range straddling the cut must not have thrown",
                heightOf(text) > 0f);
    }

    // ── TextRange ────────────────────────────────────────────────────────────

    @Test
    public void anEmptyRangeIsRejected() {
        try {
            TextRange.of(4, 4);
            fail("a zero-length range highlights nothing and is always a mistake");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("non-empty"));
        }
    }

    @Test
    public void clippingDropsShortensOrKeeps() {
        assertNull("entirely past the cut", TextRange.of(10, 14).clippedTo(10));
        assertEquals("straddling the cut", 8, TextRange.of(4, 14).clippedTo(8).end());
        TextRange untouched = TextRange.of(0, 4);
        assertSame("entirely before it — must not even copy", untouched, untouched.clippedTo(10));
    }

    /**
     * <b>A band must not outlive the highlight that asked for it.</b>
     *
     * <p>The per-character band array was assigned only on the path that <em>has</em> highlights, so the
     * two early returns for "nothing to style" left the previous one in place. That is not a stale style:
     * an unhighlighted label shapes as a single run starting at character 0, and the band pass reads the
     * run's first character, so one leftover entry at index 0 paints across the entire string.</p>
     *
     * <p>Rows are pooled, so in the explorer every row element that had ever shown a match went on banding
     * whatever filename landed on it next — full width, for a query that matched one file. Nothing else
     * was wrong: the registered range was empty, the count said "1 of 1", and only the paint disagreed,
     * which is exactly why {@link UIText#highlightBandCount()} exists to be asserted on.</p>
     */
    @Test
    public void aBandIsClearedWhenItsHighlightGoesAway() {
        UIText text = build("mama.glsl", "text::highlight(find-match) { background-color: #C8873C; }");
        text.highlights().set("find-match", java.util.List.of(TextRange.of(0, 4)));
        settle();
        settle();
        assertEquals("the band should cover exactly the query span", 4, text.highlightBandCount());

        // What recycling does: a new name, and no match this time.
        text.highlights().remove("find-match");
        text.setText("gradle.properties");
        settle();
        settle();
        assertEquals("a row with no match must carry no band", 0, text.highlightBandCount());
    }

    /** The same, for a label whose text never changes — only the highlight is withdrawn. */
    @Test
    public void withdrawingAHighlightClearsTheBandWithoutRetyping() {
        UIText text = build("mama.glsl", "text::highlight(find-match) { background-color: #C8873C; }");
        text.highlights().set("find-match", java.util.List.of(TextRange.of(0, 4)));
        settle();
        settle();
        assertEquals(4, text.highlightBandCount());

        text.highlights().remove("find-match");
        settle();
        settle();
        assertEquals(0, text.highlightBandCount());
    }

}
