package com.crystalgui.ui;

import com.crystalgui.render.texture.ArgbMath;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The HSV conversions behind the colour picker.
 *
 * <h3>Why this is worth pinning</h3>
 * <p>A colour picker is a loop: the ring's angle becomes a hue, the hue becomes RGB, and the RGB has
 * to come back as the same angle or the handle drifts away from the colour under it. Nothing throws
 * when it is wrong — the picker simply becomes subtly unusable, and only for some colours.</p>
 */
public class ArgbHsvTest {

    private static void assertRoundTrips(int argb) {
        float[] hsv = ArgbMath.toHsv(argb);
        int back = ArgbMath.fromHsv(hsv[0], hsv[1], hsv[2], (argb >>> 24) & 0xFF);
        assertEquals("round trip of " + Integer.toHexString(argb), argb, back);
    }

    /** Every primary and secondary, plus the greys — the hue sector boundaries are where this breaks. */
    @Test
    public void everySectorRoundTrips() {
        int[] colours = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF,
                0xFF000000, 0xFFFFFFFF, 0xFF808080,
                0xFF3A9376, 0xFFB00DDB, 0x80123456
        };
        for (int argb : colours) assertRoundTrips(argb);
    }

    /** Alpha is carried, not recomputed — a picker's A slider is independent of hue and value. */
    @Test
    public void alphaSurvives() {
        assertEquals(0x00FF0000, ArgbMath.fromHsv(0f, 1f, 1f, 0));
        assertEquals(0x84FF0000, ArgbMath.fromHsv(0f, 1f, 1f, 0x84));
    }

    /** Red sits at hue 0, and the sectors run in the order the ring draws them. */
    @Test
    public void hueMatchesTheRingsOrder() {
        assertEquals(0f, ArgbMath.toHsv(0xFFFF0000)[0], 0.001f);
        assertEquals(1f / 3f, ArgbMath.toHsv(0xFF00FF00)[0], 0.001f);
        assertEquals(2f / 3f, ArgbMath.toHsv(0xFF0000FF)[0], 0.001f);
    }

    /**
     * <b>A grey reports hue 0, and a picker must not write that back.</b>
     *
     * <p>Dragging value down to black would otherwise snap the ring to red, throwing away the hue the
     * user picked — so hue lives in the widget's state rather than being re-derived from the colour.
     * This test exists to state that, because the conversion itself is not wrong.</p>
     */
    @Test
    public void hueIsUndefinedForGreys() {
        assertEquals(0f, ArgbMath.toHsv(0xFF000000)[0], 0.001f);
        assertEquals(0f, ArgbMath.toHsv(0xFF808080)[0], 0.001f);
        assertEquals("saturation is what actually says 'grey'", 0f, ArgbMath.toHsv(0xFF808080)[1], 0.001f);
    }

    /** Value is the largest channel and saturation its spread — the SV square's two axes. */
    @Test
    public void saturationAndValueAreTheSquaresAxes() {
        float[] halfRed = ArgbMath.toHsv(0xFF800000);
        assertEquals("value is the max channel", 128f / 255f, halfRed[2], 0.01f);
        assertEquals("fully saturated regardless of brightness", 1f, halfRed[1], 0.01f);
    }
}
