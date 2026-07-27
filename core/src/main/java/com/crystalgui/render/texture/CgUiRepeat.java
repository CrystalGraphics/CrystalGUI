package com.crystalgui.render.texture;

/**
 * How a 9-slice sprite's edge and centre regions fill their span — CSS {@code border-image-repeat},
 * with the same four values. Corners never tile under any mode.
 *
 * <p>Baked into the {@link CgUiSprite} at parse time (like its tint and crop rect), not a per-element
 * style property. Set via a trailing keyword on {@code sprite(...)} or the {@code repeat} field of a
 * sprite-pack JSON.</p>
 *
 * <p>The same {@link #tileCount} drives both renderers — {@link CgUiSprite}'s CPU quad loop and the
 * {@code WITH_9SLICE_FILL} branch of {@code gui_rounded_rect.shader}, which receives the count as a
 * uniform rather than recomputing it. Agreement is by construction: the two paths cannot round
 * differently.</p>
 */
public enum CgUiRepeat {
    /** Stretch one copy across the whole span. The default, and the engine's only behaviour before
     * this existed. */
    STRETCH,
    /** Tile at natural size; the last tile is clipped wherever the span runs out. */
    REPEAT,
    /** Tile at a size adjusted so a whole number of tiles fits the span exactly — no clipped tile. */
    ROUND,
    /** Tile at natural size, distributing the leftover space as equal gaps between (and around) the
     * tiles. Gap regions are not drawn. */
    SPACE;

    /** Never emit more tiles than this per axis, per region. A guard, not a design limit: the shared
     * quad index buffer tops out at 16384 quads and overflows by silently reading past the end of
     * the index buffer rather than throwing, so a pathological 1px slice on a huge box must not be
     * able to get there. */
    public static final int MAX_TILES_PER_AXIS = 1024;

    /**
     * Tiles needed to fill {@code span} with a source region {@code src} pixels long.
     *
     * <p>Fractional for {@link #REPEAT} (the remainder is the clipped final tile); a whole number for
     * {@link #ROUND} and {@link #SPACE}; always 1 for {@link #STRETCH}.</p>
     *
     * <p>Degenerate inputs collapse to 1 (i.e. stretch): a non-positive source size, or {@link #SPACE}
     * when not even one tile fits. CSS says {@code space} draws nothing in that case; falling back is
     * both friendlier and consistent with how the rest of this engine degrades (e.g.
     * {@code overlay-fit} falls back to {@code fill} when a drawable has no intrinsic size).</p>
     */
    public float tileCount(float span, float src) {
        if (this == STRETCH || src <= 0f || span <= 0f) return 1f;
        float raw = span / src;
        float count = switch (this) {
            case REPEAT -> raw;
            case ROUND -> Math.max(1f, Math.round(raw));
            case SPACE -> (float) Math.floor(raw);
            default -> 1f;
        };
        if (count < 1f) return 1f; // SPACE with no room for a whole tile
        return Math.min(count, MAX_TILES_PER_AXIS);
    }

    /** Gap inserted before, between, and after tiles under {@link #SPACE}; 0 for every other mode. */
    public float gap(float span, float src, float tileCount) {
        if (this != SPACE || tileCount < 1f || src <= 0f) return 0f;
        float used = tileCount * src;
        if (used >= span) return 0f;
        return (span - used) / (tileCount + 1f);
    }

    /** Case-insensitive keyword lookup, {@code null} when unrecognised so callers can warn and fall
     * back rather than throwing. */
    public static CgUiRepeat parse(String keyword) {
        if (keyword == null) return null;
        for (CgUiRepeat mode : values()) {
            if (mode.name().equalsIgnoreCase(keyword.trim())) return mode;
        }
        return null;
    }
}
