package com.crystalgui.ui;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.text.FontStyle;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code font-weight} and {@code font-style} — the two face properties.
 *
 * <h3>What is worth asserting, and what is not</h3>
 *
 * <p>Not the pixels. Synthetic bold is a rasteriser-side embolden and there is no honest way to assert
 * "these glyphs look heavier" from here. What <em>is</em> assertable is the thing that actually broke
 * for every other property of this kind in this codebase: whether the value reaches the shaper at all.
 * {@code UIText.styleSpanCount()} exists for exactly that reason — it is the only observable that a
 * declaration reached the paint rather than merely resolving through the cascade — and it is what
 * caught a {@code text-decoration-line} that had never once drawn.</p>
 *
 * <p>The measurement assertions matter for the same reason from the other end: an embolden makes text
 * WIDER, so if the weight reaches the glyphs but not the measurement, a bold label is sized for the
 * thin version and truncates inside its own box.</p>
 */
public class FontFaceTest extends UiTestBase {

    private UIWindow window;

    private UIText build(String content, java.util.function.Consumer<UIText> configure) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIText text = new UIText(content);
        configure.accept(text);
        root.addChild(text);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 800);
        settle();
        settle();
        return text;
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    @Test
    public void keywordsParse() {
        StyleSheet sheet = StyleSheet.parse("text { font-weight: bold; font-style: italic; }");
        assertEquals(1, sheet.getRules().size());
    }

    /**
     * The numeric scale is accepted and mapped, because authors write it. 600 is CSS Fonts 4's own
     * boundary for "the bold face" in a family with nothing between 400 and 700 — which is every family
     * this engine loads.
     */
    @Test
    public void theNumericScaleMapsOntoTheTwoAvailableFaces() {
        assertEquals(FontWeight.NORMAL, FontWeight.ofNumeric(100));
        assertEquals(FontWeight.NORMAL, FontWeight.ofNumeric(400));
        assertEquals(FontWeight.NORMAL, FontWeight.ofNumeric(500));
        assertEquals(FontWeight.BOLD, FontWeight.ofNumeric(600));
        assertEquals(FontWeight.BOLD, FontWeight.ofNumeric(700));
        assertEquals(FontWeight.BOLD, FontWeight.ofNumeric(900));
    }

    /**
     * An out-of-range number is REFUSED rather than clamped, and the refusal degrades to "no value"
     * rather than propagating — {@code StyleValue.compute} catches, warns and yields null, which is what
     * keeps one malformed declaration from breaking the sheet around it.
     */
    @Test
    public void anOutOfRangeWeightIsRefusedRatherThanClamped() {
        assertNull(new com.crystalgui.style.property.visual.text.FontWeightValue("1200").compute());
        assertNull(new com.crystalgui.style.property.visual.text.FontWeightValue("nonsense").compute());
    }

    /** {@code oblique} is kept distinct from {@code italic} in the enum and resolves the same today. */
    @Test
    public void obliqueIsItsOwnValueAndStillSlants() {
        assertNotEquals(FontStyle.ITALIC, FontStyle.OBLIQUE);
        assertTrue(FontStyle.OBLIQUE.isItalic());
        assertTrue(FontStyle.ITALIC.isItalic());
        assertTrue(!FontStyle.NORMAL.isItalic());
    }

    // ── it reaches the shaper ───────────────────────────────────────────────

    /**
     * <b>The load-bearing one.</b> A plain label must stay on the unspanned shaping path — AGENTS.md
     * records that routing ordinary text through a one-span document shifts every label in the engine by
     * a fraction of a pixel.
     */
    @Test
    public void anOrdinaryLabelUsesNoSpansAtAll() {
        UIText text = build("hello", t -> { });
        assertEquals("a label with no face properties must not take the styled path",
                0, text.styleSpanCount());
    }

    /** And a bold one does — which is the only evidence the weight reached the shaper. */
    @Test
    public void aBoldLabelEmitsASpanCoveringItsWholeText() {
        UIText text = build("hello", t -> t.generalStyle(g -> g.fontWeight(FontWeight.BOLD)));
        assertEquals("font-weight resolved but never reached the shaper", 1, text.styleSpanCount());
    }

    @Test
    public void anItalicLabelEmitsASpanToo() {
        UIText text = build("hello", t -> t.generalStyle(g -> g.fontStyle(FontStyle.ITALIC)));
        assertEquals(1, text.styleSpanCount());
    }

    // ── geometry ────────────────────────────────────────────────────────────

    /**
     * Emboldening widens the glyphs, and the element has to be measured at the weight it paints — else
     * the box is sized for the thin version and the text truncates inside it.
     *
     * <p>Asserted as "at least as wide", not "wider": whether a given font's synthetic bold actually adds
     * advance is the rasteriser's business and a bitmap font may add none. What must never happen is the
     * bold label measuring NARROWER than the regular one, which is what a measurement path that ignored
     * the weight would produce as soon as the two shaping paths diverged.</p>
     */
    @Test
    public void aBoldLabelIsMeasuredAtItsOwnWeight() {
        float regular = build("wwwwwwwwww", t -> { }).getRuntimeCache().getWidth();
        float bold = build("wwwwwwwwww", t -> t.generalStyle(g -> g.fontWeight(FontWeight.BOLD)))
                .getRuntimeCache().getWidth();
        assertTrue("bold measured NARROWER than regular (" + bold + " < " + regular + "),"
                + " which means the measurement path ignored the weight", bold >= regular - 0.5f);
    }

    // ── inheritance ─────────────────────────────────────────────────────────

    /**
     * <b>This one genuinely inherits, unlike {@code font-size}.</b> Inheritance applies only where there
     * is no candidate at any origin, and the user-agent sheet's {@code * { font-size: 10 }} puts one on
     * every element — which is why a {@code font-size} on a wrapper never reaches the label inside it.
     * Nothing writes a universal {@code font-weight}, so this one does reach.
     *
     * <p>Worth pinning precisely because it is the asymmetry someone will later "fix" by adding a
     * universal rule, which would silently disable this.</p>
     */
    @Test
    public void weightInheritsThroughAWrapperEvenThoughFontSizeCannot() {
        UIElement wrapper = new UIElement();
        UIText label = new UIText("hi");
        wrapper.addChild(label);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(wrapper);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 800);
        wrapper.generalStyle(g -> g.fontWeight(FontWeight.BOLD));
        settle();
        settle();

        assertEquals("font-weight must inherit to the label inside a wrapper",
                FontWeight.BOLD, label.getStyle().getGeneralGroup().fontWeight());
        assertEquals("and it must reach the shaper from there", 1, label.styleSpanCount());
    }

    // ── registration ────────────────────────────────────────────────────────

    /** Both are reachable by CSS name — the thing that makes them parseable in a stylesheet at all. */
    @Test
    public void bothAreRegisteredUnderTheirCssNames() {
        assertEquals(StylePropertyRegistry.FONT_WEIGHT, StylePropertyRegistry.byName("font-weight"));
        assertEquals(StylePropertyRegistry.FONT_STYLE, StylePropertyRegistry.byName("font-style"));
    }
}
