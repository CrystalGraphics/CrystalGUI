package com.crystalgui.ui.elements.slot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The tile grid a native fill lays out, both ways round.
 *
 * <p>This exists because the arithmetic is four lines that are invisible when wrong: every assertion
 * below passes trivially for an extent that is a whole number of tiles, which is what a tank at 100%
 * is, which is what anybody checks first. The cases that matter are the ones with a remainder.</p>
 */
public class NativeTileGridTest {

    private static final float TILE = 16f;

    @Test
    public void anExtentIsCoveredByCeilingTiles() {
        assertEquals(3, NativeTileGrid.count(40f, TILE));
        assertEquals(2, NativeTileGrid.count(32f, TILE));
        assertEquals(3, NativeTileGrid.count(33f, TILE));
        assertEquals(1, NativeTileGrid.count(8f, TILE));
        assertEquals(1, NativeTileGrid.count(16f, TILE));
    }

    /** Nothing to draw is spelled as no tiles, not as one degenerate one. */
    @Test
    public void anEmptyExtentHasNoTiles() {
        assertEquals(0, NativeTileGrid.count(0f, TILE));
        assertEquals(0, NativeTileGrid.count(-4f, TILE));
    }

    /**
     * The modulo trap. {@code extent % tile} is <b>zero</b> when the extent divides exactly, so a
     * remainder computed that way draws the last tile of an exactly-full tank at zero width — which is
     * the one fill level everybody eyeballs first and the one this would break.
     */
    @Test
    public void anExactlyFittingExtentHasNoPartialTile() {
        assertEquals(16f, NativeTileGrid.sizeOf(32f, TILE, 0), 0.001f);
        assertEquals("not zero", 16f, NativeTileGrid.sizeOf(32f, TILE, 1), 0.001f);
        assertEquals(16f, NativeTileGrid.sizeOf(16f, TILE, 0), 0.001f);
    }

    @Test
    public void onlyTheLastTileFromTheAnchorIsCut() {
        assertEquals(16f, NativeTileGrid.sizeOf(40f, TILE, 0), 0.001f);
        assertEquals(16f, NativeTileGrid.sizeOf(40f, TILE, 1), 0.001f);
        assertEquals(8f, NativeTileGrid.sizeOf(40f, TILE, 2), 0.001f);
        assertEquals("an extent under one tile is one cut tile", 8f,
                NativeTileGrid.sizeOf(8f, TILE, 0), 0.001f);
    }

    /** Anchored at the near edge: whole tiles first, remainder against the far edge. */
    @Test
    public void aNearAnchoredGridPutsTheRemainderAtTheFarEdge() {
        assertEquals(0f, NativeTileGrid.startOf(40f, TILE, 0, false), 0.001f);
        assertEquals(16f, NativeTileGrid.startOf(40f, TILE, 1, false), 0.001f);
        assertEquals("the cut tile ends flush with the extent", 32f,
                NativeTileGrid.startOf(40f, TILE, 2, false), 0.001f);
    }

    /**
     * Anchored at the far edge: the same grid mirrored, remainder against the near edge.
     *
     * <p>Indices are <em>not</em> renumbered — index 0 is still a whole tile, it just lands at the far
     * end. Getting that backwards is the shape of mistake that yields a correct-looking tank whose
     * partial tile is on the wrong side.</p>
     */
    @Test
    public void aFarAnchoredGridPutsTheRemainderAtTheNearEdge() {
        assertEquals(24f, NativeTileGrid.startOf(40f, TILE, 0, true), 0.001f);
        assertEquals(8f, NativeTileGrid.startOf(40f, TILE, 1, true), 0.001f);
        assertEquals("the cut tile starts flush with the near edge", 0f,
                NativeTileGrid.startOf(40f, TILE, 2, true), 0.001f);
    }

    /**
     * The property that actually matters: whichever end it is anchored to, the tiles tile — no gap, no
     * overlap, no spill. Checked across a sweep of extents rather than the two hand-written above,
     * because an off-by-one here shows up only at some remainders.
     */
    @Test
    public void tilesAreContiguousAndCoverExactlyTheExtentEitherWay() {
        for (float extent = 1f; extent <= 80f; extent += 1f) {
            for (boolean fromFar : new boolean[] { false, true }) {
                int count = NativeTileGrid.count(extent, TILE);
                float[] edges = new float[count + 1];
                for (int i = 0; i < count; i++) {
                    float start = NativeTileGrid.startOf(extent, TILE, i, fromFar);
                    float size = NativeTileGrid.sizeOf(extent, TILE, i);
                    // Index i lands at position (count - 1 - i) when mirrored.
                    int slot = fromFar ? count - 1 - i : i;
                    edges[slot] = start;
                    edges[slot + 1] = start + size;
                }
                String at = "extent " + extent + (fromFar ? " (far)" : " (near)");
                assertEquals(at + " starts at 0", 0f, edges[0], 0.001f);
                assertEquals(at + " ends at the extent", extent, edges[count], 0.001f);
                for (int i = 1; i < count; i++) {
                    // Written back twice, once as a tile's end and once as its neighbour's start; equal
                    // means contiguous, and the two writes agreeing is what rules out a gap.
                    assertEquals(at + " boundary " + i + " is whole tiles from an end",
                            true, edges[i] > edges[i - 1]);
                }
            }
        }
    }

    @Test
    public void aNonPositiveTileIsRefusedRatherThanLoopingForever() {
        try {
            NativeTileGrid.count(16f, 0f);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // A zero tile size is an infinite tile count, so this cannot be allowed to degrade quietly.
        }
    }

    @Test
    public void anIndexOutsideTheGridIsRefused() {
        try {
            NativeTileGrid.sizeOf(40f, TILE, 3);
            fail("expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // 40 over 16 is three tiles, so index 3 is one past the end.
        }
    }
}
