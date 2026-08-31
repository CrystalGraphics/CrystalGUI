package com.crystalgui.desktop.window;

import javax.annotation.Nullable;

/**
 * Where a window lands when it is dragged at an edge — CrystalOS <b>W13b</b>.
 *
 * <h3>CONTACT, not proximity — the rule the first version got wrong</h3>
 *
 * <p>Windows fires Aero Snap when the <b>cursor reaches the last row of pixels</b> at the edge of the
 * monitor. Not when the window nears the edge, and not when the pointer enters a generous band: the
 * cursor has to make contact. The giveaway is what happens on a multi-monitor desktop — snapping works
 * on the far left and far right of the whole arrangement and <em>not</em> on the interior edges where
 * two monitors meet, because those have no final row of pixels to arrive at.</p>
 *
 * <p>That is what makes a 1px trigger feel deliberate rather than twitchy: <b>the monitor edge is a
 * physical wall.</b> The cursor cannot travel past it, so the gesture is to shove the pointer into the
 * stop and feel it hold. A wide band would fire while merely crossing the area.</p>
 *
 * <p>We have the same wall for the same reason — the pointer belongs to a host window and stops at its
 * frame — so {@link #EDGE} is small on purpose. It is also <b>unbounded outwards</b>: a pointer already
 * past the work area is asking for that edge at least as plainly as one two pixels inside it, and the
 * window clamp means the window stops travelling well before the hand does.</p>
 *
 * <h3>Quadrants, at KWin's published ratio</h3>
 *
 * <p>Contacting a side edge tiles to that half — unless the pointer is near one <em>end</em> of the
 * edge, which tiles to a quarter. KWin spells this as {@code ElectricBorderCornerRatio}, default
 * <b>0.25</b>: the outer quarter of a vertical edge, at each end, is a corner. Windows behaves the same
 * way without publishing a number for it.</p>
 *
 * <p>So the corner belongs to the QUARTER now, where an earlier revision of this class deliberately gave
 * it to maximise. That was right while there were no quarters — a corner with nothing of its own to do
 * would otherwise have made the top band unreachable from either end — and it is wrong the moment a
 * quarter exists, which is what dragging into a corner obviously means.</p>
 *
 * <h3>Every edge is read from the CURSOR, the top included</h3>
 *
 * <p>The top used to take the frame's CAPTION HEIGHT as its band, and the argument was a real constraint
 * rather than a preference: the pointer sits at a fixed offset <em>inside the caption</em> for the whole
 * of a drag, and a window clamped so its caption never leaves the work area holds that pointer a whole
 * caption below the border. Grab a title bar ten pixels down and it could never reach {@code y <= 2}.</p>
 *
 * <p>What that bought was the one edge triggered by the WINDOW rather than by the cursor — it fires the
 * moment the window's upper lip touches the border, while every other edge waits for the hand. The
 * inconsistency is what makes it feel wrong rather than merely eager.</p>
 *
 * <p>So the constraint is answered where it arises instead: {@code WindowFrame} lets the caption rise one
 * caption-height ABOVE the work area <b>while a move is live</b>, and withdraws the headroom when the
 * drag ends. Any grab can then bring the cursor to the border, and this class needs no special case for
 * the top at all. Windows does the same — drag a window up and its title bar goes off the top while the
 * cursor reaches the edge.
 *
 * <h3>Pure arithmetic, so it is testable without a desktop</h3>
 *
 * <p>The same shape as {@code RegionDropZones}, which answers the same kind of question for the dock.
 * Nothing here reads an element, so the awkward halves — a band measured against a work area that has
 * not been laid out, a zone chosen from a pointer in the wrong coordinate space — are reachable in a
 * unit test rather than only by dragging. The second of those had already shipped: the caller added the
 * title bar's own origin to a pointer that was <em>already</em> in absolute layout coordinates, so the
 * zone a drag reported depended on where the window happened to be.</p>
 */
public final class SnapZones {

    /**
     * How close to a side edge the pointer must be, in logical pixels — <b>contact, not proximity</b>.
     *
     * <p>Windows uses the last row of pixels; this is two, which is the same gesture with room for
     * rounding at a fractional {@code uiScale} and for the one frame of layout a live drag can lag by.
     * </p>
     */
    public static final float EDGE = 2f;

    /**
     * How much of each end of a side edge is a CORNER rather than the edge proper.
     *
     * <p>KWin's {@code ElectricBorderCornerRatio}, whose default is this number. Raising it makes
     * quarters easier to hit and the half harder; lowering it does the reverse. It is a fraction of the
     * work area's height, so it stays proportionate on any screen — which a pixel count would not.</p>
     */
    public static final float CORNER_RATIO = 0.25f;

    /**
     * Which zone a drag is over, or none — <b>as a pair of sides rather than as seven names</b>.
     *
     * <p>{@code xSide} is −1 for the left column, +1 for the right, 0 for the full width; {@code ySide}
     * says the same about rows. Every zone here is one cell of a 2x2 grid, so spelling them that way
     * turns {@link #rectFor} into arithmetic instead of a seven-arm switch — and, more usefully, makes
     * "which of my edges is a shared divider" answerable: the divider is the edge facing the middle, so
     * a handle moving it has {@code dx == -xSide}. That is what joint resize is asking, and it would be
     * a lookup table if the zones were only names.</p>
     */
    public enum Zone {
        /** The left half of the work area. */
        LEFT(-1, 0),
        /** The right half. */
        RIGHT(1, 0),
        /** The top-left quarter. */
        TOP_LEFT(-1, -1),
        /** The top-right quarter. */
        TOP_RIGHT(1, -1),
        /** The bottom-left quarter. */
        BOTTOM_LEFT(-1, 1),
        /** The bottom-right quarter. */
        BOTTOM_RIGHT(1, 1),
        /** The whole of it — the same thing maximise does. */
        MAXIMIZE(0, 0);

        /** −1 left column, +1 right column, 0 full width. */
        public final int xSide;
        /** −1 top row, +1 bottom row, 0 full height. */
        public final int ySide;

        Zone(int xSide, int ySide) {
            this.xSide = xSide;
            this.ySide = ySide;
        }

        /**
         * Whether a resize handle with this delta is moving a <b>shared divider</b> rather than an outer
         * edge of the work area.
         *
         * <p>A cell's divider is the edge facing the middle, so it is the one whose sign opposes the
         * side the cell is on: the left half's divider is its RIGHT edge. An outer edge answers false —
         * dragging the left edge of a left-snapped window is resizing one window, not repartitioning the
         * screen, and it must not drag anything else with it.</p>
         *
         * <p>A corner handle is both at once, which is what makes the centre of a four-window layout drag
         * both dividers — the case Windows 11 added and Windows 10 could not do.</p>
         */
        public boolean movesVerticalDivider(int handleDx) {
            return xSide != 0 && handleDx == -xSide;
        }

        /** @see #movesVerticalDivider */
        public boolean movesHorizontalDivider(int handleDy) {
            return ySide != 0 && handleDy == -ySide;
        }
    }

    /** The default divider position on either axis — halves, which is what a first snap means. */
    public static final float CENTRE_SPLIT = 0.5f;

    /**
     * How far a divider may travel, as a fraction.
     *
     * <p>Windows stops a joint resize at the smaller window's own minimum size, which is a per-window
     * answer we would have to ask Taffy for mid-drag. A flat fraction is the cruder rule and the honest
     * one to start from: it guarantees neither cell can be driven to nothing, which is the failure that
     * matters, and it is one number rather than a size negotiation between two windows.</p>
     */
    public static final float MIN_SPLIT = 0.15f;

    /** @see #MIN_SPLIT */
    public static final float MAX_SPLIT = 1f - MIN_SPLIT;

    private SnapZones() {
    }

    /**
     * The zone {@code (pointerX, pointerY)} is in, or null.
     *
     * <p>Coordinates and the work area are both in the desktop's own space — the space a frame's
     * {@code left}/{@code top} are written in. <b>A drag callback's coordinates are already in absolute
     * layout space</b> ({@code screenToLocal} converts out of surface pixels; it does not subtract the
     * source's own origin, which is why {@code isMouseOverElement} compares its argument against
     * {@code runtimeCache.getX()}), so a caller subtracts the work area's origin and nothing else.</p>
     *
     * <p>A non-positive work area answers null rather than guessing. Every rule that reads the work area
     * is guarded that way, because the layer measures 0x0 before its first layout and a zone chosen
     * against nothing would snap a window to a rect of nothing.</p>
     */
    @Nullable
    public static Zone forPoint(float pointerX, float pointerY,
                                float areaWidth, float areaHeight) {
        if (areaWidth <= 0f || areaHeight <= 0f) return null;
        // BOUNDED VERTICALLY AND NOT HORIZONTALLY, which is deliberate rather than an oversight.
        //
        // Past a side edge is still AT that edge -- a pointer dragged off the left of the work area is
        // asking for the left half as plainly as one two pixels inside it, and is the very gesture the
        // class note describes. Past the bottom is different: that is the taskbar, and a drag over the
        // strip is not a snap. The top is guarded for symmetry with it.
        if (pointerY < 0f || pointerY > areaHeight) return null;

        boolean atLeft = pointerX <= EDGE;
        boolean atRight = pointerX >= areaWidth - EDGE;
        if (atLeft || atRight) {
            // SIDES FIRST, so a pointer in a corner gets the QUARTER. The top edge is checked afterwards
            // and therefore means "the top, away from either end", which is what maximise should be.
            float corner = areaHeight * CORNER_RATIO;
            if (pointerY <= corner) return atLeft ? Zone.TOP_LEFT : Zone.TOP_RIGHT;
            if (pointerY >= areaHeight - corner) return atLeft ? Zone.BOTTOM_LEFT : Zone.BOTTOM_RIGHT;
            return atLeft ? Zone.LEFT : Zone.RIGHT;
        }

        // THE SAME THIN EDGE AS THE SIDES, because a snap zone is read from the POINTER.
        //
        // This used to take the frame's CAPTION HEIGHT as its band, which quietly turned the top zone
        // into the one edge that is read from the WINDOW instead: a caption is dragged from somewhere
        // inside it, so the pointer sits about a caption's height below the window's top edge, and a
        // band that tall fires exactly when the window's upper lip reaches the border. Every other edge
        // waits for the cursor itself, and the inconsistency is what makes it feel wrong rather than
        // merely eager.
        if (pointerY <= EDGE) return Zone.MAXIMIZE;
        return null;
    }

    /** The rect {@code zone} puts a window in, with the dividers at the centre. */
    public static float[] rectFor(Zone zone, float areaWidth, float areaHeight) {
        return rectFor(zone, areaWidth, areaHeight, CENTRE_SPLIT, CENTRE_SPLIT);
    }

    /**
     * The rect {@code zone} puts a window in: {left, top, width, height} in the work area's space, with
     * the dividers wherever the group has dragged them.
     *
     * <p>{@code splitX}/{@code splitY} are the <b>fractions</b> the work area is cut at, which is the
     * state joint resize actually keeps: two windows sharing an edge are {@code n} and {@code 1 − n} of
     * one axis, so storing the cut rather than two rects is what makes the pair unable to drift apart.
     * It is the same thing {@code SplitView} keeps for the same reason.</p>
     *
     * <p>The cut is <b>floored to a whole pixel and the far side takes the remainder</b>: both sides
     * rounding independently would leave a one-pixel line of desktop showing down the middle at odd
     * widths, which reads as a rendering bug rather than as arithmetic.</p>
     */
    public static float[] rectFor(Zone zone, float areaWidth, float areaHeight,
                                  float splitX, float splitY) {
        float cutX = (float) Math.floor(areaWidth * clampSplit(splitX));
        float cutY = (float) Math.floor(areaHeight * clampSplit(splitY));

        float left = zone.xSide > 0 ? cutX : 0f;
        float width = zone.xSide == 0 ? areaWidth : zone.xSide < 0 ? cutX : areaWidth - cutX;
        float top = zone.ySide > 0 ? cutY : 0f;
        float height = zone.ySide == 0 ? areaHeight : zone.ySide < 0 ? cutY : areaHeight - cutY;
        return new float[] {left, top, width, height};
    }

    /**
     * The fraction a divider would sit at if {@code zone} occupied {@code near..near + extent}.
     *
     * <p>The inverse of {@link #rectFor} on one axis, and it reads the edge facing the middle: a left
     * cell's divider is its far edge, a right cell's is its near one. Clamped, so a drag cannot push a
     * cell to nothing — see {@link #MIN_SPLIT}.</p>
     *
     * @param side {@code Zone.xSide} or {@code Zone.ySide}; 0 means the zone spans the axis and has no
     *             divider on it, which answers {@link #CENTRE_SPLIT} rather than dividing by nothing
     */
    public static float splitFor(int side, float near, float extent, float area) {
        if (area <= 0f || side == 0) return CENTRE_SPLIT;
        return clampSplit((side < 0 ? near + extent : near) / area);
    }

    private static float clampSplit(float split) {
        if (Float.isNaN(split)) return CENTRE_SPLIT;
        return Math.max(MIN_SPLIT, Math.min(MAX_SPLIT, split));
    }
}
