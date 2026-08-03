package com.crystalgui.headless;

import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockDropZones;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The drop-zone hit map, ported from VS Code's {@code positionOverlay}.
 *
 * <p>Pure arithmetic on a rectangle, so it can be checked exhaustively rather than by waving a mouse at a
 * scene — which is the only way the 10%-vs-30% asymmetry and the corner tie-breaks get looked at at all.</p>
 */
public class DockDropZonesTest {

    private static final float W = 400f;
    private static final float H = 300f;

    /** The centre merges. The most-used drop, and the one edge-zones-only implementations forget. */
    @Test
    public void theCentreMerges() {
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(W / 2f, H / 2f, W, H));
    }

    /** Anywhere inside the 10% inset on both axes still merges, right up to the threshold. */
    @Test
    public void theWholeInsetBoxMerges() {
        float insetX = W * DockDropZones.EDGE_THRESHOLD;
        float insetY = H * DockDropZones.EDGE_THRESHOLD;
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(insetX + 1f, insetY + 1f, W, H));
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(W - insetX - 1f, H - insetY - 1f, W, H));
    }

    /** Outside the inset, the thirds decide. Side-by-side preferred: left/right own the outer thirds. */
    @Test
    public void sideBySidePrefersLeftAndRight() {
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forPane(1f, H / 2f, W, H));
        assertEquals(DockDropZone.SPLIT_RIGHT, DockDropZones.forPane(W - 1f, H / 2f, W, H));
        // Middle third, near the top edge -> up.
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forPane(W / 2f, 1f, W, H));
        assertEquals(DockDropZone.SPLIT_DOWN, DockDropZones.forPane(W / 2f, H - 1f, W, H));
    }

    /** With side-by-side NOT preferred the map transposes: up/down own the outer thirds. */
    @Test
    public void stackedPrefersUpAndDown() {
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forPane(W / 2f, 1f, W, H, false, false));
        assertEquals(DockDropZone.SPLIT_DOWN, DockDropZones.forPane(W / 2f, H - 1f, W, H, false, false));
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forPane(1f, H / 2f, W, H, false, false));
        assertEquals(DockDropZone.SPLIT_RIGHT, DockDropZones.forPane(W - 1f, H / 2f, W, H, false, false));
    }

    /**
     * <b>A whole group gets a bigger target along the preferred axis — 30%, not 10%.</b>
     *
     * <p>VS Code's asymmetry, and deliberate rather than a rounding: a group is a bigger thing to place.
     * At 15% of the width a single panel still merges and a group already splits.</p>
     */
    @Test
    public void draggingAGroupWidensTheEdgeAlongThePreferredAxis() {
        float x = W * 0.15f;
        float y = H / 2f;

        assertEquals("a single panel is still inside the 10% inset",
                DockDropZone.MERGE, DockDropZones.forPane(x, y, W, H, true, false));
        assertEquals("a group is already past the 30% inset",
                DockDropZone.SPLIT_LEFT, DockDropZones.forPane(x, y, W, H, true, true));
    }

    /** …and only along the preferred axis. The other one keeps 10%, or every drop would split. */
    @Test
    public void theWidenedEdgeAppliesToOneAxisOnly() {
        float x = W / 2f;
        float y = H * 0.15f;
        assertEquals("15% down, side-by-side preferred, dragging a group: still the merge box",
                DockDropZone.MERGE, DockDropZones.forPane(x, y, W, H, true, true));
    }

    /** A degenerate pane cannot be split into anything, so it merges rather than dividing by zero. */
    @Test
    public void aZeroSizedPaneMerges() {
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(0f, 0f, 0f, 0f));
    }

    // ── Preview ─────────────────────────────────────────────────────────────────────────────────

    /** A split previews half the pane on the side it would land; a merge covers the whole pane. */
    @Test
    public void thePreviewCoversHalfForASplitAndAllForAMerge() {
        assertArrayEquals(new float[]{0f, 0f, W / 2f, H},
                DockDropZones.previewRect(DockDropZone.SPLIT_LEFT, W, H), 1e-4f);
        assertArrayEquals(new float[]{W / 2f, 0f, W / 2f, H},
                DockDropZones.previewRect(DockDropZone.SPLIT_RIGHT, W, H), 1e-4f);
        assertArrayEquals(new float[]{0f, H / 2f, W, H / 2f},
                DockDropZones.previewRect(DockDropZone.SPLIT_DOWN, W, H), 1e-4f);
        assertArrayEquals(new float[]{0f, 0f, W, H},
                DockDropZones.previewRect(DockDropZone.MERGE, W, H), 1e-4f);
    }

    // ── Outer edge ──────────────────────────────────────────────────────────────────────────────

    /** Away from the frame, the outer map declines and the per-pane map decides. */
    @Test
    public void theOuterEdgeDeclinesInTheMiddle() {
        assertNull(DockDropZones.forOuterEdge(W / 2f, H / 2f, W, H));
    }

    @Test
    public void theOuterEdgeClaimsTheBands() {
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forOuterEdge(2f, H / 2f, W, H));
        assertEquals(DockDropZone.SPLIT_RIGHT, DockDropZones.forOuterEdge(W - 2f, H / 2f, W, H));
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forOuterEdge(W / 2f, 2f, W, H));
        assertEquals(DockDropZone.SPLIT_DOWN, DockDropZones.forOuterEdge(W / 2f, H - 2f, W, H));
    }

    /**
     * <b>A corner is in two bands at once, and the deeper one wins.</b>
     *
     * <p>Otherwise the answer is decided by the order the branches happen to be written in, which means
     * one of the four corners behaves unlike the other three and nobody can say why.</p>
     */
    @Test
    public void aCornerResolvesToWhicheverBandThePointerIsDeeperInto() {
        // 2px from the left, 20px from the top, with a 24px band: deeper into the left band.
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forOuterEdge(2f, 20f, W, H));
        // Transposed.
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forOuterEdge(20f, 2f, W, H));
    }

    /**
     * The band is capped at a third of the area, or a small dock is entirely outer edge and the only pane
     * it has becomes impossible to drop into.
     */
    @Test
    public void theBandIsCappedSoASmallDockKeepsAnInterior() {
        float tiny = 30f;
        assertNull("the centre of a 30px-wide area is still interior",
                DockDropZones.forOuterEdge(tiny / 2f, tiny / 2f, tiny, tiny));
    }
}
