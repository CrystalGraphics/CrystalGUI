package com.crystalgui.widget.form;

import com.crystalgui.widget.form.ColorSelector;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The hue ring's geometry — where a hue is drawn, and what hue a click there means.
 *
 * <h3>Why this needs a test at all</h3>
 * <p>Three separate pieces have to agree on one convention: the shader that <em>draws</em> the band, the
 * placement that puts the handle on it, and the click path that reads a hue back. They disagreed twice —
 * once by a quarter turn and once by a half turn — and neither failure throws. The ring simply lies:
 * the handle sits on a colour that is not the one selected, and clicking magenta yields green.</p>
 *
 * <p>The reference is {@code gui_color_field.shader}, which computes
 * {@code fract(atan2(x, y) / 2π + 0.5)} with <b>y pointing down</b>. Everything below is that function
 * or its inverse.</p>
 */
public class ColorSelectorRingTest {

    private static final float R = 0.43f;

    /** Red at the top is the orientation every picker uses, and what the shader draws. */
    @Test
    public void hueZeroIsAtTheTop() {
        float[] offset = ColorSelector.offsetForHue(0f, R);
        assertEquals("no horizontal offset", 0f, offset[0], 0.001f);
        assertEquals("above centre, so dy is negative", -R, offset[1], 0.001f);
    }

    /**
     * <b>The case that was wrong on screen.</b>
     *
     * <p>Magenta (hue 300°) belongs on the upper RIGHT. The handle was drawing it upper-left, because the
     * placement omitted the shader's half-turn and flipped the vertical sign.</p>
     */
    @Test
    public void magentaSitsUpperRight() {
        float[] offset = ColorSelector.offsetForHue(300f / 360f, R);
        assertTrue("right of centre: " + offset[0], offset[0] > 0f);
        assertTrue("above centre: " + offset[1], offset[1] < 0f);
    }

    /**
     * Quarter-turn checkpoints, so a sign flip shows up as a specific wrong quadrant.
     *
     * <p>The ring runs <b>anticlockwise</b> from red at the top — red, green at the left, cyan at the
     * bottom, magenta at the right — which is what {@code fract(atan2(x, y) / 2π + 0.5)} produces with y
     * down. Worth stating outright: the first draft of this test assumed clockwise and failed, and it
     * would have been easy to "fix" the code to match the test and break the widget instead.</p>
     */
    @Test
    public void theQuartersLandWhereTheShaderDrawsThem() {
        // Reading a hue back from each cardinal direction, dy DOWN-positive.
        assertEquals("red at top", 0f, ColorSelector.hueFromOffset(0f, -1f), 0.001f);
        assertEquals("green at left", 0.25f, ColorSelector.hueFromOffset(-1f, 0f), 0.001f);
        assertEquals("cyan at bottom", 0.5f, ColorSelector.hueFromOffset(0f, 1f), 0.001f);
        assertEquals("magenta at right", 0.75f, ColorSelector.hueFromOffset(1f, 0f), 0.001f);
    }

    /**
     * <b>Placement and reading are exact inverses.</b>
     *
     * <p>This is the property that actually matters: drop the handle at a hue, click where it landed,
     * and get the same hue back. Any shared misconvention would still fail one of the fixed checks
     * above, so the two together pin the orientation rather than merely its self-consistency.</p>
     */
    @Test
    public void placingAndReadingRoundTrip() {
        for (int degrees = 0; degrees < 360; degrees += 15) {
            float hue = degrees / 360f;
            float[] offset = ColorSelector.offsetForHue(hue, R);
            float read = ColorSelector.hueFromOffset(offset[0], offset[1]);
            assertEquals("hue " + degrees + "deg", hue, read, 0.001f);
        }
    }

    /** The centre is degenerate; it must still return a usable hue rather than NaN. */
    @Test
    public void theCentreDoesNotProduceNaN() {
        float hue = ColorSelector.hueFromOffset(0f, 0f);
        assertFalse(Float.isNaN(hue));
        assertTrue(hue >= 0f && hue <= 1f);
    }
}
