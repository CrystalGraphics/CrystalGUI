package com.crystalgui.ui.elements.slot;

/**
 * <b>Where the tiles go, for a {@link NativeContentService} that fills a {@link NativeSurface} with a
 * repeating pattern.</b>
 *
 * <p>One axis at a time, and pure — no GL, no Minecraft, no atlas. It lives in {@code core} rather than
 * beside the renderer that uses it for the same reason {@link FluidSlot#fillBox} does: it is the part
 * that can be wrong by one in a way a screenshot will not show, and the loader modules have no test
 * source set to pin it in.</p>
 *
 * <h3>The grid runs from the anchor; the remainder falls at the far end</h3>
 *
 * <p>Tiles are indexed <b>from the anchored end</b>, so index 0 is always a whole tile (unless the whole
 * extent is smaller than one) and only the last index is ever cut. {@code fromFar} does not renumber
 * them — it mirrors where they land, so the cut tile ends up against the near edge instead of the far
 * one. See {@link NativeAnchor} for why an implementation is told which end to pin to rather than
 * deciding for itself.</p>
 *
 * <pre>
 *   extent 40, tile 16          fromFar = false          fromFar = true
 *   index 0                     [ 0, 16)                 [24, 40)
 *   index 1                     [16, 32)                 [ 8, 24)
 *   index 2  (cut, 8 wide)      [32, 40)                 [ 0,  8)
 * </pre>
 */
public final class NativeTileGrid {

    private NativeTileGrid() {
    }

    /**
     * How many tiles cover {@code extent}, the last one possibly partial.
     *
     * <p>Zero for an empty or negative extent, so a caller's loop does nothing rather than drawing a
     * degenerate tile. {@code Math.ceil} rather than a rounded division: 40 over 16 is three tiles, not
     * two and not three-and-a-bit.</p>
     */
    public static int count(float extent, float tile) {
        requirePositiveTile(tile);
        if (!(extent > 0f)) return 0;
        return (int) Math.ceil(extent / tile);
    }

    /**
     * The size of tile {@code index}, counting from the anchored end. Only the last is ever cut.
     *
     * <p>The remainder is computed as {@code extent - (count - 1) * tile} rather than with a modulo,
     * because {@code extent % tile} is <b>zero</b> when the extent divides exactly — which would draw
     * the last tile of an exactly-fitting tank at zero width.</p>
     */
    public static float sizeOf(float extent, float tile, int index) {
        int count = count(extent, tile);
        requireIndex(index, count);
        return index == count - 1 ? extent - (count - 1) * tile : tile;
    }

    /**
     * Where tile {@code index} starts, in box coordinates from the near edge.
     *
     * <p>{@code fromFar} walks backwards from the far edge, which is the whole of what an anchor does:
     * the same grid, pinned at the other end, so the partial tile lands against the near edge.</p>
     */
    public static float startOf(float extent, float tile, int index, boolean fromFar) {
        float size = sizeOf(extent, tile, index);
        return fromFar ? extent - index * tile - size : index * tile;
    }

    /**
     * The low edge of the {@code [0..1]} slice of the sprite a tile of this size fraction shows,
     * per axis. {@link #uvHi} is its pair.
     *
     * <p><b>The sprite aligns to the anchored end of its own tile</b> — "repeat from the anchor, clip
     * at the far end" — which is what keeps every join seamless: a full tile ends where the cut tile
     * beyond it begins, so the pattern crosses that join exactly as it crosses every other one, and
     * the single discontinuity lands on the far edge, where the slot's border is.</p>
     *
     * <p>This is <em>not</em> the obvious reading. A tile truncated at its bottom edge "naturally"
     * keeps its top slice — that was the 1.7.10 renderer's first bug — and Tinkers' Construct itself
     * pins its vertical cut to {@code getMaxV()} while its own horizontal axis does the opposite
     * (and is never exercised, since {@code SmelteryGui} always passes a full-width tile). The rule
     * lives here so no loader gets a third chance to re-derive it.</p>
     *
     * <p>A consumer maps the fractions into its sprite's own interval:
     * {@code u = minU + (maxU - minU) * uv}. A full tile is {@code [0, 1]} under either anchor.</p>
     *
     * @param fraction the tile's size over the full tile size — {@code sizeOf(...) / tile}
     * @param fromFar  whether this axis's grid is anchored at the far edge ({@link NativeAnchor})
     */
    public static float uvLo(float fraction, boolean fromFar) {
        return fromFar ? 1f - clampFraction(fraction) : 0f;
    }

    /** The high edge of the slice — see {@link #uvLo}. */
    public static float uvHi(float fraction, boolean fromFar) {
        return fromFar ? 1f : clampFraction(fraction);
    }

    private static float clampFraction(float fraction) {
        if (!(fraction > 0f)) return 0f;
        return Math.min(1f, fraction);
    }

    private static void requirePositiveTile(float tile) {
        if (!(tile > 0f)) {
            throw new IllegalArgumentException("tile size must be positive, was " + tile);
        }
    }

    private static void requireIndex(int index, int count) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("tile " + index + " of " + count);
        }
    }
}
