package com.crystalgui.headless;

import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.drag.DockDropZones;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The drop-zone hit map, from VS Code's {@code positionOverlay} with wider edges and the nearest edge winning.
 *
 * <p>Pure arithmetic on a rectangle, so it can be checked exhaustively rather than by waving a mouse at a
 * scene — which is the only way the thresholds and the corner tie-breaks get looked at at all.</p>
 */
public class DockDropZonesTest {

    private static final float W = 400f;
    private static final float H = 300f;

    /** The centre merges. The most-used drop, and the one edge-zones-only implementations forget. */
    @Test
    public void theCentreMerges() {
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(W / 2f, H / 2f, W, H));
    }

    /** Anywhere inside the 30% inset on both axes still merges, right up to the threshold. */
    @Test
    public void theWholeInsetBoxMerges() {
        float insetX = W * DockDropZones.EDGE_THRESHOLD;
        float insetY = H * DockDropZones.EDGE_THRESHOLD;
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(insetX + 1f, insetY + 1f, W, H));
        assertEquals(DockDropZone.MERGE, DockDropZones.forPane(W - insetX - 1f, H - insetY - 1f, W, H));
    }

    /** Each edge band splits on its own side, well in from the edge: three quarters across is a right split. */
    @Test
    public void eachEdgeBandSplitsOnItsSide() {
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forPane(W * 0.25f, H / 2f, W, H));
        assertEquals(DockDropZone.SPLIT_RIGHT, DockDropZones.forPane(W * 0.75f, H / 2f, W, H));
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forPane(W / 2f, H * 0.25f, W, H));
        assertEquals(DockDropZone.SPLIT_DOWN, DockDropZones.forPane(W / 2f, H * 0.75f, W, H));
    }

    /** Outside the merge box the nearest edge wins: near the top of the left third is up, not left. */
    @Test
    public void theNearestEdgeWins() {
        assertEquals(DockDropZone.SPLIT_UP, DockDropZones.forPane(W * 0.2f, H * 0.05f, W, H));
        assertEquals(DockDropZone.SPLIT_LEFT, DockDropZones.forPane(W * 0.05f, H * 0.2f, W, H));
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
