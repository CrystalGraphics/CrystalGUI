package com.crystalgui.headless;

import com.crystalgui.render.texture.svg.SvgPath;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link SvgPath} — enough of the {@code d} grammar to draw a real icon. */
public class SvgPathTest {

    /** Feather's `folder`, MIT. A real icon, arcs and all. */
    private static final String FOLDER =
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";

    @Test
    public void aStraightRunParsesToItsCorners() {
        List<SvgPath.Polyline> lines = SvgPath.parse("M0 0 L10 0 L10 10 Z");
        assertEquals(1, lines.size());
        assertTrue("a Z must mark the run closed", lines.get(0).closed());
        assertEquals(3, lines.get(0).points().size());
    }

    @Test
    public void relativeCommandsAccumulate() {
        List<SvgPath.Polyline> lines = SvgPath.parse("M5 5 l10 0 l0 10");
        List<float[]> points = lines.get(0).points();
        assertEquals(15f, points.get(1)[0], 0.001f);
        assertEquals(15f, points.get(2)[1], 0.001f);
    }

    /** {@code H} and {@code V} carry the other axis forward rather than zeroing it. */
    @Test
    public void horizontalAndVerticalKeepTheOtherAxis() {
        List<float[]> points = SvgPath.parse("M3 7 H20 V2").get(0).points();
        assertEquals(7f, points.get(1)[1], 0.001f);
        assertEquals(20f, points.get(2)[0], 0.001f);
    }

    /**
     * <b>The whole icon parses, closed, and lands inside its own viewBox.</b>
     *
     * <p>The bounds are the assertion that matters. A parser that mishandles arcs still produces
     * <em>points</em> — they simply fly off somewhere — so a count alone would pass on geometry that
     * renders as a spray of lines across the screen. Feather authors this in a 24×24 box, so anything
     * outside it is wrong by the artwork's own definition.</p>
     */
    @Test
    public void aRealIconParsesInsideItsViewBox() {
        List<SvgPath.Polyline> lines = SvgPath.parse(FOLDER);
        assertEquals("one path, one run", 1, lines.size());
        assertTrue(lines.get(0).closed());

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] point : lines.get(0).points()) {
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        assertTrue("left edge outside the viewBox: " + minX, minX >= -0.5f);
        assertTrue("top edge outside the viewBox: " + minY, minY >= -0.5f);
        assertTrue("right edge outside the viewBox: " + maxX, maxX <= 24.5f);
        assertTrue("bottom edge outside the viewBox: " + maxY, maxY <= 24.5f);

        // And it fills the box rather than collapsing to a dot, which is what a curve flattener that
        // silently produced nothing would look like from a bounds check alone.
        assertTrue("the shape collapsed: " + (maxX - minX), maxX - minX > 18f);
        assertTrue("the shape collapsed: " + (maxY - minY), maxY - minY > 15f);
    }

    /** Malformed data degrades rather than hanging — an unknown command must not spin the cursor. */
    @Test
    public void junkDoesNotHang() {
        SvgPath.parse("M0 0 K9 9 L5 5");
        SvgPath.parse("");
        SvgPath.parse("nonsense");
    }

    // The tests that LOAD a .svg live in core/src/test, not here: SvgDocument.load reaches CgIO, which
    // is CrystalGraphics core and deliberately absent from this source set. The absence is the assertion
    // -- a dedicated server has no resource manager -- so parse() is testable here and load() is not.

    /** A rect with rx goes through the same arc code a path would, not a second corner implementation. */
    @Test
    public void aRoundedRectBecomesGeometry() {
        com.crystalgui.render.texture.svg.SvgDocument image =
                com.crystalgui.render.texture.svg.SvgDocument.parse(
                        "<svg viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" "
                                + "height=\"18\" rx=\"2\" ry=\"2\"/></svg>");
        assertTrue("the rect produced nothing", !image.isEmpty());
        for (SvgPath.Polyline line : image.outline()) {
            for (float[] point : line.points()) {
                assertTrue("corner arc escaped the rect: " + point[0], point[0] >= 2.5f && point[0] <= 21.5f);
                assertTrue("corner arc escaped the rect: " + point[1], point[1] >= 2.5f && point[1] <= 21.5f);
            }
        }
    }

    /**
     * <b>A second decimal point starts a new number.</b>
     *
     * <p>{@code 2.128.194} is two numbers — {@code 2.128} and {@code .194} — not one malformed one. SVG's
     * grammar allows the separator to be dropped exactly there, and every minifier takes it: the shipped
     * {@code Csharp.svg} contains {@code c.739 0 2.128.194 2.471.875}, which is six numbers.</p>
     *
     * <p>Consuming dots greedily made {@code Float.parseFloat} throw, and the parser's {@code catch}
     * turned that into {@code 0} — <b>silently</b>. Nothing failed to load; control points landed on the
     * origin and one terminal of the glyph collapsed into a spike, so it read as a rendering artefact
     * rather than a parse error. Which is why this asserts the coordinates and not merely that a path
     * came back.</p>
     */
    @Test
    public void aSecondDecimalPointStartsANewNumber() {
        List<SvgPath.Polyline> lines = SvgPath.parse("M0 0 l1.5.5 2.5.5");
        assertEquals(1, lines.size());
        List<float[]> points = lines.get(0).points();
        assertEquals("start", 0f, points.get(0)[0], 1e-4f);
        assertEquals("1.5 then .5, not the single token 1.5.5", 1.5f, points.get(1)[0], 1e-4f);
        assertEquals(0.5f, points.get(1)[1], 1e-4f);
        assertEquals(4f, points.get(2)[0], 1e-4f);
        assertEquals(1f, points.get(2)[1], 1e-4f);
    }

    /** One exponent per number, so {@code 1e2.5} is {@code 1e2} then {@code .5}. */
    @Test
    public void anExponentEndsAtTheNextNumber() {
        List<float[]> points = SvgPath.parse("M0 0 L1e1 2").get(0).points();
        assertEquals("a plain exponent still parses", 10f, points.get(1)[0], 1e-4f);
        assertEquals(2f, points.get(1)[1], 1e-4f);

        List<float[]> split = SvgPath.parse("M0 0 L1e2.5 3").get(0).points();
        assertEquals(100f, split.get(1)[0], 1e-4f);
        assertEquals(0.5f, split.get(1)[1], 1e-4f);
    }
}
