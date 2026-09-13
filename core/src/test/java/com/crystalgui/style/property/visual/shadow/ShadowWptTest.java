package com.crystalgui.style.property.visual.shadow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The Web Platform Tests for {@code text-shadow}, ported case for case.
 *
 * <ul>
 *   <li>{@code css/css-text-decor/text-shadow/parsing/text-shadow-valid.html}</li>
 *   <li>{@code css/css-text-decor/text-shadow/parsing/text-shadow-invalid.html}</li>
 *   <li>{@code css/css-text-decor/text-shadow/parsing/text-shadow-computed.html}</li>
 *   <li>{@code css/css-transitions/animations/text-shadow-interpolation.html}</li>
 * </ul>
 *
 * <p><b>Skipped:</b> the {@code calc()} cases, since no length here parses {@code calc()}. The em
 * resolution they exercise is covered with plain {@code em} instead.</p>
 *
 * <p><b>Inverted:</b> {@code 10px 20px 30px 40px} is invalid in Level 3 and valid in Level 4, which is
 * the grammar {@code text-shadow} registers. Both are asserted.</p>
 */
public class ShadowWptTest {

    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF008000;

    private static ShadowList l3(String raw) {
        return ShadowList.parse(raw, ShadowGrammar.TEXT_LEVEL_3);
    }

    private static ShadowList l4(String raw) {
        return ShadowList.parse(raw, ShadowGrammar.TEXT_LEVEL_4);
    }

    /** What getComputedStyle prints for one shadow, with currentcolor resolved against {@code color}. */
    private static String computed(Shadow s, int color) {
        int argb = s.color().resolve(color);
        int a = argb >>> 24;
        String c = a == 255
                ? String.format("rgb(%d, %d, %d)", (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)
                : String.format("rgba(%d, %d, %d, %s)", (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                        a == 0 ? "0" : trim(Math.round(a / 255f * 100f) / 100f));
        return c + " " + len(s.x()) + " " + len(s.y()) + " " + len(s.blur());
    }

    private static String len(float px) {
        return trim(Math.round(px * 10f) / 10f) + "px";
    }

    private static String trim(float v) {
        return v == (long) v ? Long.toString((long) v) : Float.toString(v);
    }

    // ── text-shadow-valid.html ───────────────────────────────────────────────────────────────────

    @Test
    public void validValues() {
        for (String raw : new String[]{
                "none", "10px 20px", "10px 20px 30px", "-10px 20px 30px", "10px -20px 30px",
                "rgb(255, 0, 0) 10px 20px", "10px 20px 30px lime", "10px 20px, 30px 40px",
                "lime 10px 20px 30px, blue 40px 50px"}) {
            assertNotNull(raw, l3(raw));
            assertNotNull(raw, l4(raw));
        }
    }

    @Test
    public void colourAfterTheLengthsSerialisesFirst() {
        ShadowList list = l3("10px 20px 30px lime");
        assertEquals(l3("lime 10px 20px 30px"), list);
        assertEquals("#00FF00FF 10px 20px 30px", ShadowParser.write(list));
    }

    // ── text-shadow-invalid.html ─────────────────────────────────────────────────────────────────

    @Test
    public void invalidValues() {
        for (String raw : new String[]{
                "auto", "10px 20px -30px", "10px", "red 10px 20px blue",
                "10% 20px", "10px 20% 30px", "lime 10px 20px 30%"}) {
            assertNull(raw, l3(raw));
            assertNull(raw, l4(raw));
        }
    }

    @Test
    public void aFourthLengthIsLevel4Only() {
        assertNull(l3("10px 20px 30px 40px"));
        assertNotNull(l4("10px 20px 30px 40px"));
        assertNull("a text spread may not be negative", l4("10px 20px 30px -40px"));
        assertNotNull("a box spread may", ShadowList.parse("10px 20px 30px -40px", ShadowGrammar.BOX));
    }

    @Test
    public void insetIsLevel4Only() {
        assertNull(l3("inset 1px 1px"));
        assertNotNull(l4("inset 1px 1px"));
        assertNotNull(l4("1px 1px red inset"));
        assertNotNull(l4("red inset 1px 1px"));
        assertNull("a second inset", l4("inset 1px 1px inset"));
    }

    @Test
    public void dropShadowTakesOneShadowWithoutSpreadOrNone() {
        assertNotNull(ShadowList.parse("1px 2px 3px red", ShadowGrammar.DROP_SHADOW));
        assertNull(ShadowList.parse("1px 2px, 3px 4px", ShadowGrammar.DROP_SHADOW));
        assertNull(ShadowList.parse("1px 2px 3px 4px", ShadowGrammar.DROP_SHADOW));
        assertNull(ShadowList.parse("none", ShadowGrammar.DROP_SHADOW));
    }

    // ── text-shadow-computed.html, at font-size: 40px; color: blue ───────────────────────────────

    @Test
    public void computedValues() {
        assertEquals(ShadowList.NONE, l3("none"));
        assertEquals("rgb(0, 0, 255) 10px 20px 0px", computed(l3("10px 20px").get(0), BLUE));
        assertEquals("rgb(255, 0, 0) 10px 20px 30px", computed(l3("red 10px 20px 30px").get(0), BLUE));

        ShadowList two = l3("10px 20px, 30px 40px");
        assertEquals("rgb(0, 0, 255) 10px 20px 0px", computed(two.get(0), BLUE));
        assertEquals("rgb(0, 0, 255) 30px 40px 0px", computed(two.get(1), BLUE));

        ShadowList mixed = l3("lime 10px 20px 30px, red 40px 50px");
        assertEquals("rgb(0, 255, 0) 10px 20px 30px", computed(mixed.get(0), BLUE));
        assertEquals("rgb(255, 0, 0) 40px 50px 0px", computed(mixed.get(1), BLUE));
    }

    /** The calc() cases' em half, at the test's 40px: an em computes to pixels at the declaring element. */
    @Test
    public void emComputesAgainstTheElementsFontSize() {
        ShadowValue value = new ShadowValue("0.5em 0.25em 0.5em", ShadowGrammar.TEXT_LEVEL_3);
        Shadow s = value.resolveAgainst(40f).get(0);
        assertEquals("rgb(0, 0, 255) 20px 10px 20px", computed(s, BLUE));
    }

    // ── text-shadow-interpolation.html: .parent 30px 10px 30px orange; .target 10px 30px 10px orange,
    //    color green ─────────────────────────────────────────────────────────────────────────────

    private static void expect(String from, String to, float at, String expected) {
        Shadow s = ShadowList.interpolate(l3(from), l3(to), at).get(0);
        assertEquals(from + " -> " + to + " at " + at, expected, computed(s, GREEN));
    }

    @Test
    public void interpolationFromTheNeutralKeyframe() {
        String underlying = "orange 10px 30px 10px";
        String to = "green 20px 20px 20px";
        expect(underlying, to, -0.3f, "rgb(255, 176, 0) 7px 33px 7px");
        expect(underlying, to, 0f, "rgb(255, 165, 0) 10px 30px 10px");
        expect(underlying, to, 0.3f, "rgb(179, 154, 0) 13px 27px 13px");
        expect(underlying, to, 0.6f, "rgb(102, 143, 0) 16px 24px 16px");
        expect(underlying, to, 1f, "rgb(0, 128, 0) 20px 20px 20px");
        expect(underlying, to, 1.5f, "rgb(0, 110, 0) 25px 15px 25px");
    }

    @Test
    public void interpolationFromInitialPadsWithTransparent() {
        String to = "green 20px 20px 20px";
        expect("none", to, -0.3f, "rgba(0, 0, 0, 0) -6px -6px 0px");
        expect("none", to, 0f, "rgba(0, 0, 0, 0) 0px 0px 0px");
        expect("none", to, 0.3f, "rgba(0, 128, 0, 0.3) 6px 6px 6px");
        expect("none", to, 0.6f, "rgba(0, 128, 0, 0.6) 12px 12px 12px");
        expect("none", to, 1f, "rgb(0, 128, 0) 20px 20px 20px");
        expect("none", to, 1.5f, "rgb(0, 192, 0) 30px 30px 30px");
    }

    @Test
    public void interpolationFromInherit() {
        String parent = "30px 10px 30px orange";
        String to = "green 20px 20px 20px";
        expect(parent, to, -0.3f, "rgb(255, 176, 0) 33px 7px 33px");
        expect(parent, to, 0f, "rgb(255, 165, 0) 30px 10px 30px");
        expect(parent, to, 0.3f, "rgb(179, 154, 0) 27px 13px 27px");
        expect(parent, to, 0.6f, "rgb(102, 143, 0) 24px 16px 24px");
        expect(parent, to, 1f, "rgb(0, 128, 0) 20px 20px 20px");
        expect(parent, to, 1.5f, "rgb(0, 110, 0) 15px 25px 15px");
    }

    @Test
    public void interpolationClampsBlurAndColour() {
        String from = "black 15px 10px 5px";
        String to = "orange -15px -10px 25px";
        expect(from, to, -0.3f, "rgb(0, 0, 0) 24px 16px 0px");
        expect(from, to, 0f, "rgb(0, 0, 0) 15px 10px 5px");
        expect(from, to, 0.3f, "rgb(77, 50, 0) 6px 4px 11px");
        expect(from, to, 0.6f, "rgb(153, 99, 0) -3px -2px 17px");
        expect(from, to, 1f, "rgb(255, 165, 0) -15px -10px 25px");
        expect(from, to, 1.5f, "rgb(255, 248, 0) -30px -20px 35px");
    }

    @Test
    public void interpolationTowardCurrentColor() {
        String from = "black 10px 10px 10px";
        String to = "currentColor 10px 10px 10px";
        expect(from, to, -0.3f, "rgb(0, 0, 0) 10px 10px 10px");
        expect(from, to, 0f, "rgb(0, 0, 0) 10px 10px 10px");
        expect(from, to, 0.3f, "rgb(0, 38, 0) 10px 10px 10px");
        expect(from, to, 0.6f, "rgb(0, 77, 0) 10px 10px 10px");
        expect(from, to, 1f, "rgb(0, 128, 0) 10px 10px 10px");
        expect(from, to, 1.5f, "rgb(0, 192, 0) 10px 10px 10px");
    }

    @Test
    public void interpolationOfSubPixelLengths() {
        String from = "black 0px 0px 0px";
        String to = "black 1px 1px 1px";
        expect(from, to, -0.3f, "rgb(0, 0, 0) -0.3px -0.3px 0px");
        expect(from, to, 0f, "rgb(0, 0, 0) 0px 0px 0px");
        expect(from, to, 0.3f, "rgb(0, 0, 0) 0.3px 0.3px 0.3px");
        expect(from, to, 0.6f, "rgb(0, 0, 0) 0.6px 0.6px 0.6px");
        expect(from, to, 1f, "rgb(0, 0, 0) 1px 1px 1px");
        expect(from, to, 1.5f, "rgb(0, 0, 0) 1.5px 1.5px 1.5px");
    }

    /** Backgrounds 3: a pair whose inset differs cannot interpolate, so the whole list is discrete. */
    @Test
    public void mismatchedInsetIsDiscrete() {
        ShadowList outer = l4("1px 1px red");
        ShadowList inner = l4("1px 1px red inset");
        assertEquals(outer, ShadowList.interpolate(outer, inner, 0.4f));
        assertEquals(inner, ShadowList.interpolate(outer, inner, 0.6f));
    }

    /** Whatever the writer produces, the parser reads back as the same value. */
    @Test
    public void writtenValuesReadBack() {
        for (String raw : new String[]{"none", "1px 2px", "red 1px 2px 3px, 4px 5px",
                "currentcolor 0 0 8px", "1.5px -2px 3px 4px inset"}) {
            ShadowList list = l4(raw);
            assertEquals(raw, list, l4(ShadowParser.write(list)));
        }
    }
}
