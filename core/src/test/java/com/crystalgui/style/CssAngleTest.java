package com.crystalgui.style;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The engine's first angle unit. Pure parser — no window, no stylesheet, no GL.
 *
 * <p>The interesting cases are the rejections: an unrecognised unit must come back {@code null} so the
 * declaration is dropped and logged, never silently read as a plausible-looking number.</p>
 */
public class CssAngleTest {

    private static final float EPS = 1e-5f;

    @Test
    public void degreesAreTheCommonCase() {
        assertEquals((float) Math.PI / 2f, CssAngle.parse("90deg"), EPS);
        assertEquals(-(float) Math.PI, CssAngle.parse("-180deg"), EPS);
        assertEquals((float) Math.PI / 4f, CssAngle.parse("45.0deg"), EPS);
    }

    @Test
    public void radiansPassThrough() {
        assertEquals(1.5f, CssAngle.parse("1.5rad"), EPS);
    }

    /** `grad` ends with `rad`, so the suffix tests have to be ordered — this is what catches it. */
    @Test
    public void gradiansAreNotMistakenForRadians() {
        assertEquals((float) Math.PI / 2f, CssAngle.parse("100grad"), EPS);
    }

    @Test
    public void turnsAreFullRevolutions() {
        assertEquals((float) Math.PI * 2f, CssAngle.parse("1turn"), EPS);
        assertEquals((float) Math.PI / 2f, CssAngle.parse("0.25turn"), EPS);
    }

    @Test
    public void whitespaceAndCaseAreTolerated() {
        assertEquals((float) Math.PI / 2f, CssAngle.parse("  90DEG "), EPS);
    }

    /**
     * CSS allows a unitless zero and nothing else unitless. Accepting a bare {@code 45} would read
     * {@code rotate(45)} as 45 degrees — plausible, wrong, and impossible to notice.
     */
    @Test
    public void onlyZeroMayBeUnitless() {
        assertEquals(0f, CssAngle.parse("0"), EPS);
        assertNull("a bare non-zero number is not a CSS angle", CssAngle.parse("45"));
    }

    @Test
    public void garbageIsRejectedRatherThanGuessed() {
        assertNull(CssAngle.parse("45px"));
        assertNull(CssAngle.parse("deg"));
        assertNull(CssAngle.parse(""));
        assertNull(CssAngle.parse(null));
    }
}
