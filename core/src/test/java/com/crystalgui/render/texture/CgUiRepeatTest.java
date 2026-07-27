package com.crystalgui.render.texture;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The tile-count math is the single source of truth shared by both 9-slice renderers — the CPU quad
 * loop in {@link CgUiSprite} and the {@code WITH_9SLICE_FILL} shader branch, which receives these
 * counts as uniforms rather than recomputing them. If this drifts, the two paths silently disagree
 * and an element's tiling changes depending on whether it has a border-radius.
 */
public class CgUiRepeatTest {

    private static final float EPS = 0.001f;

    @Test
    public void stretchIsAlwaysASingleTile() {
        assertEquals(1f, CgUiRepeat.STRETCH.tileCount(100f, 16f), EPS);
        assertEquals(1f, CgUiRepeat.STRETCH.tileCount(7f, 16f), EPS);
    }

    /** repeat keeps the natural tile size, so the count is fractional — the remainder is the
     * clipped final tile. */
    @Test
    public void repeatKeepsNaturalSizeAndReportsAFractionalCount() {
        assertEquals(6.25f, CgUiRepeat.REPEAT.tileCount(100f, 16f), EPS);
    }

    /** round snaps to a whole number of tiles so none is clipped; 100/16 = 6.25 rounds to 6. */
    @Test
    public void roundSnapsToTheNearestWholeTileCount() {
        assertEquals(6f, CgUiRepeat.ROUND.tileCount(100f, 16f), EPS);
        // 90/16 = 5.625 rounds up to 6
        assertEquals(6f, CgUiRepeat.ROUND.tileCount(90f, 16f), EPS);
    }

    /** space fits only whole tiles at natural size; 100/16 = 6.25 floors to 6. */
    @Test
    public void spaceFloorsToWholeTiles() {
        assertEquals(6f, CgUiRepeat.SPACE.tileCount(100f, 16f), EPS);
    }

    /** Leftover space is split into n+1 gaps — before, between, and after the tiles. */
    @Test
    public void spaceDistributesLeftoverAsEqualGaps() {
        float n = CgUiRepeat.SPACE.tileCount(100f, 16f); // 6 tiles = 96px, 4px left over
        assertEquals(4f / 7f, CgUiRepeat.SPACE.gap(100f, 16f, n), EPS);
    }

    @Test
    public void onlySpaceProducesGaps() {
        assertEquals(0f, CgUiRepeat.STRETCH.gap(100f, 16f, 1f), EPS);
        assertEquals(0f, CgUiRepeat.REPEAT.gap(100f, 16f, 6.25f), EPS);
        assertEquals(0f, CgUiRepeat.ROUND.gap(100f, 16f, 6f), EPS);
    }

    /** Degenerate inputs collapse to a single stretched tile rather than dividing by zero or
     * (for space) drawing nothing as CSS would. Deliberate deviation — matches how the rest of the
     * engine degrades, e.g. overlay-fit falling back to fill. */
    @Test
    public void degenerateInputsFallBackToASingleTile() {
        assertEquals(1f, CgUiRepeat.REPEAT.tileCount(100f, 0f), EPS);
        assertEquals(1f, CgUiRepeat.ROUND.tileCount(100f, -4f), EPS);
        assertEquals(1f, CgUiRepeat.SPACE.tileCount(0f, 16f), EPS);
        // space with a tile bigger than the span: CSS draws nothing, we stretch one tile
        assertEquals(1f, CgUiRepeat.SPACE.tileCount(10f, 16f), EPS);
    }

    /** Guards the 16384-quad index buffer, which overflows by reading past its own end rather than
     * throwing — a 1px slice on a huge box must not be able to get there. */
    @Test
    public void tileCountIsClampedToTheQuadBudget() {
        assertEquals(CgUiRepeat.MAX_TILES_PER_AXIS,
                CgUiRepeat.REPEAT.tileCount(100000f, 1f), EPS);
    }

    @Test
    public void parseIsCaseInsensitiveAndReturnsNullForUnknown() {
        assertEquals(CgUiRepeat.ROUND, CgUiRepeat.parse("round"));
        assertEquals(CgUiRepeat.SPACE, CgUiRepeat.parse("  SPACE "));
        assertNull(CgUiRepeat.parse("wrap"));
        assertNull(CgUiRepeat.parse(null));
    }
}
