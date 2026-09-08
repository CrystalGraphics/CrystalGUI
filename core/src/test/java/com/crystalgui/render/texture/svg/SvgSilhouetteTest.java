package com.crystalgui.render.texture.svg;

import com.crystalgraphics.gl.render.CgVectorRenderer;

import org.junit.Test;

import java.util.List;

import static com.crystalgui.render.texture.svg.SvgTriangulator.BOTTOM;
import static com.crystalgui.render.texture.svg.SvgTriangulator.LEFT;
import static com.crystalgui.render.texture.svg.SvgTriangulator.RIGHT;
import static com.crystalgui.render.texture.svg.SvgTriangulator.TOP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Which cell edges are on the outline, on the one shape that has every case: an L, whose upper cell has
 * a bottom that is half seam and half silhouette.
 */
public class SvgSilhouetteTest {

    private static final List<List<float[]>> L = List.of(List.of(
            new float[]{0, 0}, new float[]{4, 0}, new float[]{4, 2},
            new float[]{2, 2}, new float[]{2, 4}, new float[]{0, 4}));

    @Test
    public void aRectangleIsOneCellWithEveryEdgeOnTheOutline() {
        SvgTriangulator.Fill fill = SvgTriangulator.fill(List.of(List.of(
                new float[]{0, 0}, new float[]{3, 0}, new float[]{3, 2}, new float[]{0, 2})), false, 0f, 0f);
        assertEquals(1, fill.count());
        assertEquals(TOP | RIGHT | BOTTOM | LEFT, fill.edges()[0]);
    }

    @Test
    public void aStepSplitsTheCellWhereTheShapeStopsContinuing() {
        SvgTriangulator.Fill fill = SvgTriangulator.fill(L, false, 0f, 0f);
        assertEquals("upper band in two pieces, lower band in one", 3, fill.count());

        // Upper band, left piece: sits on the lower band, so its bottom is a seam.
        assertArrayEquals(new float[]{0, 0, 2, 0, 2, 2, 0, 2}, cell(fill, 0), 0f);
        assertEquals(TOP | LEFT, fill.edges()[0]);
        // Upper band, right piece: nothing below it.
        assertArrayEquals(new float[]{2, 0, 4, 0, 4, 2, 2, 2}, cell(fill, 1), 0f);
        assertEquals(TOP | RIGHT | BOTTOM, fill.edges()[1]);
        // Lower band: its top is the seam.
        assertArrayEquals(new float[]{0, 2, 2, 2, 2, 4, 0, 4}, cell(fill, 2), 0f);
        assertEquals(RIGHT | BOTTOM | LEFT, fill.edges()[2]);
    }

    /**
     * The mask travels from the tessellator into {@code CgVectorRenderer.Cell#softEdges} untranslated,
     * and the tessellator may not import the renderer to say so — this is where the two are held equal.
     */
    @Test
    public void theEdgeBitsAreTheRenderersEdgeBits() {
        assertEquals(CgVectorRenderer.CELL_TOP, TOP);
        assertEquals(CgVectorRenderer.CELL_RIGHT, RIGHT);
        assertEquals(CgVectorRenderer.CELL_BOTTOM, BOTTOM);
        assertEquals(CgVectorRenderer.CELL_LEFT, LEFT);
    }

    @Test
    public void thePiecesOfASplitCellKeepItsSliceTag() {
        SvgTriangulator.Fill fill = SvgTriangulator.fill(L, false, 0f, 0f);
        assertEquals(fill.slice()[0], fill.slice()[1]);
    }

    private static float[] cell(SvgTriangulator.Fill fill, int index) {
        float[] out = new float[8];
        System.arraycopy(fill.cells(), index * 8, out, 0, 8);
        return out;
    }
}
