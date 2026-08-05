package com.crystalgui.ui;

import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgPath;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The shipped {@code .svg} icons, loaded as assets.
 *
 * <p>Here rather than in {@code headlessTest} because {@code SvgDocument.load} reaches {@code CgIO},
 * which is CrystalGraphics <b>core</b> and deliberately off that classpath — a dedicated server has no
 * resource manager. {@code SvgPath.parse} stays headless; only the loading half needs a real one.</p>
 */
public class SvgIconAssetTest {

    private static final String[] ICONS = {"folder", "file-text", "image", "code", "package"};

    /**
     * <b>Every shipped icon loads, and every one of them draws something.</b>
     *
     * <p>The second half is the assertion that matters. An icon set is not all {@code <path>} — Feather's
     * {@code image} is a rounded rect, a circle and a polyline; {@code code} is two polylines and no path
     * at all. A loader handling only paths still returns a document for each of these and draws two
     * thirds of the set as nothing, which reads as a rendering bug rather than a loader gap.</p>
     */
    @Test
    public void everyShippedIconLoadsWithGeometry() {
        for (String name : ICONS) {
            SvgDocument icon = SvgDocument.load("crystalgui:ui/icons/" + name + ".svg");
            assertNotNull(name + " did not load", icon);
            assertTrue(name + " loaded with no geometry at all", !icon.isEmpty());
            assertEquals(name + " lost its viewBox", 24f, icon.width(), 0.01f);

            int points = 0;
            for (SvgPath.Polyline line : icon.outline()) points += line.points().size();
            // 4, not some larger round number:  is genuinely two three-point chevrons, and a
            // threshold picked for the ornate icons fails the plain ones. The point of this bound is to
            // catch a document that loaded and produced nothing, not to grade artwork.
            assertTrue(name + " drew only " + points + " points", points >= 4);

            // Inside its own box. A mishandled element still produces points -- they simply fly off --
            // so a count alone passes on geometry that renders as a spray across the screen.
            for (SvgPath.Polyline line : icon.outline()) {
                for (float[] point : line.points()) {
                    assertTrue(name + " escaped its viewBox at x=" + point[0],
                            point[0] >= -0.5f && point[0] <= 24.5f);
                    assertTrue(name + " escaped its viewBox at y=" + point[1],
                            point[1] >= -0.5f && point[1] <= 24.5f);
                }
            }
        }
    }

    /** A polyline-only icon has no path element at all, and must still produce runs. */
    @Test
    public void anIconWithNoPathElementStillDraws() {
        SvgDocument code = SvgDocument.load("crystalgui:ui/icons/code.svg");
        assertEquals("both chevrons", 2, code.outline().size());
    }

    /** The mixed one: a rect, a circle and a polyline in a single file. */
    @Test
    public void anIconMixingElementKindsKeepsAllOfThem() {
        SvgDocument image = SvgDocument.load("crystalgui:ui/icons/image.svg");
        assertEquals("rect + circle + polyline", 3, image.outline().size());
    }

    /**
     * <b>Every element in a file becomes a run, including the {@code <line>}s.</b>
     *
     * <p>The regression that got past the first pass: the attribute pattern excluded digits, so
     * {@code x1}/{@code y1}/{@code x2}/{@code y2} never matched and every {@code <line>} collapsed to a
     * zero-length segment at the origin. Counting runs alone did not catch it — the lines were still
     * <em>there</em>, just degenerate. The earlier tests happened to use only {@code image} and
     * {@code code}, whose attributes carry no digits at all.</p>
     */
    @Test
    public void everyElementProducesARunOfRealLength() {
        assertEquals("path + polyline + line + line + polyline",
                5, SvgDocument.load("crystalgui:ui/icons/file-text.svg").outline().size());
        assertEquals("line + path + polyline + line",
                4, SvgDocument.load("crystalgui:ui/icons/package.svg").outline().size());

        for (String name : ICONS) {
            SvgDocument icon = SvgDocument.load("crystalgui:ui/icons/" + name + ".svg");
            for (SvgPath.Polyline line : icon.outline()) {
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (float[] point : line.points()) {
                    minX = Math.min(minX, point[0]);
                    maxX = Math.max(maxX, point[0]);
                    minY = Math.min(minY, point[1]);
                    maxY = Math.max(maxY, point[1]);
                }
                assertTrue(name + " has a run of zero extent -- an element whose attributes did not parse",
                        (maxX - minX) > 0.01f || (maxY - minY) > 0.01f);
            }
        }
    }

    /**
     * <b>A viewBox states an origin, not only a size.</b>
     *
     * <p>An icon authored as {@code "-2 -2 28 28"} — which is how a set gives itself padding — draws
     * offset by exactly that origin if only the width and height are read. Every Feather icon is
     * {@code "0 0 24 24"}, so nothing in the shipped set can reveal it.</p>
     */
    @Test
    public void aViewBoxOriginIsSubtractedRatherThanIgnored() {
        SvgDocument shifted = SvgDocument.parse(
                "<svg viewBox=\"-2 -2 28 28\"><line x1=\"-2\" y1=\"-2\" x2=\"26\" y2=\"26\"/></svg>");
        assertEquals(28f, shifted.width(), 0.01f);
        float[] first = shifted.outline().get(0).points().get(0);
        assertEquals("the origin was not subtracted", 0f, first[0], 0.01f);
        assertEquals(0f, first[1], 0.01f);
    }
}
