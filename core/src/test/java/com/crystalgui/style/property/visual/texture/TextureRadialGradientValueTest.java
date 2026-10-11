package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.render.texture.CgUiGradient.Extent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code radial-gradient()} parses to a {@link CgUiGradient} whose ending shape and centre follow CSS
 * Images 3 §3.5: the configuration is read, the defaults hold, the extents size the shape per box, and a
 * malformed value is a parse failure.
 */
public class TextureRadialGradientValueTest {

    private static CgUiGradient.Radial parse(String css) {
        CgUiDrawable d = TextureValue.parseDrawable(css);
        assertTrue("did not parse as a gradient: " + css, d instanceof CgUiGradient);
        CgUiGradient.Radial radial = ((CgUiGradient) d).radial();
        assertTrue("parsed as linear: " + css, radial != null);
        return radial;
    }

    private static float[] radii(String css, float w, float h) {
        return ((CgUiGradient) TextureValue.parseDrawable(css)).radiiFor(w, h);
    }

    private static float[] centre(String css, float w, float h) {
        return ((CgUiGradient) TextureValue.parseDrawable(css)).centreFor(w, h);
    }

    @Test
    public void noConfigurationIsAnEllipseToTheFarthestCornerAtTheCentre() {
        CgUiGradient.Radial r = parse("radial-gradient(#FFF, #000)");
        assertFalse(r.circle());
        assertEquals(Extent.FARTHEST_CORNER, r.extent());
        assertEquals(50f, centre("radial-gradient(#FFF, #000)", 100f, 60f)[0], 1e-4f);
        assertEquals(30f, centre("radial-gradient(#FFF, #000)", 100f, 60f)[1], 1e-4f);
    }

    @Test
    public void shapeExtentAndPositionAreRead() {
        CgUiGradient.Radial r = parse("radial-gradient(circle closest-side at 25% 15%, #FFF, #000)");
        assertTrue(r.circle());
        assertEquals(Extent.CLOSEST_SIDE, r.extent());
        float[] c = centre("radial-gradient(circle closest-side at 25% 15%, #FFF, #000)", 200f, 100f);
        assertEquals(50f, c[0], 1e-4f);
        assertEquals(15f, c[1], 1e-4f);
    }

    @Test
    public void keywordPositionsReadInEitherOrder() {
        float[] c = centre("radial-gradient(at top left, #FFF, #000)", 100f, 80f);
        assertEquals(0f, c[0], 1e-4f);
        assertEquals(0f, c[1], 1e-4f);
        c = centre("radial-gradient(at right bottom, #FFF, #000)", 100f, 80f);
        assertEquals(100f, c[0], 1e-4f);
        assertEquals(80f, c[1], 1e-4f);
        c = centre("radial-gradient(at bottom, #FFF, #000)", 100f, 80f);
        assertEquals("one vertical keyword centres the other axis", 50f, c[0], 1e-4f);
        assertEquals(80f, c[1], 1e-4f);
    }

    @Test
    public void lengthsPickTheShape() {
        assertTrue("one length alone is a circle", parse("radial-gradient(40px, #FFF, #000)").circle());
        assertFalse("two are an ellipse", parse("radial-gradient(40px 20%, #FFF, #000)").circle());
        float[] r = radii("radial-gradient(40px 20% at 0 0, #FFF, #000)", 100f, 50f);
        assertEquals(40f, r[0], 1e-4f);
        assertEquals("a percentage is of its own axis", 10f, r[1], 1e-4f);
        r = radii("radial-gradient(circle 30px, #FFF, #000)", 100f, 50f);
        assertEquals(30f, r[0], 1e-4f);
        assertEquals(30f, r[1], 1e-4f);
    }

    @Test
    public void extentsSizeTheShapePerBox() {
        // Centre (20, 10) in a 100x50 box: sides 20 and 80 across, 10 and 40 down.
        String at = " at 20px 10px, #FFF, #000)";
        float[] r = radii("radial-gradient(circle closest-side" + at, 100f, 50f);
        assertEquals("circle: the nearest of all four sides", 10f, r[0], 1e-4f);
        r = radii("radial-gradient(circle farthest-side" + at, 100f, 50f);
        assertEquals(80f, r[0], 1e-4f);
        r = radii("radial-gradient(circle farthest-corner" + at, 100f, 50f);
        assertEquals("circle: the distance to the corner", (float) Math.hypot(80, 40), r[0], 1e-3f);
        r = radii("radial-gradient(ellipse closest-side" + at, 100f, 50f);
        assertEquals("ellipse: each axis its own side", 20f, r[0], 1e-4f);
        assertEquals(10f, r[1], 1e-4f);
        r = radii("radial-gradient(ellipse farthest-corner" + at, 100f, 50f);
        // CSS: the ellipse through the corner with the side extent's aspect ratio, i.e. sqrt(2) x the sides.
        assertEquals(80f * (float) Math.sqrt(2), r[0], 1e-3f);
        assertEquals(40f * (float) Math.sqrt(2), r[1], 1e-3f);
    }

    @Test
    public void malformedValuesFailToParse() {
        assertNull("a circle's radius may not be a percentage",
                TextureValue.parseDrawable("radial-gradient(circle 20%, #FFF, #000)"));
        assertNull("an ellipse needs two lengths",
                TextureValue.parseDrawable("radial-gradient(ellipse 20px, #FFF, #000)"));
        assertNull("an extent and a length together",
                TextureValue.parseDrawable("radial-gradient(closest-side 20px, #FFF, #000)"));
        assertNull("an unknown word", TextureValue.parseDrawable("radial-gradient(oval, #FFF, #000)"));
        assertNull("a position with no coordinate", TextureValue.parseDrawable("radial-gradient(at, #FFF, #000)"));
        assertNull("one stop", TextureValue.parseDrawable("radial-gradient(circle, #FFF)"));
    }
}
