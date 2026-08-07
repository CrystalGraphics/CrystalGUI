package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;

import javax.annotation.Nullable;

/**
 * Where a pointer over the workbench means to drop a tool window — the six slots, as arithmetic.
 *
 * <pre>
 * ------------------------------------------------
 * |        |                            |        |
 * |  LEFT  |                            | RIGHT  |
 * |  TOP   |                            |  TOP   |
 * |--------|          (nothing)         |--------|
 * |  LEFT  |                            | RIGHT  |
 * | BOTTOM |                            | BOTTOM |
 * |--------|----------------------------|--------|
 * |     BOTTOM LEFT     |     BOTTOM RIGHT       |
 * ------------------------------------------------
 * </pre>
 *
 * <h3>The whole window is the target, not the rail</h3>
 *
 * <p>This is the correction that mattered, and it is IntelliJ's actual behaviour: you do not have to hit a
 * twenty-pixel stripe to move a tool window. The window is banded, the destination rectangle lights up, and
 * the drag reads as "put it over there" rather than as "hit this button". Aiming at the rail is aiming at
 * the <em>control</em>; aiming at a band is aiming at the <em>place</em>.</p>
 *
 * <h3>The centre is a real answer, and it is "no"</h3>
 *
 * <p>Over the editor there is no highlight and no label — the drag simply offers nothing. That is not a gap
 * in the map: the editor region holds documents, and a tool window has no meaning there. Returning some
 * nearest band instead would make every drop land somewhere, which is exactly how a drag ends up putting a
 * panel where nobody asked.</p>
 *
 * <h3>Corners belong to the bottom</h3>
 *
 * <p>The bottom band and the side bands overlap at both lower corners, and the bottom wins. Two reasons, and
 * the second is the one that decides it: it is what IntelliJ does, and the bottom band is the only one whose
 * two halves are split by <em>x</em> — so at the lower-left corner "bottom" and "left" would otherwise
 * disagree about which axis the halves even run along.</p>
 *
 * <p>Pure arithmetic on a rectangle: no element, no window, no GL, so it is tested headlessly and
 * exhaustively rather than by waving a mouse at a scene. Same reasoning as {@code DockDropZones}, which is
 * this class's opposite number for the editor area.</p>
 */
public final class RegionDropZones {

    private RegionDropZones() {
    }

    /** One of the six slots — a region and which half of it. */
    public record Target(DockRegion region, RegionSide side) {
    }

    /**
     * How wide a band is when its region is hidden and therefore has no width to measure.
     *
     * <p>A hidden region must stay droppable — being unable to put a tool window back into a region you
     * closed is the kind of dead end that makes people restart the application. So the band still exists;
     * it just cannot take its size from the thing that is not there.</p>
     */
    public static final float DEFAULT_BAND_FRACTION = 0.15f;

    /**
     * The most of an axis one band may claim.
     *
     * <p>Without it a sidebar dragged out to half the window leaves no centre at all, and the editor — the
     * one place a tool window must <em>not</em> land — becomes unreachable as a "nothing here" answer.</p>
     */
    public static final float MAX_BAND_FRACTION = 1f / 3f;

    /**
     * The slot for a pointer at {@code (x, y)} in the workbench's local space, or {@code null} for none.
     *
     * @param leftBand   the sidebar's current width, or {@code 0} to use the default fraction
     * @param rightBand  the auxiliary region's current width, likewise
     * @param bottomBand the panel's current height, likewise
     */
    @Nullable
    public static Target forPoint(float x, float y, float width, float height,
                                  float leftBand, float rightBand, float bottomBand) {
        if (width <= 0f || height <= 0f) return null;

        float left = band(leftBand, width);
        float right = band(rightBand, width);
        float bottom = band(bottomBand, height);

        // BOTTOM FIRST -- see the class note on corners.
        if (y > height - bottom) {
            return new Target(DockRegion.PANEL,
                    x < width / 2f ? RegionSide.PRIMARY : RegionSide.SECONDARY);
        }
        if (x < left) {
            return new Target(DockRegion.SIDEBAR,
                    y < height / 2f ? RegionSide.PRIMARY : RegionSide.SECONDARY);
        }
        if (x > width - right) {
            return new Target(DockRegion.AUXILIARY,
                    y < height / 2f ? RegionSide.PRIMARY : RegionSide.SECONDARY);
        }
        return null;
    }

    /** A band's width: the region's own, defaulted when hidden and capped so the centre survives. */
    private static float band(float measured, float axis) {
        float size = measured > 0f ? measured : axis * DEFAULT_BAND_FRACTION;
        return Math.min(size, axis * MAX_BAND_FRACTION);
    }

    /**
     * The half of {@code regionRect} that a drop into {@code target} would occupy, as
     * {@code [x, y, width, height]}.
     *
     * <h3>The region's own box, not the band the pointer is in</h3>
     *
     * <p>The two are deliberately different rectangles and conflating them was visible immediately: a band
     * is measured from the <b>workbench edge</b> so that hovering the rail targets the region behind it,
     * and painting that band lights the rail up as if a tool window could land <em>on</em> it. IntelliJ's
     * highlight starts where the sidebar starts. So the caller supplies the region's actual box and this
     * only decides which half of it.</p>
     *
     * <p>Halved along the region's <b>cross axis</b> — top/bottom for a column, left/right for the bottom
     * strip — which is the same axis {@link RegionSide} is about. Highlighting the whole region for a
     * SECONDARY drop would promise the sidebar and deliver half of it.</p>
     */
    public static float[] previewRect(Target target, float[] regionRect) {
        float x = regionRect[0];
        float y = regionRect[1];
        float width = regionRect[2];
        float height = regionRect[3];
        boolean second = target.side() == RegionSide.SECONDARY;

        if (target.region() == DockRegion.PANEL) {
            // Split across, because the bottom strip's halves sit side by side.
            return new float[]{second ? x + width / 2f : x, y, width / 2f, height};
        }
        return new float[]{x, second ? y + height / 2f : y, width, height / 2f};
    }

    /**
     * Where a region's box is when the region is <b>hidden</b>, so a drop can still be shown landing there.
     *
     * <p>A band inset by {@code railInset}, which is what the rail occupies at that edge. The band exists
     * so a closed region stays droppable; insetting it is what keeps the preview off the rail, exactly as
     * it is off the rail when the region is open and its real box is used instead.</p>
     */
    public static float[] fallbackRect(DockRegion region, float width, float height,
                                       float leftBand, float rightBand, float bottomBand,
                                       float railInset) {
        switch (region) {
            case PANEL: {
                float bottom = band(bottomBand, height);
                return new float[]{railInset, height - bottom,
                        Math.max(0f, width - railInset * 2f), bottom};
            }
            case AUXILIARY: {
                float right = band(rightBand, width);
                return new float[]{width - right, 0f, Math.max(0f, right - railInset), height};
            }
            default: {
                float left = band(leftBand, width);
                return new float[]{railInset, 0f, Math.max(0f, left - railInset), height};
            }
        }
    }

    /**
     * "Move to Bottom Left" — IntelliJ's own wording, and deliberately its vocabulary rather than ours.
     *
     * <p>It names the <b>slot on screen</b>, not the model: "Bottom Left" rather than "Panel, primary
     * half". This is read while pointing at a piece of the window, and the only useful answer is which
     * piece.</p>
     *
     * <p>Note the word order flips with the axis — "<i>Bottom</i> Left" but "<i>Left</i> Bottom" — because
     * the first word is the region and the second is the half. That is IntelliJ's convention too, and it
     * is what keeps "Bottom Left" (the bottom strip's left half) distinct from "Left Bottom" (the sidebar's
     * lower half), which are genuinely two different places.</p>
     */
    public static String labelFor(Target target) {
        boolean second = target.side() == RegionSide.SECONDARY;
        switch (target.region()) {
            case PANEL:
                return second ? "Move to Bottom Right" : "Move to Bottom Left";
            case AUXILIARY:
                return second ? "Move to Right Bottom" : "Move to Right Top";
            default:
                return second ? "Move to Left Bottom" : "Move to Left Top";
        }
    }
}
