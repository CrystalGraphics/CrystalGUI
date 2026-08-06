package com.crystalgui.ui;

import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgPath;
import com.crystalgui.render.texture.svg.SvgTriangulator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The structural half of the SVG loader — groups, transforms, inheritance, paint servers, fills.
 *
 * <p>Everything here is a document a <b>regex loader could not have read at all</b>: it saw shape elements
 * and nothing around them, so a group's transform, an ancestor's colour and a {@code <defs>} template were
 * all equally invisible to it. These are the assertions that pin the scanner actually being used.</p>
 */
public class SvgDocumentTest {

    private static float[] pointsOf(SvgDocument document) {
        List<float[]> all = new ArrayList<>();
        for (SvgPath.Polyline line : document.outline()) all.addAll(line.points());
        float[] flat = new float[all.size() * 2];
        for (int i = 0; i < all.size(); i++) {
            flat[i * 2] = all.get(i)[0];
            flat[i * 2 + 1] = all.get(i)[1];
        }
        return flat;
    }

    /**
     * <b>A group's transform reaches its children, and nested groups compose.</b>
     *
     * <p>The outer scales by 2 and the inner then translates by 5 — so the point lands at
     * {@code (2·(1+5), 2·(1+5))} if composition is right, at {@code (2+5, 2+5)} if the two are applied in
     * the wrong order, and at {@code (1,1)} if the transform never arrived. All three are distinguishable,
     * which is the point of picking numbers that are not 0, 1 or each other.</p>
     */
    @Test
    public void nestedGroupTransformsCompose() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 100 100'>"
                        + "<g transform='scale(2)'><g transform='translate(5,5)'>"
                        + "<line x1='1' y1='1' x2='1' y2='1' stroke='black'/>"
                        + "</g></g></svg>");
        float[] points = pointsOf(document);
        assertEquals("x", 12f, points[0], 0.01f);
        assertEquals("y", 12f, points[1], 0.01f);
    }

    /** A {@code rotate} about a centre is a rotation there, not at the origin. */
    @Test
    public void aRotationAboutACentreStaysAboutIt() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 100 100'><g transform='rotate(90 10 10)'>"
                        + "<line x1='10' y1='10' x2='20' y2='10' stroke='black'/></g></svg>");
        float[] points = pointsOf(document);
        assertEquals("the centre is a fixed point", 10f, points[0], 0.01f);
        assertEquals(10f, points[1], 0.01f);
        assertEquals("and the far end swung a quarter turn", 10f, points[2], 0.01f);
        assertEquals(20f, points[3], 0.01f);
    }

    /**
     * <b>Presentation attributes inherit, and {@code style=""} beats them.</b>
     *
     * <p>CSS's own precedence — a presentation attribute has author specificity zero and an inline
     * declaration outranks it. Exported artwork carries both with different values constantly, so reading
     * them the other way round paints a whole file the wrong colour.</p>
     */
    @Test
    public void inlineStyleOutranksAPresentationAttribute() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 10 10'><g fill='#ff0000'>"
                        + "<rect x='0' y='0' width='4' height='4'/>"
                        + "<rect x='5' y='5' width='4' height='4' fill='#00ff00' style='fill:#0000ff'/>"
                        + "</g></svg>");
        assertEquals(2, document.ops().size());
        assertEquals("inherited from the group", 0xFFFF0000, document.ops().get(0).argb());
        assertEquals("style beat the attribute", 0xFF0000FF, document.ops().get(1).argb());
    }

    /**
     * <b>{@code <defs>} does not draw, and {@code <use>} makes it draw.</b>
     *
     * <p>Both halves matter and they fail in opposite directions: rendering {@code <defs>} in place paints
     * every template on top of the artwork, and ignoring {@code <use>} leaves a file that is entirely
     * definitions showing nothing at all.</p>
     */
    @Test
    public void definitionsDrawOnlyThroughUse() {
        String defsOnly = "<svg viewBox='0 0 10 10'><defs>"
                + "<rect id='r' x='0' y='0' width='2' height='2' fill='black'/></defs>";
        assertTrue("a definition drew where it was defined",
                SvgDocument.parse(defsOnly + "</svg>").ops().isEmpty());

        SvgDocument used = SvgDocument.parse(defsOnly + "<use href='#r' x='5' y='5'/></svg>");
        assertEquals(1, used.ops().size());
        float[] points = pointsOf(used);
        assertEquals("the use's x offset was dropped", 5f, points[0], 0.01f);
        assertEquals(5f, points[1], 0.01f);
    }

    /**
     * <b>A gradient fill is evaluated per pixel, not approximated per cell.</b>
     *
     * <p>The op carries a start colour, an end colour and an axis per triangle; the fragment stage
     * computes {@code t = dot(p - origin, dir)} and mixes. The two things that can be wrong are the
     * colours and the axis, so both are asserted: the ramp's ends must actually reach the stops, and the
     * axis must map the triangle's own extent onto {@code [0, 1]}.</p>
     *
     * <p>This replaced a test that asserted a <em>flat colour per cell</em> spanned the ramp. That was
     * the right assertion for a mesh that approximated a gradient by subdividing, and it is meaningless
     * for one that evaluates it — which is why it failed rather than passing vacuously when the model
     * changed.</p>
     */
    @Test
    public void aGradientFillIsEvaluatedPerPixel() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 10 10'>"
                        + "<linearGradient id='g' gradientUnits='userSpaceOnUse' x1='0' y1='0'"
                        + " x2='4' y2='0'>"
                        + "<stop offset='0' style='stop-color:#000000'/>"
                        + "<stop offset='1' style='stop-color:#ffffff'/>"
                        + "</linearGradient>"
                        + "<rect x='0' y='0' width='4' height='4' style='fill:url(#g)'/></svg>");
        SvgDocument.DrawOp op = document.ops().get(0);
        assertNotNull("no start colours", op.colours());
        assertNotNull("the fill was flattened -- no end colour, so no per-pixel ramp", op.coloursEnd());
        assertNotNull("no gradient axis", op.gradients());

        int darkest = 0xFF, lightest = 0;
        for (int i = 0; i < op.colours().length; i++) {
            for (int argb : new int[]{op.colours()[i], op.coloursEnd()[i]}) {
                int red = (argb >>> 16) & 0xFF;
                darkest = Math.min(darkest, red);
                lightest = Math.max(lightest, red);
            }
        }
        assertTrue("the dark end never reached black, got " + darkest, darkest < 0x20);
        assertTrue("the light end never reached white, got " + lightest, lightest > 0xE0);
    }

    /**
     * <b>The stored axis maps each triangle's own span onto {@code [0, 1]}.</b>
     *
     * <p>The axis is the half of a per-pixel gradient that no colour check can catch: get its direction
     * or its scale wrong and every triangle still carries the right two colours, while the ramp inside
     * runs the wrong way, or clamps flat, or repeats. Evaluated here exactly as the fragment stage does.</p>
     */
    @Test
    public void theGradientAxisSpansEachTriangle() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 40 10'>"
                        + "<linearGradient id='g' gradientUnits='userSpaceOnUse' x1='0' y1='0'"
                        + " x2='40' y2='0'>"
                        + "<stop offset='0' style='stop-color:#000000'/>"
                        + "<stop offset='1' style='stop-color:#ffffff'/>"
                        + "</linearGradient>"
                        + "<polygon points='0,0 40,0 40,8 0,8' style='fill:url(#g)'/></svg>");
        SvgDocument.DrawOp op = document.ops().get(0);
        float[] axes = op.gradients();
        assertNotNull(axes);

        float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
        for (int i = 0; i < op.colours().length; i++) {
            float ox = axes[i * 4], oy = axes[i * 4 + 1];
            float dx = axes[i * 4 + 2], dy = axes[i * 4 + 3];
            for (int v = 0; v < 6; v += 2) {
                float t = (op.data()[i * 6 + v] - ox) * dx + (op.data()[i * 6 + v + 1] - oy) * dy;
                assertTrue("t = " + t + " is outside the triangle's own span",
                        t >= -0.01f && t <= 1.01f);
                lowest = Math.min(lowest, t);
                highest = Math.max(highest, t);
            }
        }
        assertTrue("no vertex sat at the ramp's start, lowest was " + lowest, lowest < 0.01f);
        assertTrue("no vertex sat at the ramp's end, highest was " + highest, highest > 0.99f);
    }

    /**
     * <b>A {@code userSpaceOnUse} axis is read in the shape's own space, not the shape's bounding box.</b>
     *
     * <p>This is the distinction that made the JetBrains mark come out brown. Its gradients state axes up
     * to 87 units long for shapes half that size, so each shape occupies a slice of the ramp — and the
     * colours it should show have nothing to do with the ramp's middle. A shape sitting in the FIRST
     * quarter of an axis must be dark throughout; treating the axis as the bounding box would run the full
     * ramp across it instead.</p>
     */
    @Test
    public void aUserSpaceAxisIsNotTheShapesBoundingBox() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 40 10'>"
                        + "<linearGradient id='g' gradientUnits='userSpaceOnUse' x1='0' y1='0'"
                        + " x2='40' y2='0'>"
                        + "<stop offset='0' style='stop-color:#000000'/>"
                        + "<stop offset='1' style='stop-color:#ffffff'/>"
                        + "</linearGradient>"
                        + "<rect x='0' y='0' width='10' height='4' style='fill:url(#g)'/></svg>");
        int[] colours = document.ops().get(0).colours();
        assertNotNull(colours);
        for (int argb : colours) {
            int red = (argb >>> 16) & 0xFF;
            assertTrue("a shape in the first quarter of the axis showed " + red, red < 0x50);
        }
    }

    /**
     * <b>{@code currentColor} is left unresolved in the cache and bound at draw time.</b>
     *
     * <p>A document is parsed once and shared; the same icon is routinely drawn in two colours in one frame
     * — a selected row and an unselected one. Baking the tint into the op would make the second draw take
     * the first one's colour.</p>
     */
    @Test
    public void currentColorIsLateBound() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 10 10' stroke='currentColor' fill='none'>"
                        + "<line x1='0' y1='0' x2='9' y2='9'/></svg>");
        assertEquals(1, document.ops().size());
        assertTrue("the tint hook was resolved away at parse time",
                document.ops().get(0).currentColor());
    }

    /** An element with no fill and no stroke is not geometry worth keeping. */
    @Test
    public void aShapePaintedWithNothingProducesNoOps() {
        assertTrue(SvgDocument.parse("<svg viewBox='0 0 10 10'>"
                + "<rect x='0' y='0' width='4' height='4' fill='none' stroke='none'/></svg>")
                .ops().isEmpty());
    }

    /** A bare path with no presentation attributes at all is a black FILL, which is SVG's own default. */
    @Test
    public void anUnstyledPathFillsRatherThanStrokes() {
        SvgDocument document = SvgDocument.parse(
                "<svg viewBox='0 0 10 10'><path d='M0 0 L8 0 L8 8 Z'/></svg>");
        assertEquals(1, document.ops().size());
        assertTrue("a bare path came out as a wireframe", document.ops().get(0).fill());
        assertEquals(0xFF000000, document.ops().get(0).argb());
    }

    /**
     * <b>The JetBrains mark — the file this pass exists for.</b>
     *
     * <p>Every feature at once and nothing else in the shipped set exercising any of it: nested
     * {@code <g>}, five inline {@code linearGradient}s, {@code style="fill:url(#…)"} on every shape, and
     * geometry that is 100% filled polygons with no stroke anywhere. The old loader drew it as nothing.</p>
     */
    @Test
    public void theJetBrainsMarkLoadsAsFilledPolygons() {
        SvgDocument logo = SvgDocument.load("crystalgui:ui/icons/IntelliJ_IDEA_Icon.svg");
        assertNotNull(logo);
        assertEquals("viewBox", 70f, logo.width(), 0.01f);
        assertTrue("no fills -- the gradients or the polygons did not resolve",
                logo.triangleCount() > 0);
        assertEquals("one op per shape in the file", 8, logo.ops().size());

        // The four gradient polygons come first, the black square and the three white glyph parts after.
        // Asserting the SPLIT, not just a count: a gradient that failed to resolve still emits an op, and
        // it emits a flat one -- so "8 ops" alone passes on a logo painted entirely in fallback grey.
        int ramped = 0;
        for (SvgDocument.DrawOp op : logo.ops()) {
            assertTrue("the mark is filled artwork; a stroke op means a fill was missed", op.fill());
            assertTrue("a shape fell back to the unresolved-paint grey",
                    op.argb() != 0xFF808080);
            if (op.colours() != null) ramped++;

            // Inside its own box. A mishandled transform still produces triangles -- they simply fly off --
            // so a count alone passes on geometry that renders as a spray across the screen.
            for (float v : op.data()) {
                assertTrue("geometry escaped the viewBox at " + v, v >= -1f && v <= 71f);
            }
        }
        assertEquals("the four gradient polygons", 4, ramped);
    }

    /**
     * <b>A hole is cut, not filled.</b>
     *
     * <p>The single reason this is a scanline decomposition and not ear clipping. A ring drawn as a disc is
     * the difference between a recognisable "O" and a blob, and it is the failure every naive triangulator
     * has. Sampled at the centre: a point inside the inner contour must be covered by no triangle.</p>
     */
    @Test
    public void anInnerContourIsSubtractedFromItsOuterOne() {
        List<List<float[]>> ring = List.of(
                square(0, 0, 10),
                square(3, 3, 4));
        float[] triangles = SvgTriangulator.fill(ring, true);
        assertTrue("nothing was filled at all", triangles.length > 0);
        assertTrue("the hole was filled in", !covers(triangles, 5f, 5f));
        assertTrue("the body between the contours was dropped", covers(triangles, 1.5f, 5f));
    }

    /** {@code nonzero} with two same-wound contours fills the middle; {@code evenodd} does not. */
    @Test
    public void theFillRuleDecidesWhetherAHoleIsAHole() {
        List<List<float[]>> ring = List.of(square(0, 0, 10), square(3, 3, 4));
        assertTrue("nonzero must fill through a same-wound inner contour",
                covers(SvgTriangulator.fill(ring, false), 5f, 5f));
    }

    /**
     * <b>A self-intersecting contour fills its real area, not a bowtie.</b>
     *
     * <p>A crossing is not a vertex, so nothing cuts a band there unless something goes looking — and the
     * band that contains the crossing has its left and right walls trade places partway down. The trapezoid
     * built from them covers a wedge outside the shape and leaves a matching wedge inside it bare.</p>
     *
     * <p>The figure-of-eight below is the smallest thing that shows it: two lobes joined at a crossing in
     * the middle. Sampled inside the upper lobe and outside both, in the pocket the bowtie wrongly
     * covers.</p>
     */
    @Test
    public void aSelfIntersectingContourIsCutAtItsCrossing() {
        // (0,0) -> (10,10) -> (0,10) -> (10,0) -> close. The two diagonals cross at (5,5).
        List<List<float[]>> bowtie = List.of(List.of(
                new float[]{0, 0}, new float[]{10, 10},
                new float[]{0, 10}, new float[]{10, 0}));
        float[] triangles = SvgTriangulator.fill(bowtie, false);

        assertTrue("the upper lobe was not filled", covers(triangles, 5f, 2f));
        assertTrue("the lower lobe was not filled", covers(triangles, 5f, 8f));
        assertTrue("the bowtie spilled into the left pocket", !covers(triangles, 1f, 5f));
        assertTrue("the bowtie spilled into the right pocket", !covers(triangles, 9f, 5f));
    }

    private static List<float[]> square(float x, float y, float size) {
        return List.of(new float[]{x, y}, new float[]{x + size, y},
                new float[]{x + size, y + size}, new float[]{x, y + size});
    }

    private static boolean covers(float[] triangles, float px, float py) {
        for (int i = 0; i < triangles.length; i += 6) {
            float d1 = side(px, py, triangles[i], triangles[i + 1], triangles[i + 2], triangles[i + 3]);
            float d2 = side(px, py, triangles[i + 2], triangles[i + 3], triangles[i + 4], triangles[i + 5]);
            float d3 = side(px, py, triangles[i + 4], triangles[i + 5], triangles[i], triangles[i + 1]);
            boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
            boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
            if (!(negative && positive)) return true;
        }
        return false;
    }

    private static float side(float px, float py, float ax, float ay, float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }
}
