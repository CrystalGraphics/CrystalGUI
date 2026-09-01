package com.crystalgui.workbench.dock.drag;

/**
 * Where a pointer over a pane means to drop — the geometry every IDE has and none documents.
 *
 * <p>Ported verbatim from VS Code's {@code positionOverlay} in
 * {@code workbench/browser/parts/editor/editorDropTarget.ts} (MIT), which is the only readable statement
 * of these numbers. Pure arithmetic on a rectangle: no element, no window, no GL, so it is tested
 * headlessly and exhaustively rather than by waving a mouse at a scene.</p>
 *
 * <pre>
 * ----------------------------------------------
 * |                SPLIT UP                    |
 * |--------------------------------------------|
 * |  SPLIT LEFT  |    MERGE    |  SPLIT RIGHT  |
 * |--------------------------------------------|
 * |                SPLIT DOWN                  |
 * ----------------------------------------------
 * </pre>
 */
public final class DockDropZones {

    private DockDropZones() {
    }

    /** Inside this fraction of the pane on both axes, a drop merges into the tab strip instead of splitting. */
    public static final float EDGE_THRESHOLD = 0.1f;

    /**
     * The edge threshold along the preferred split axis when a whole group is being dragged.
     *
     * <p>A group is a bigger thing to place than a single tab, so it gets a bigger target — VS Code's own
     * reasoning, and the asymmetry is deliberate rather than a rounding of 0.1.</p>
     */
    public static final float GROUP_EDGE_THRESHOLD = 0.3f;

    /** Past a third of the way across, a split is offered on that side. */
    public static final float SPLIT_THRESHOLD = 1f / 3f;

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

    /**
     * The zone for a pointer at {@code (x, y)} in a pane's local space.
     *
     * @param preferSideBySide whether left/right is the preferred split arrangement, which decides which
     *        axis gets the larger hit zone. VS Code derives this from
     *        {@code workbench.editor.openSideBySideDirection === 'right'}
     * @param draggingGroup whether a whole group is in flight rather than a single panel
     */
    public static DockDropZone forPane(float x, float y, float width, float height,
                                       boolean preferSideBySide, boolean draggingGroup) {
        if (width <= 0f || height <= 0f) return DockDropZone.MERGE;

        float edgeWidthFactor = EDGE_THRESHOLD;
        float edgeHeightFactor = EDGE_THRESHOLD;
        if (draggingGroup) {
            edgeWidthFactor = preferSideBySide ? GROUP_EDGE_THRESHOLD : EDGE_THRESHOLD;
            edgeHeightFactor = preferSideBySide ? EDGE_THRESHOLD : GROUP_EDGE_THRESHOLD;
        }

        float edgeWidth = width * edgeWidthFactor;
        float edgeHeight = height * edgeHeightFactor;

        // Inside the middle box on BOTH axes: merge. This is the most-used drop in the whole system, and
        // the one an edge-zones-only implementation forgets — leaving a dock where every drop splits and
        // two panels can never share a strip.
        if (x > edgeWidth && x < width - edgeWidth && y > edgeHeight && y < height - edgeHeight) {
            return DockDropZone.MERGE;
        }

        float splitWidth = width * SPLIT_THRESHOLD;
        float splitHeight = height * SPLIT_THRESHOLD;

        if (preferSideBySide) {
            if (x < splitWidth) return DockDropZone.SPLIT_LEFT;
            if (x > splitWidth * 2f) return DockDropZone.SPLIT_RIGHT;
            return y < height / 2f ? DockDropZone.SPLIT_UP : DockDropZone.SPLIT_DOWN;
        }
        if (y < splitHeight) return DockDropZone.SPLIT_UP;
        if (y > splitHeight * 2f) return DockDropZone.SPLIT_DOWN;
        return x < width / 2f ? DockDropZone.SPLIT_LEFT : DockDropZone.SPLIT_RIGHT;
    }

    /** The common case: a single panel in flight, side-by-side preferred. */
    public static DockDropZone forPane(float x, float y, float width, float height) {
        return forPane(x, y, width, height, true, false);
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
