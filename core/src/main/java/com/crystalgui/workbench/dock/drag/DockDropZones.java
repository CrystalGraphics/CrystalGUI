package com.crystalgui.workbench.dock.drag;

/**
 * Where a pointer over a pane means to drop — the geometry every IDE has and none documents.
 *
 * <p>From VS Code's {@code positionOverlay} in {@code workbench/browser/parts/editor/editorDropTarget.ts}
 * (MIT), with two deliberate differences. Its edge band is 10% of the pane for a tab and 30% for a group;
 * here it is 30% for both, since at 10% a split had to be aimed at a sliver of a large editor. And outside
 * the merge box the NEAREST edge wins, relative to the pane's size, where VS Code's thirds made a pointer
 * near the top edge of the left third a left split. The smaller merge box costs little: a tab strip always
 * merges. Pure arithmetic on a rectangle, so it is tested headlessly and exhaustively.</p>
 *
 * <pre>
 * ---------------------------------
 * | \          SPLIT UP         / |
 * |   \-----------------------/   |
 * | L  |        MERGE        |  R |
 * |   /-----------------------\   |
 * | /         SPLIT DOWN        \ |
 * ---------------------------------
 * </pre>
 */
public final class DockDropZones {

    private DockDropZones() {
    }

    /** Within this fraction of the pane from an edge, a drop splits on that side; inside it on both axes, it merges. */
    public static final float EDGE_THRESHOLD = 0.25f;

    /** How far the drop overlay covers the pane when it is previewing a split. */
    public static final float PREVIEW_FRACTION = 0.5f;

    /**
     * How long the overlay survives the pointer leaving, in seconds.
     *
     * <p>Without it the overlay is torn down and rebuilt every time the pointer crosses the seam between
     * two panes, which reads as a flicker rather than as a boundary.</p>
     */
    public static final float OVERLAY_CLEANUP_SECONDS = 0.3f;

    /** The band at the very edge of the whole dock area that targets the outer edge, in logical pixels. */
    public static final float OUTER_EDGE_BAND_PX = 24f;

    /** The zone for a pointer at {@code (x, y)} in a pane's local space. */
    public static DockDropZone forPane(float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return DockDropZone.MERGE;
        float left = x / width;
        float right = 1f - left;
        float up = y / height;
        float down = 1f - up;

        // Inside the middle box on BOTH axes: merge. The most-used drop in the whole system, and the one an
        // edge-zones-only implementation forgets -- leaving a dock where every drop splits.
        if (Math.min(left, right) > EDGE_THRESHOLD && Math.min(up, down) > EDGE_THRESHOLD) return DockDropZone.MERGE;

        float nearest = Math.min(Math.min(left, right), Math.min(up, down));
        if (nearest == left) return DockDropZone.SPLIT_LEFT;
        if (nearest == right) return DockDropZone.SPLIT_RIGHT;
        if (nearest == up) return DockDropZone.SPLIT_UP;
        return DockDropZone.SPLIT_DOWN;
    }

    /**
     * The zone for a pointer near the outer edge of the whole dock area, or {@code null} when it is not
     * near one and the per-pane map should decide.
     *
     * <p>The band is capped at a third of the area so a very small dock does not become entirely outer
     * edge, which would make it impossible to drop into the only pane it has.</p>
     */
    public static DockDropZone forOuterEdge(float x, float y, float width, float height) {
        return forOuterEdge(x, y, width, height, OUTER_EDGE_BAND_PX);
    }

    public static DockDropZone forOuterEdge(float x, float y, float width, float height, float bandPx) {
        if (width <= 0f || height <= 0f) return null;
        float horizontalBand = Math.min(bandPx, width / 3f);
        float verticalBand = Math.min(bandPx, height / 3f);

        boolean left = x >= 0f && x < horizontalBand;
        boolean right = x <= width && x > width - horizontalBand;
        boolean up = y >= 0f && y < verticalBand;
        boolean down = y <= height && y > height - verticalBand;

        // A corner is in two bands at once. Pick the one the pointer is deeper into, so the choice tracks
        // the gesture rather than the order the branches happen to be written in.
        //
        // "Deeper" is the LARGER penetration, and absent bands are -1 rather than a large sentinel: a
        // MAX_VALUE placeholder reads as "infinitely deep" to a max and wins every comparison, which is
        // the same inversion in the other direction.
        float leftDepth = left ? horizontalBand - x : -1f;
        float rightDepth = right ? horizontalBand - (width - x) : -1f;
        float upDepth = up ? verticalBand - y : -1f;
        float downDepth = down ? verticalBand - (height - y) : -1f;

        float best = Math.max(Math.max(leftDepth, rightDepth), Math.max(upDepth, downDepth));
        if (best < 0f) return null;
        if (best == leftDepth) return DockDropZone.SPLIT_LEFT;
        if (best == rightDepth) return DockDropZone.SPLIT_RIGHT;
        if (best == upDepth) return DockDropZone.SPLIT_UP;
        return DockDropZone.SPLIT_DOWN;
    }

    /**
     * The rectangle the overlay should cover to preview {@code zone}, as
     * {@code [x, y, width, height]} in the pane's local space.
     */
    public static float[] previewRect(DockDropZone zone, float width, float height) {
        switch (zone) {
            case SPLIT_UP:
                return new float[]{0f, 0f, width, height * PREVIEW_FRACTION};
            case SPLIT_DOWN:
                return new float[]{0f, height * PREVIEW_FRACTION, width, height * PREVIEW_FRACTION};
            case SPLIT_LEFT:
                return new float[]{0f, 0f, width * PREVIEW_FRACTION, height};
            case SPLIT_RIGHT:
                return new float[]{width * PREVIEW_FRACTION, 0f, width * PREVIEW_FRACTION, height};
            default:
                return new float[]{0f, 0f, width, height};
        }
    }
}
