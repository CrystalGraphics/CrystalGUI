package com.crystalgui.ui.elements.slot;

/**
 * <b>Which corner of a {@link NativeSurface} a repeating pattern is pinned to.</b>
 *
 * <p>Only meaningful to an implementation that <em>tiles</em> — a fluid. An item is drawn once, filling
 * whatever box it is given, and never reads this. {@link #TOP_LEFT} is the default for exactly that
 * reason: it is what a surface answers when nobody has an opinion.</p>
 *
 * <h3>What it is for</h3>
 *
 * <p>{@link FluidSlot} narrows the content box to the filled portion before handing it over, so by the
 * time the host sees a surface, <b>one of its edges moves as the tank fills and the other three do
 * not</b> — and nothing in {@link NativeSurface} would otherwise say which. Pin the tile grid to the
 * moving edge and that edge is always a whole tile's edge, identical at every fill level, with the
 * remainder falling against a static edge where the slot's own border already is. Pin it to a static
 * edge instead and the <em>moving</em> one cuts through a tile, so the fluid's surface shows a different
 * slice of the sprite at every level and appears to shimmer as it fills. That was the reported defect,
 * and both of Tinkers' Construct's tank renderers avoid it the same way.</p>
 *
 * <p>Which is why this is on the surface rather than derived in the loader: after the narrowing, the
 * moving edge is unrecoverable from a width and a height.</p>
 *
 * <h3>It is a corner, not an edge</h3>
 *
 * <p>Both axes are answered at once because both can be the narrowed one — a horizontal gauge fills
 * sideways — and a tile grid has to start somewhere on each. The axis that is <em>not</em> being filled
 * takes the near end, which is the same answer it would have given before this existed.</p>
 */
public enum NativeAnchor {

    /** The default, and correct for a tank ({@link FluidSlot.FillDirection#BOTTOM_UP}). */
    TOP_LEFT(false, false),

    /** A gauge growing rightwards ({@link FluidSlot.FillDirection#LEFT_RIGHT}). */
    TOP_RIGHT(true, false),

    /** A draining reservoir ({@link FluidSlot.FillDirection#TOP_DOWN}). */
    BOTTOM_LEFT(false, true),

    /** No {@link FluidSlot.FillDirection} produces this today; here so the pair of axes is complete. */
    BOTTOM_RIGHT(true, true);

    private final boolean fromRight;
    private final boolean fromBottom;

    NativeAnchor(boolean fromRight, boolean fromBottom) {
        this.fromRight = fromRight;
        this.fromBottom = fromBottom;
    }

    /** Whether the horizontal grid starts at the box's right edge and the remainder falls on the left. */
    public boolean fromRight() {
        return fromRight;
    }

    /** Whether the vertical grid starts at the box's bottom edge and the remainder falls on the top. */
    public boolean fromBottom() {
        return fromBottom;
    }

    /** The corner for a box whose {@code right} and/or {@code bottom} edge is the one that moves. */
    public static NativeAnchor of(boolean fromRight, boolean fromBottom) {
        if (fromRight) return fromBottom ? BOTTOM_RIGHT : TOP_RIGHT;
        return fromBottom ? BOTTOM_LEFT : TOP_LEFT;
    }
}
