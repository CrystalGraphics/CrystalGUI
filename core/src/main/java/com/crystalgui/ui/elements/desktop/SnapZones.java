package com.crystalgui.ui.elements.desktop;

import javax.annotation.Nullable;

/**
 * Where a window lands when it is dragged at an edge — CrystalOS <b>W13b</b>.
 *
 * <h3>Bands, not corners</h3>
 *
 * <p>Three zones: the left edge, the right edge, and the top. Left and right take half the work area;
 * the top maximises. Windows adds quarter-screen corners and a keyboard half (Win+arrows); the plan
 * keeps both deferred, because a corner zone has to be carved out of the two edge bands and makes the
 * halves harder to hit for a feature nobody has asked for.</p>
 *
 * <p><b>The top band is thinner than the sides.</b> A window is dragged by its caption, so the pointer
 * is already near the top of the work area for the whole of an ordinary move across the desktop — a
 * generous top band would maximise windows people were only rearranging. The side bands have no such
 * problem: reaching one means deliberately going there.</p>
 *
 * <h3>Pure arithmetic, so it is testable without a desktop</h3>
 *
 * <p>The same shape as {@code RegionDropZones}, which answers the same kind of question for the dock and
 * is a static function over a rect for the same reason. Nothing here reads an element, so the awkward
 * halves — a band measured against a work area that has not been laid out, a zone chosen from a pointer
 * in the wrong coordinate space — are reachable in a unit test rather than only by dragging.</p>
 */
public final class SnapZones {

    /** How far from a side edge the pointer must be, in logical pixels. */
    public static final float SIDE_BAND = 12f;

    /** @see SnapZones — deliberately thinner than {@link #SIDE_BAND}. */
    public static final float TOP_BAND = 6f;

    /** Which zone a drag is over, or none. */
    public enum Zone {
        /** The left half of the work area. */
        LEFT,
        /** The right half. */
        RIGHT,
        /** The whole of it — the same thing maximise does. */
        MAXIMIZE
    }

    private SnapZones() {
    }

    /**
     * The zone {@code (pointerX, pointerY)} is in, or null.
     *
     * <p>Coordinates and the work area are both in the desktop's own space — the space a frame's
     * {@code left}/{@code top} are written in. A drag callback's coordinates are already local to the
     * drag source, which is <em>not</em> that space, so a caller has to convert; that is the same
     * distinction the restore-drag pays for and the reason this takes plain numbers rather than an
     * event.</p>
     *
     * <p>A non-positive work area answers null rather than guessing. Every rule that reads the work area
     * is guarded that way, because the layer measures 0x0 before its first layout and a zone chosen
     * against nothing would snap a window to a rect of nothing.</p>
     */
    @Nullable
    public static Zone forPoint(float pointerX, float pointerY, float areaWidth, float areaHeight) {
        if (areaWidth <= 0f || areaHeight <= 0f) return null;
        if (pointerY < 0f || pointerY > areaHeight) return null;
        // TOP FIRST, so the corners belong to maximise rather than to a half. Dragging into the top-left
        // corner of the screen is how somebody maximises a window they are already moving leftwards, and
        // resolving it as LEFT there would make the top band unreachable from either end.
        if (pointerY <= TOP_BAND) return Zone.MAXIMIZE;
        if (pointerX <= SIDE_BAND) return Zone.LEFT;
        if (pointerX >= areaWidth - SIDE_BAND) return Zone.RIGHT;
        return null;
    }

    /**
     * The rect {@code zone} puts a window in: {left, top, width, height} in the work area's space.
     *
     * <p>Halves are computed with the <b>right half taking the remainder</b> rather than both taking
     * {@code width / 2}: an odd work area would otherwise leave a one-pixel column of desktop showing
     * down the middle, which is the sort of thing that looks like a rendering bug.</p>
     */
    public static float[] rectFor(Zone zone, float areaWidth, float areaHeight) {
        float half = (float) Math.floor(areaWidth / 2f);
        switch (zone) {
            case LEFT:
                return new float[] {0f, 0f, half, areaHeight};
            case RIGHT:
                return new float[] {half, 0f, areaWidth - half, areaHeight};
            default:
                return new float[] {0f, 0f, areaWidth, areaHeight};
        }
    }
}
