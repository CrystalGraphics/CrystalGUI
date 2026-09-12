package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.DeclarationParser;
import com.crystalgui.style.sheet.StyleRule;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The CSS surface of a text outline: the {@code text-stroke} shorthand, and the one thing about
 * these properties that has already been got wrong once.
 */
public class TextStrokeStyleTest {

    // ── the shorthand ────────────────────────────────────────────────────────────────────────

    @Test
    public void shorthandSplitsWidthAndColour() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: 2px #0B5D8F;");

        assertEquals(2, out.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH, out.get(0).property());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(1).property());
        assertEquals((Integer) 0xFF0B5D8F, out.get(1).value().compute());
    }

    @Test
    public void shorthandIsOrderIndependent() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: #0B5D8F 2px;");

        assertEquals(2, out.size());
        // Emitted width-then-colour whatever order they were written in, as CSS shorthands are.
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH, out.get(0).property());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(1).property());
    }

    @Test
    public void shorthandTakesEitherHalfAlone() {
        List<StyleRule.Declaration> widthOnly = DeclarationParser.parseBlock("text-stroke: 1px;");
        assertEquals(1, widthOnly.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH, widthOnly.get(0).property());

        List<StyleRule.Declaration> colourOnly = DeclarationParser.parseBlock("text-stroke: #FF0000;");
        assertEquals(1, colourOnly.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, colourOnly.get(0).property());
    }

    @Test
    public void shorthandNoneZeroesTheWidthAndLeavesTheColourAlone() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: none;");

        assertEquals(1, out.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH, out.get(0).property());
        assertEquals(LengthPercent.ZERO, out.get(0).value().compute());
    }

    /**
     * A colour function's own spaces must survive the shorthand's tokenizer. Splitting on
     * whitespace tears {@code rgb(11, 93, 143)} into three pieces, none of which reads as a colour
     * or a width, and all three are then dropped without a word.
     */
    @Test
    public void shorthandKeepsAColourFunctionWhole() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: 2px rgb(11, 93, 143);");

        assertEquals(2, out.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(1).property());
        assertEquals((Integer) 0xFF0B5D8F, out.get(1).value().compute());
    }

    /**
     * {@code text-stroke} is the only spelling a sheet may write. The two halves still exist, still
     * cascade and still serialise under their own names — they are simply not authorable, so an
     * author has one thing to learn and the engine keeps the two values a partial override needs.
     */
    @Test
    public void theLonghandsAreNotWritableBytheirOwnNames() {
        List<StyleRule.Declaration> out =
                DeclarationParser.parseBlock("text-stroke-width: 3px; text-stroke-color: #FF0000;");
        assertTrue("both longhands must be refused by name", out.isEmpty());

        assertEquals("text-stroke", StylePropertyRegistry.TEXT_STROKE_WIDTH.getAuthoredThrough());
        assertEquals("text-stroke", StylePropertyRegistry.TEXT_STROKE_COLOR.getAuthoredThrough());
        // Still resolvable by name, which is what the wire codec and the cascade both need.
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH,
                StylePropertyRegistry.byName("text-stroke-width"));
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR,
                StylePropertyRegistry.byName("text-stroke-color"));
    }

    /** A partial override is the whole reason the two halves survive under the shorthand. */
    @Test
    public void aColourOnlyDeclarationLeavesTheWidthAlone() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: #FF0000;");

        assertEquals(1, out.size());
        assertSame("stating only a colour must not emit a width, or a hover rule would zero it",
                StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(0).property());
    }

    @Test
    public void transitionOnTheShorthandNameReachesBothLonghands() {
        assertTrue(TextStrokeShorthand.transitionNameMatches(
                "text-stroke", StylePropertyRegistry.TEXT_STROKE_WIDTH));
        assertTrue(TextStrokeShorthand.transitionNameMatches(
                "text-stroke", StylePropertyRegistry.TEXT_STROKE_COLOR));
        assertTrue("both longhands allow transition, or the shorthand animates nothing",
                StylePropertyRegistry.TEXT_STROKE_WIDTH.isAllowTransition()
                        && StylePropertyRegistry.TEXT_STROKE_COLOR.isAllowTransition());
    }

    // ── units ────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code em} and {@code %} are the SAME unit for a stroke width, because the percentage already
     * resolves against the font size rather than against a box. {@code px} is the odd one out: it is
     * absolute, so it keeps its thickness while the text scales.
     */
    @Test
    public void emAndPercentAreTheSameWidth() {
        assertEquals(LengthPercent.percent(0.04f), width("0.04em"));
        assertEquals(LengthPercent.percent(0.04f), width("4%"));
        assertEquals("em must fold to a percentage, never to pixels — there is no font size at parse "
                        + "time, and the consumer supplies one as the resolve axis",
                width("4%"), width("0.04em"));
    }

    @Test
    public void pxIsAbsoluteAndNotTheSameAsEm() {
        assertEquals(LengthPercent.px(2f), width("2px"));
        assertEquals("a bare number is px, as everywhere else", LengthPercent.px(2f), width("2"));
        // 2px only equals 0.028em at font-size 72 -- the point is that the two are different KINDS.
        assertNotEquals(width("2px"), width("2em"));
    }

    /** The shared parser must NOT learn em: its percentages are fractions of a box everywhere else. */
    @Test
    public void theSharedLengthParserStillRefusesEm() {
        assertNull("border-radius: 1em would have to mean a whole box width",
                LengthPercent.parse("1em"));
    }

    private static LengthPercent width(String value) {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: " + value + ";");
        assertEquals("expected one width declaration for " + value, 1, out.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_WIDTH, out.get(0).property());
        return (LengthPercent) out.get(0).value().compute();
    }

    // ── the collision that has already cost a session ────────────────────────────────────────

    /**
     * <b>A fully transparent stroke colour is indistinguishable from an unset one BY VALUE.</b>
     *
     * <p>{@code text-stroke-color} and {@code text-fill-color} both take {@code 0} as their initial,
     * and {@code #00000000} parses to exactly {@code 0} — so "did the author write this?" cannot be
     * answered by comparing against the initial, and {@code UIText} asks
     * {@code ComputedStyle.isSet} instead. What makes that work is the declaration surviving the
     * cascade as a candidate even though its value equals the initial, which is what this pins: kill
     * it and a transparent outline silently becomes an inherited-colour one.</p>
     */
    @Test
    public void aTransparentStrokeColourIsStillADeclaration() {
        Integer transparent = ColorValue.parseColor("#00000000");
        assertEquals("the premise: the parsed value IS the initial", transparent,
                StylePropertyRegistry.TEXT_STROKE_COLOR.initialValue);
        assertEquals((Integer) 0, transparent);

        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: #00000000;");
        assertEquals(1, out.size());
        assertEquals("a value equal to the initial is still a real value, not a parse failure",
                (Integer) 0, out.get(0).value().compute());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(0).property());
    }

    /** {@code transparent} is the same colour by another spelling, and must behave the same way. */
    @Test
    public void theTransparentKeywordIsTheSameColour() {
        assertEquals((Integer) 0, ColorValue.parseColor("transparent"));

        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("text-stroke: transparent;");
        assertEquals(1, out.size());
        assertSame(StylePropertyRegistry.TEXT_STROKE_COLOR, out.get(0).property());
    }

    // ── keywords ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void strokeAlignKeywordsParse() {
        assertEquals(StrokeAlign.OUTSET, parseAlign("outset"));
        assertEquals(StrokeAlign.CENTER, parseAlign("center"));
        assertEquals(StrokeAlign.INSET, parseAlign("inset"));
        assertEquals("outset is the initial — an outline that eats the letterform is never the "
                        + "default", StrokeAlign.OUTSET,
                StylePropertyRegistry.STROKE_ALIGN.initialValue);
    }

    @Test
    public void paintOrderKeywordsParse() {
        List<StyleRule.Declaration> normal = DeclarationParser.parseBlock("paint-order: normal;");
        assertEquals(PaintOrder.NORMAL, normal.get(0).value().compute());

        List<StyleRule.Declaration> stroke = DeclarationParser.parseBlock("paint-order: stroke;");
        assertEquals(PaintOrder.STROKE, stroke.get(0).value().compute());

        assertEquals("normal is the initial: the fill paints over the stroke", PaintOrder.NORMAL,
                StylePropertyRegistry.PAINT_ORDER.initialValue);
    }

    /**
     * A malformed keyword must compute to null, which is what the cascade refuses to turn into a
     * slot — so the property degrades to its initial rather than being poisoned with a null.
     */
    @Test
    public void anUnknownStrokeKeywordComputesToNull() {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("stroke-align: sideways;");
        for (StyleRule.Declaration declaration : out) {
            assertNull("a keyword no enum constant matches must not compute to a value",
                    declaration.value().compute());
        }
    }

    private static StrokeAlign parseAlign(String keyword) {
        List<StyleRule.Declaration> out = DeclarationParser.parseBlock("stroke-align: " + keyword + ";");
        assertEquals(1, out.size());
        return (StrokeAlign) out.get(0).value().compute();
    }
}
