package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiGradient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code linear-gradient()} parses to a {@link CgUiGradient} with CSS's direction, stop and
 * interpolation rules.
 *
 * <p>The spine, not the picture: the direction is read (angle, side, corner), the stops keep their
 * order and spread when unpositioned, the interpolation is premultiplied, the gradient line puts its
 * ends at the corners, and a malformed value is a parse failure rather than a gradient of something.
 * What the shader draws is the probe scene's to show ({@code --mode=cgui-gradient-probe}).</p>
 */
public class TextureGradientValueTest {

    private static CgUiGradient parse(String css) {
        CgUiDrawable d = TextureValue.parseDrawable(css);
        assertTrue("did not parse as a gradient: " + css, d instanceof CgUiGradient);
        return (CgUiGradient) d;
    }

    @Test
    public void aCentredWashReadsItsDirectionAndPositions() {
        CgUiGradient g = parse("linear-gradient(90deg, transparent 18%, #3574F033 50%, transparent 82%)");
        assertEquals(90f, g.angleDeg(), 1e-4f);
        assertEquals(3, g.stops().size());
        assertEquals(0.18f, g.stops().get(0).position(), 1e-4f);
        assertEquals(0.50f, g.stops().get(1).position(), 1e-4f);
        assertEquals(0x333574F0, g.stops().get(1).argb());
        assertEquals("fully transparent before the first stop", 0, g.colorAt(0f) >>> 24);
        assertEquals("the middle stop, exactly, at its position", 0x333574F0, g.colorAt(0.5f));
        assertEquals("halfway between 18% and 50% is half the alpha", 0x19, g.colorAt(0.34f) >>> 24, 1);
    }

    @Test
    public void aFadeToTransparentKeepsItsHue() {
        // CSS interpolates gradients PREMULTIPLIED (Images 3 §3.4.3): `transparent` is transparent
        // black, and a straight lerp toward it darkens the colour on the way -- the muddy shoulder of
        // every naive fade. Halfway to transparent the blue is still the blue at half the alpha.
        CgUiGradient g = parse("linear-gradient(90deg, #3574F0, transparent)");
        int mid = g.colorAt(0.5f);
        assertEquals(0x80, mid >>> 24, 1);
        assertEquals("the hue survives -- a straight lerp answers 0x1A3A78", 0x3574F0, mid & 0xFFFFFF);
    }

    @Test
    public void missingPositionsSpreadEvenlyAndTheDefaultDirectionIsToBottom() {
        CgUiGradient g = parse("linear-gradient(#000000, #FF0000, #FFFFFF)");
        assertEquals("CSS's default is to bottom", 180f, g.angleDeg(), 1e-4f);
        assertEquals(0f, g.stops().get(0).position(), 1e-4f);
        assertEquals(0.5f, g.stops().get(1).position(), 1e-4f);
        assertEquals(1f, g.stops().get(2).position(), 1e-4f);
    }

    @Test
    public void sidesAndCornersResolveAsCssDoes() {
        assertEquals(270f, parse("linear-gradient(to left, #000, #FFF)").angleDeg(), 1e-4f);
        assertEquals(0f, parse("linear-gradient(to top, #000, #FFF)").angleDeg(), 1e-4f);
        assertEquals(90f, parse("linear-gradient(to right, #000, #FFF)").angleDeg(), 1e-4f);
        assertEquals("any CSS angle unit", 90f, parse("linear-gradient(0.25turn, #000, #FFF)").angleDeg(), 1e-3f);

        // A corner's angle depends on the box: the 50% line passes through the OTHER two corners.
        CgUiGradient corner = parse("linear-gradient(to top right, #000, #FFF)");
        assertEquals(CgUiGradient.Corner.TOP_RIGHT, corner.corner());
        assertEquals("45 degrees on a square", 45f, corner.angleFor(100f, 100f), 1e-3f);
        assertEquals("shallower on a wide box", 26.565f, corner.angleFor(200f, 100f), 1e-2f);
        assertEquals("either word order", CgUiGradient.Corner.BOTTOM_LEFT,
                parse("linear-gradient(to left bottom, #000, #FFF)").corner());
    }

    @Test
    public void theGradientLinePutsItsEndsAtTheCorners() {
        // CSS Images 3 §3.4.1: at any angle the line is long enough that 0% and 100% land on corners,
        // and for a corner direction the two remaining corners sit exactly on the 50% line.
        CgUiGradient g = parse("linear-gradient(to bottom right, #000, #FFF)");
        float[] axis = g.axisFor(300f, 100f);
        assertEquals("top-left is 0", 0f, t(axis, 0f, 0f), 1e-4f);
        assertEquals("bottom-right is 1", 1f, t(axis, 1f, 1f), 1e-4f);
        assertEquals("top-right is on the 50% line", 0.5f, t(axis, 1f, 0f), 1e-4f);
        assertEquals("bottom-left is on the 50% line", 0.5f, t(axis, 0f, 1f), 1e-4f);

        float[] right = parse("linear-gradient(90deg, #000, #FFF)").axisFor(300f, 100f);
        assertEquals("90deg is plain u", 1f, right[0], 1e-4f);
        assertEquals(0f, right[1], 1e-4f);
        float[] up = parse("linear-gradient(to top, #000, #FFF)").axisFor(300f, 100f);
        assertEquals("to top runs against v", -1f, up[1], 1e-4f);
    }

    /** The shader's own formula for a fragment at {@code (u, v)}. */
    private static float t(float[] axis, float u, float v) {
        return 0.5f + (u - 0.5f) * axis[0] + (v - 0.5f) * axis[1];
    }

    @Test
    public void anRgbaStopWithSpacesKeepsItsPosition() {
        CgUiGradient g = parse("linear-gradient(to right, rgba(53, 116, 240, 0.2) 25%, #000000 75%)");
        assertEquals(0.25f, g.stops().get(0).position(), 1e-4f);
        assertEquals(0x3574F0, g.stops().get(0).argb() & 0xFFFFFF);
    }

    @Test
    public void moreStopsThanOneDrawHoldsAreKept() {
        // Eight per draw is the shader's window, not the gradient's limit: nothing is dropped.
        CgUiGradient g = parse("linear-gradient(to right, #F00, #F80, #FF0, #0F0, #0FF, #00F, #80F, #F0F, #FFF, #000)");
        assertEquals(10, g.stops().size());
        assertEquals(1f, g.stops().get(9).position(), 1e-4f);
    }

    @Test
    public void malformedValuesAreParseFailuresNotGradients() {
        assertNull("one stop is not a gradient", TextureValue.parseDrawable("linear-gradient(#000)"));
        assertNull("a colour that does not parse fails the whole value", TextureValue.parseDrawable("linear-gradient(#000, nonsense)"));
        assertNull("an unknown side fails", TextureValue.parseDrawable("linear-gradient(to sideways, #000, #FFF)"));
        assertNull("two vertical words are not a corner", TextureValue.parseDrawable("linear-gradient(to top bottom, #000, #FFF)"));
    }
}
