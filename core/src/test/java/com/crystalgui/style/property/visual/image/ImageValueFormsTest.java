package com.crystalgui.style.property.visual.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.render.image.CgUiMark;
import com.crystalgui.render.texture.asset.FileIconTheme;

/** What each CSS image function reads: gradients by the paint they make over a box, icons, marks, repeats, none. */
public class ImageValueFormsTest {

    private static CgUiImage.Gradient gradient(String css) {
        CgUiImage image = ImageValue.parse(css);
        assertTrue("did not parse as a gradient: " + css, image instanceof CgUiImage.Gradient);
        return (CgUiImage.Gradient) image;
    }

    /** A radial's centre and radii over a box, in pixels: what the paint was made with. */
    private static float[] radial(String css, float w, float h) {
        CgGradient paint = gradient(css).paint(w, h);
        assertEquals(CgGradient.Shape.RADIAL, paint.shape());
        return new float[]{paint.geometry(0) * w, paint.geometry(1) * h, paint.geometry(2) * w, paint.geometry(3) * h};
    }

    // ── linear-gradient ──

    @Test
    public void aCentredWashReadsItsDirectionAndPositions() {
        CgGradient g = gradient("linear-gradient(90deg, transparent 18%, #3574F033 50%, transparent 82%)").paint(10, 10);
        assertEquals(90f, g.geometry(0), 1e-4f);
        assertEquals(3, g.stopCount());
        assertEquals(0.18f, g.position(0), 1e-4f);
        assertEquals(0x333574F0, g.color(1));
        assertEquals("transparent before the first stop", 0, g.colorAt(0f) >>> 24);
        assertEquals("the middle stop at its position", 0x333574F0, g.colorAt(0.5f));
        assertEquals("halfway between 18% and 50% is half the alpha", 0x19, g.colorAt(0.34f) >>> 24, 1);
    }

    @Test
    public void aFadeToTransparentKeepsItsHue() {
        int mid = gradient("linear-gradient(90deg, #3574F0, transparent)").paint(10, 10).colorAt(0.5f);
        assertEquals(0x80, mid >>> 24, 1);
        assertEquals("premultiplied: a straight lerp would darken it", 0x3574F0, mid & 0xFFFFFF);
    }

    @Test
    public void missingPositionsSpreadEvenlyAndTheDefaultIsToBottom() {
        CgGradient g = gradient("linear-gradient(#000000, #FF0000, #FFFFFF)").paint(10, 10);
        assertEquals("CSS's default is to bottom", 180f, g.geometry(0), 1e-4f);
        assertEquals(0.5f, g.position(1), 1e-4f);
        assertEquals(1f, g.position(2), 1e-4f);
    }

    @Test
    public void sidesAndCornersResolveAsCssDoes() {
        assertEquals(270f, gradient("linear-gradient(to left, #000, #FFF)").angleFor(1, 1), 1e-4f);
        assertEquals(0f, gradient("linear-gradient(to top, #000, #FFF)").angleFor(1, 1), 1e-4f);
        assertEquals(90f, gradient("linear-gradient(to right, #000, #FFF)").angleFor(1, 1), 1e-4f);
        assertEquals("any CSS angle unit", 90f, gradient("linear-gradient(0.25turn, #000, #FFF)").angleFor(1, 1), 1e-3f);
        CgUiImage.Gradient corner = gradient("linear-gradient(to top right, #000, #FFF)");
        assertEquals("45 degrees on a square", 45f, corner.angleFor(100f, 100f), 1e-3f);
        assertEquals("shallower on a wide box", 26.565f, corner.angleFor(200f, 100f), 1e-2f);
        assertEquals("either word order", gradient("linear-gradient(to bottom left, #000, #FFF)"),
                gradient("linear-gradient(to left bottom, #000, #FFF)"));
    }

    @Test
    public void anRgbaStopWithSpacesKeepsItsPositionAndManyStopsAreKept() {
        CgGradient g = gradient("linear-gradient(to right, rgba(53, 116, 240, 0.2) 25%, #000000 75%)").paint(1, 1);
        assertEquals(0.25f, g.position(0), 1e-4f);
        assertEquals(0x3574F0, g.color(0) & 0xFFFFFF);
        assertEquals(10, gradient("linear-gradient(to right, #F00, #F80, #FF0, #0F0, #0FF, #00F, #80F, #F0F, #FFF, #000)")
                .stops().size());
    }

    @Test
    public void malformedLinearValuesFail() {
        assertNull("one stop", ImageValue.parse("linear-gradient(#000)"));
        assertNull("a bad colour", ImageValue.parse("linear-gradient(#000, nonsense)"));
        assertNull("an unknown side", ImageValue.parse("linear-gradient(to sideways, #000, #FFF)"));
        assertNull("two vertical words", ImageValue.parse("linear-gradient(to top bottom, #000, #FFF)"));
    }

    // ── radial-gradient ──

    @Test
    public void aBareRadialIsAnEllipseToTheFarthestCornerAtTheCentre() {
        float[] r = radial("radial-gradient(#FFF, #000)", 100f, 60f);
        assertEquals(50f, r[0], 1e-3f);
        assertEquals(30f, r[1], 1e-3f);
        assertEquals(50f * (float) Math.sqrt(2), r[2], 1e-3f);
        assertEquals(30f * (float) Math.sqrt(2), r[3], 1e-3f);
    }

    @Test
    public void positionsReadAsCssDoes() {
        float[] c = radial("radial-gradient(circle closest-side at 25% 15%, #FFF, #000)", 200f, 100f);
        assertEquals(50f, c[0], 1e-3f);
        assertEquals(15f, c[1], 1e-3f);
        c = radial("radial-gradient(at top left, #FFF, #000)", 100f, 80f);
        assertEquals(0f, c[0], 1e-3f);
        assertEquals(0f, c[1], 1e-3f);
        c = radial("radial-gradient(at right bottom, #FFF, #000)", 100f, 80f);
        assertEquals(100f, c[0], 1e-3f);
        assertEquals(80f, c[1], 1e-3f);
        c = radial("radial-gradient(at bottom, #FFF, #000)", 100f, 80f);
        assertEquals("one vertical keyword centres the other axis", 50f, c[0], 1e-3f);
        assertEquals(80f, c[1], 1e-3f);
    }

    @Test
    public void lengthsAndExtentsSizeTheShape() {
        float[] r = radial("radial-gradient(40px 20% at 0 0, #FFF, #000)", 100f, 50f);
        assertEquals(40f, r[2], 1e-3f);
        assertEquals("a percentage is of its own axis", 10f, r[3], 1e-3f);
        r = radial("radial-gradient(circle 30px, #FFF, #000)", 100f, 50f);
        assertEquals(30f, r[2], 1e-3f);
        assertEquals(30f, r[3], 1e-3f);
        String at = " at 20px 10px, #FFF, #000)";
        assertEquals("circle: the nearest side", 10f, radial("radial-gradient(circle closest-side" + at, 100f, 50f)[2], 1e-3f);
        assertEquals(80f, radial("radial-gradient(circle farthest-side" + at, 100f, 50f)[2], 1e-3f);
        assertEquals((float) Math.hypot(80, 40), radial("radial-gradient(circle farthest-corner" + at, 100f, 50f)[2], 1e-2f);
        r = radial("radial-gradient(ellipse closest-side" + at, 100f, 50f);
        assertEquals("ellipse: each axis its own side", 20f, r[2], 1e-3f);
        assertEquals(10f, r[3], 1e-3f);
        r = radial("radial-gradient(ellipse farthest-corner" + at, 100f, 50f);
        assertEquals(80f * (float) Math.sqrt(2), r[2], 1e-2f);
        assertEquals(40f * (float) Math.sqrt(2), r[3], 1e-2f);
    }

    @Test
    public void malformedRadialValuesFail() {
        assertNull("a circle's radius is no percentage", ImageValue.parse("radial-gradient(circle 20%, #FFF, #000)"));
        assertNull("an ellipse needs two lengths", ImageValue.parse("radial-gradient(ellipse 20px, #FFF, #000)"));
        assertNull("an extent and a length", ImageValue.parse("radial-gradient(closest-side 20px, #FFF, #000)"));
        assertNull("an unknown word", ImageValue.parse("radial-gradient(oval, #FFF, #000)"));
        assertNull("a position with no coordinate", ImageValue.parse("radial-gradient(at, #FFF, #000)"));
        assertNull("one stop", ImageValue.parse("radial-gradient(circle, #FFF)"));
    }

    // ── icon, shape, none, repeat ──

    @Test
    public void iconsResolveAndAMissingOneFails() {
        assertTrue(ImageValue.parse("icon(\"crystalgui:folder\")") instanceof CgUiImage.Icon);
        assertTrue("the namespace is optional", ImageValue.parse("icon(\"folder\")") instanceof CgUiImage.Icon);
        assertTrue(ImageValue.parse("icon(\"crystalgui:folder\", monochrome, #FF0000)") instanceof CgUiImage.Icon);
        assertNull(ImageValue.parse("icon(\"crystalgui:no-such-icon\")"));
        assertNull(ImageValue.parse("icon()"));
        assertNull(ImageValue.parse("icon(\"\")"));
        assertNull("an unknown trailing argument fails", ImageValue.parse("icon(\"crystalgui:folder\", wibble)"));
    }

    @Test
    public void everyThemeIconIsNameableFromCss() {
        Set<String> names = FileIconTheme.getDefault().iconNames();
        assertTrue(names.size() >= 10);
        for (String name : names) {
            assertTrue(name, ImageValue.parse("icon(\"" + name + "\")") instanceof CgUiImage.Icon);
        }
    }

    @Test
    public void everyMarkParsesByItsNameInEitherQuotes() {
        for (CgUiMark mark : CgUiMark.values()) {
            assertEquals(CgUiImage.mark(mark), ImageValue.parse("shape(\"" + mark.cssName() + "\")"));
        }
        assertEquals(CgUiImage.mark(CgUiMark.CHECKMARK), ImageValue.parse("shape('checkmark')"));
        assertNull(ImageValue.parse("shape(\"not-a-real-shape\")"));
        assertNull(ImageValue.parse("shape()"));
        assertNull(ImageValue.parse("shape(\"checkmark\", \"extra\")"));
    }

    @Test
    public void noneAndItsAliasAreTheEmptyImage() {
        assertSame(CgUiImage.NONE, ImageValue.parse("none"));
        assertSame(CgUiImage.NONE, ImageValue.parse("empty"));
        assertSame(CgUiImage.NONE, ImageValue.parse("  NONE  "));
        assertNull(ImageValue.parse("nonesuch"));
        assertNull(ImageValue.parse("not-a-value"));
    }

    @Test
    public void repeatPairsReadAsBorderImageRepeat() {
        assertArrayEquals(new CgUiImage.Repeat[]{CgUiImage.Repeat.ROUND, CgUiImage.Repeat.ROUND}, ImageValue.repeatPair("round"));
        assertArrayEquals(new CgUiImage.Repeat[]{CgUiImage.Repeat.REPEAT, CgUiImage.Repeat.SPACE},
                ImageValue.repeatPair("repeat space"));
        assertArrayEquals(new CgUiImage.Repeat[]{CgUiImage.Repeat.STRETCH, CgUiImage.Repeat.ROUND},
                ImageValue.repeatPair("  STRETCH   Round  "));
        for (String bad : new String[]{"64 64", "#ff0000", "wobble", "round wobble", "wobble round", "round round round"}) {
            assertNull(bad, ImageValue.repeatPair(bad));
        }
        assertNull(ImageValue.repeatPair(null));
    }
}
