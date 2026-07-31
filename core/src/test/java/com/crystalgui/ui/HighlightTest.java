package com.crystalgui.ui;

import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

import java.util.List;

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
public class HighlightTest extends UiTestBase {

    private static final String SENTENCE = "the quick brown fox";

    private UIWindow window;
    private UIElement root;

    private UIText build(String content, String css) {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIText text = new UIText(content);
        root.addChild(text);
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        if (css != null) window.getStyleEngine().addStylesheet(StyleSheet.parse(css));
        settle();
        settle();
        return text;
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
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
        var style = window.getStyleEngine().highlightStyle(text, "kw");
        assertFalse(style.isEmpty());
        assertEquals(0xFFFF0000, style.color(0));
        assertTrue("an unstyled name resolves empty rather than null",
                window.getStyleEngine().highlightStyle(text, "nope").isEmpty());
    }

    /** Descendant combinators still work: the pseudo-element only ever sits on the rightmost compound. */
    @Test
    public void ancestorsStillConstrainTheMatch() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement scoped = new UIElement();
        scoped.addClass("code");
        UIText inside = new UIText(SENTENCE);
        UIText outside = new UIText(SENTENCE);
        scoped.addChild(inside);
        root.addChild(scoped);
        root.addChild(outside);
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".code text::highlight(kw) { color: #FF0000; }"));
        settle();

        assertFalse(window.getStyleEngine().highlightStyle(inside, "kw").isEmpty());
        assertTrue("outside the .code subtree it must not apply",
                window.getStyleEngine().highlightStyle(outside, "kw").isEmpty());
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
        var style = window.getStyleEngine().highlightStyle(text, "kw");

        assertEquals("the allowed half still applies", 0xFFFF0000, style.color(0));
        assertEquals("and the element's own font-size is untouched",
                16f, text.getStyle().getGeneralGroup().fontSize(), 0.01f);
    }

    @Test
    public void decorationWithoutColourLeavesTheTextReadable() {
        UIText text = build(SENTENCE, "text::highlight(hit) { text-decoration-line: underline; }");
        var style = window.getStyleEngine().highlightStyle(text, "hit");

        assertEquals(1, style.decorations().size());
        assertEquals("a highlight that sets no colour inherits the element's",
                0xFF00FF00, style.color(0xFF00FF00));
    }

    /**
     * <b>{@code background-color} is valid CSS on a highlight and is refused anyway, because we cannot
     * paint it.</b>
     *
     * <p>A band behind a character range needs per-range rects from the text layout; {@code CgStyleSpan}
     * carries colour and decorations and nothing positional. Accepting the declaration and dropping it
     * would give an author a rule that looks correct and does nothing — the exact fault
     * {@code CgStyleSpan}'s own javadoc records about three fields that were carried and then ignored.
     * The engine logs it as "not implemented" rather than "not allowed", since the spec sides with the
     * author here.</p>
     *
     * <p><b>This test is meant to flip.</b> When per-range geometry lands — alongside 6.1.6's caret,
     * which needs the same machinery — move {@code BACKGROUND_COLOR} from {@code NOT_YET_PAINTABLE} to
     * {@code ALLOWED} and invert this assertion. Failing then is the point.</p>
     */
    @Test
    public void backgroundColorIsRefusedUntilItCanBePainted() {
        assertTrue("still unpaintable — see the javadoc before changing this",
                com.crystalgui.style.HighlightStyle.NOT_YET_PAINTABLE
                        .contains(com.crystalgui.style.property.StylePropertyRegistry.BACKGROUND_COLOR));

        UIText text = build(SENTENCE,
                "text::highlight(hit) { background-color: #5A4A00; text-decoration-line: underline; }");
        var style = window.getStyleEngine().highlightStyle(text, "hit");

        assertEquals("the paintable half still applies", 1, style.decorations().size());
        assertTrue("and the unpaintable one never reached the resolved style",
                style.get(com.crystalgui.style.property.StylePropertyRegistry.BACKGROUND_COLOR, -1) == -1);
    }

    /** CSS spells strikethrough `line-through`, and multiple keywords are legal in one declaration. */
    @Test
    public void textDecorationLineParsesCssKeywordsAndCombinations() {
        UIText text = build(SENTENCE,
                "text::highlight(kw) { text-decoration-line: underline line-through; }");
        assertEquals(2, window.getStyleEngine().highlightStyle(text, "kw").decorations().size());
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
        float plainWidth = text.getRuntimeCache().getWidth();

        text.highlights().set("nothing-styles-this", TextRange.of(4, 9));
        settle();
        settle();

        assertEquals(plainWidth, text.getRuntimeCache().getWidth(), 0f);
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
        float plainWidth = text.getRuntimeCache().getWidth();
        assertTrue("fixture must actually have measured something", plainWidth > 0f);

        text.highlights().set("kw", TextRange.of(4, 9));
        settle();
        settle();

        text.highlights().clear();
        settle();
        settle();

        assertEquals(plainWidth, text.getRuntimeCache().getWidth(), 0f);
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
        assertTrue("still laid out rather than having thrown", text.getRuntimeCache().getHeight() > 0f);
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
                text.getRuntimeCache().getHeight() > 0f);
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
}
