package com.crystalgui.ui.elements.desktop;

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
 * <h3>The top band is the CAPTION's height, and it is derived rather than chosen</h3>
 *
 * <p>The top cannot use {@link #EDGE}, and the reason is a real constraint rather than a preference: the
 * pointer sits at a fixed offset <em>inside the caption</em> for the whole of a drag, and the window is
 * clamped so the caption's top never goes above the work area. Grab a title bar ten pixels down and the
 * pointer can never reach {@code y <= 2} however hard it is pushed — top-edge maximise would simply be
 * unreachable for every grab but the shallowest.</p>
 *
 * <p>Measuring against the caption's own height disposes of that, and it is not a fudge: because the
 * pointer is inside the caption by construction, <b>"the pointer is within one caption of the top" and
 * "the window's top edge has reached the top" are the same statement</b>. The two rules agree because
 * the caption is what bounds them together — which is also why the horizontal case cannot be done this
 * way. A 600px-wide window's left edge arrives at the boundary long before the hand does, so there the
 * pointer is the only honest question.</p>
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

    /** Which zone a drag is over, or none. */
    public enum Zone {
        /** The left half of the work area. */
        LEFT,
        /** The right half. */
        RIGHT,
        /** The top-left quarter. */
        TOP_LEFT,
        /** The top-right quarter. */
        TOP_RIGHT,
        /** The bottom-left quarter. */
        BOTTOM_LEFT,
        /** The bottom-right quarter. */
        BOTTOM_RIGHT,
        /** The whole of it — the same thing maximise does. */
        MAXIMIZE
    }

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
     *
     * @param topBand how far down the top edge still counts as the top — the CAPTION's height; see the
     *                class note for why this one is derived and the sides are not
     */
    @Nullable
    public static Zone forPoint(float pointerX, float pointerY,
                                float areaWidth, float areaHeight, float topBand) {
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

        if (pointerY <= topBand) return Zone.MAXIMIZE;
        return null;
    }

    /**
     * The rect {@code zone} puts a window in: {left, top, width, height} in the work area's space.
     *
     * <p>Halves are computed with the <b>far side taking the remainder</b> rather than both taking
     * {@code size / 2}: an odd work area would otherwise leave a one-pixel line of desktop showing down
     * the middle, which is the sort of thing that looks like a rendering bug.</p>
     */
    public static float[] rectFor(Zone zone, float areaWidth, float areaHeight) {
        float halfW = (float) Math.floor(areaWidth / 2f);
        float halfH = (float) Math.floor(areaHeight / 2f);
        float restW = areaWidth - halfW;
        float restH = areaHeight - halfH;
        switch (zone) {
            case LEFT:
                return new float[] {0f, 0f, halfW, areaHeight};
            case RIGHT:
                return new float[] {halfW, 0f, restW, areaHeight};
            case TOP_LEFT:
                return new float[] {0f, 0f, halfW, halfH};
            case TOP_RIGHT:
                return new float[] {halfW, 0f, restW, halfH};
            case BOTTOM_LEFT:
                return new float[] {0f, halfH, halfW, restH};
            case BOTTOM_RIGHT:
                return new float[] {halfW, halfH, restW, restH};
            default:
                return new float[] {0f, 0f, areaWidth, areaHeight};
        }
    }
}
