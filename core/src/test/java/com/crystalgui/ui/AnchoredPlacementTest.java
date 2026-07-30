package com.crystalgui.ui;

import org.junit.Test;

import static com.crystalgui.ui.AnchoredPlacement.Side;
import static org.junit.Assert.*;

/**
 * The placement geometry extracted out of {@code Tooltip} when {@code Popover} became its second consumer.
 *
 * <p>Pure maths, deliberately — no elements, no styles, no window. That is the whole point of extracting
 * it: flipping and clamping used to be observable only by looking at a rendered tooltip, and the bug that
 * prompted the extraction (reading the layout box instead of the transform chain) was found on screen.</p>
 */
public class AnchoredPlacementTest {

    private static final float W = 200f, H = 100f; // available space

    private static AnchoredPlacement.Rect anchor(float x, float y, float w, float h) {
        return new AnchoredPlacement.Rect(x, y, w, h);
    }

    // ── The preferred side ──────────────────────────────────────────────────

    @Test
    public void bottomPlacesBelowTheAnchorAndLeftAligned() {
        var at = AnchoredPlacement.resolve(anchor(20f, 30f, 50f, 10f), 40f, 20f, W, H, Side.BOTTOM, 0f);

        assertEquals("left edges line up", 20f, at.x(), 0.01f);
        assertEquals("directly under the anchor's bottom", 40f, at.y(), 0.01f);
    }

    @Test
    public void theOffsetOpensAGap() {
        var at = AnchoredPlacement.resolve(anchor(20f, 30f, 50f, 10f), 40f, 20f, W, H, Side.BOTTOM, 4f);
        assertEquals(44f, at.y(), 0.01f);
    }

    @Test
    public void rightPlacesBesideTheAnchorAndTopAligned() {
        var at = AnchoredPlacement.resolve(anchor(20f, 30f, 50f, 10f), 40f, 20f, W, H, Side.RIGHT, 0f);

        assertEquals("just past the anchor's right edge", 70f, at.x(), 0.01f);
        assertEquals("top edges line up", 30f, at.y(), 0.01f);
    }

    // ── Flipping ────────────────────────────────────────────────────────────

    @Test
    public void flipsAboveWhenThereIsNoRoomBelow() {
        // Anchor near the bottom: 100 - (80 + 10) = 10px below, but the popup needs 30.
        var at = AnchoredPlacement.resolve(anchor(20f, 80f, 50f, 10f), 40f, 30f, W, H, Side.BOTTOM, 0f);

        assertEquals("flipped to sit above the anchor", 50f, at.y(), 0.01f);
    }

    @Test
    public void flipsLeftWhenThereIsNoRoomRight() {
        var at = AnchoredPlacement.resolve(anchor(170f, 20f, 20f, 10f), 60f, 20f, W, H, Side.RIGHT, 0f);

        assertEquals("flipped to the anchor's left", 110f, at.x(), 0.01f);
    }

    /**
     * The half of the rule that is easy to omit: flipping is only worth it when the opposite side is
     * <em>roomier</em>. A popup taller than the whole viewport fits nowhere, and a naive "does it fit?"
     * check would flip it every frame, moving the overflow from one edge to the other forever.
     */
    @Test
    public void doesNotFlipWhenTheOppositeSideIsNoBetter() {
        // 10px above, 60px below. Popup needs 80 — fits neither, but below is clearly the lesser evil.
        var at = AnchoredPlacement.resolve(anchor(20f, 10f, 30f, 30f), 40f, 80f, W, H, Side.BOTTOM, 0f);

        assertEquals("stayed below, then clamped", 20f, at.y(), 0.01f);
    }

    @Test
    public void topFlipsDownwardsWhenCramped() {
        var at = AnchoredPlacement.resolve(anchor(20f, 5f, 30f, 10f), 40f, 30f, W, H, Side.TOP, 0f);

        assertEquals("flipped below", 15f, at.y(), 0.01f);
    }

    // ── Clamping ────────────────────────────────────────────────────────────

    /** The cross axis is only ever clamped, never flipped — a popup that jumped sides as the pointer
     * crossed a midpoint would be far more distracting than one that slides. */
    @Test
    public void theCrossAxisSlidesRatherThanFlipping() {
        var at = AnchoredPlacement.resolve(anchor(180f, 20f, 10f, 10f), 60f, 20f, W, H, Side.BOTTOM, 0f);

        assertEquals("slid left to fit, still below the anchor", 140f, at.x(), 0.01f);
        assertEquals(30f, at.y(), 0.01f);
    }

    @Test
    public void nothingIsEverPlacedOffTheTopOrLeft() {
        var at = AnchoredPlacement.resolve(anchor(-50f, -50f, 10f, 10f), 40f, 20f, W, H, Side.TOP, 0f);

        assertTrue("x must not be negative, was " + at.x(), at.x() >= 0f);
        assertTrue("y must not be negative, was " + at.y(), at.y() >= 0f);
    }

    /** With nowhere to fit at all, the popup is still on screen. The far edge is the lesser evil, and it
     * beats the alternative of drawing something the user cannot reach. */
    @Test
    public void anOversizedPopupIsPinnedRatherThanLost() {
        var at = AnchoredPlacement.resolve(anchor(100f, 50f, 10f, 10f), 500f, 500f, W, H, Side.BOTTOM, 0f);

        assertEquals(0f, at.x(), 0.01f);
        assertEquals(0f, at.y(), 0.01f);
    }

    // ── Point anchoring ─────────────────────────────────────────────────────

    /** A context menu is the same primitive with a zero-sized anchor, not a second one. */
    @Test
    public void aZeroSizedAnchorPlacesAtThePoint() {
        var at = AnchoredPlacement.resolve(anchor(70f, 40f, 0f, 0f), 30f, 20f, W, H, Side.BOTTOM, 0f);

        assertEquals(70f, at.x(), 0.01f);
        assertEquals(40f, at.y(), 0.01f);
    }
}
