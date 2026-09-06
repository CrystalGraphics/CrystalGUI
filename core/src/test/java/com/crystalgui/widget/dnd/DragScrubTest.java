package com.crystalgui.widget.dnd;

import com.crystalgui.ui.service.Drag;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.ui.input.DragScrub;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.10 — the arithmetic of dragging a number.
 *
 * <h3>Why this is worth a test file of its own</h3>
 * <p>Every property here is invisible when wrong. A sign error looks like a working scrub that goes the
 * wrong way; a sensitivity curve that does not scale looks like a working scrub that is unusable at one
 * end of the range; a rate that compounds looks like a working scrub until you drag back. None of them
 * throw, and none of them are visible in a layout dump — which is exactly the reason the gesture is a
 * pure function rather than a handful of statements inside a mouse listener.</p>
 */
public class DragScrubTest {

    private static final int NONE = CgModifiers.NONE;
    private static final double EPS = 1e-9;

    // ── Direction ───────────────────────────────────────────────────────────

    @Test
    public void rightAndUpIncreaseLeftAndDownDecrease() {
        assertTrue("dragging right must increase", DragScrub.value(0, 20, 0, NONE, DragScrub.Spec.FLOAT) > 0);
        assertTrue("dragging left must decrease", DragScrub.value(0, -20, 0, NONE, DragScrub.Spec.FLOAT) < 0);
        // The one that is a coin-flip to get wrong: screen Y grows DOWNWARD, so "up" is a negative dy.
        assertTrue("dragging up must increase", DragScrub.value(0, 0, -20, NONE, DragScrub.Spec.FLOAT) > 0);
        assertTrue("dragging down must decrease", DragScrub.value(0, 0, 20, NONE, DragScrub.Spec.FLOAT) < 0);
    }

    /**
     * A vector component sits in a box tens of pixels wide, so a horizontal-only scrub runs out of room
     * long before it runs out of range. The vertical axis is not a bonus, it is what makes the gesture
     * usable in the place it is mostly used.
     */
    @Test
    public void theDominantAxisWins() {
        assertEquals(30f, DragScrub.dominantDelta(30f, -10f), 0f);
        assertEquals(30f, DragScrub.dominantDelta(-10f, -30f), 0f);
        // A tie goes to X. Arbitrary, but it must be decided rather than left to float comparison luck.
        assertEquals(10f, DragScrub.dominantDelta(10f, -10f), 0f);
    }

    // ── Sensitivity ─────────────────────────────────────────────────────────

    /**
     * <b>The property a fixed rate cannot have.</b> One rate cannot serve a field at 0.5 and a field at
     * 5000: pick one and the other is either untouchable or uncontrollable.
     */
    @Test
    public void largerValuesScrubFaster() {
        double atHalf = DragScrub.unitsPerPixel(0.5, false, NONE);
        double atFiveThousand = DragScrub.unitsPerPixel(5000, false, NONE);
        assertTrue("a large value must move faster per pixel", atFiveThousand > atHalf * 10);
    }

    /** Symmetric: a value at −5000 is as far from zero as one at +5000 and must scrub identically. */
    @Test
    public void sensitivityFollowsMagnitudeNotSign() {
        assertEquals(DragScrub.unitsPerPixel(5000, false, NONE),
                DragScrub.unitsPerPixel(-5000, false, NONE), EPS);
    }

    /**
     * Floored at 1, so sub-unit values do not scrub <em>slower</em> than a value of 1 — without the
     * floor a field sitting at 0.01 moves at a tenth of the base rate and reads as broken.
     */
    @Test
    public void tinyValuesDoNotScrubSlowerThanOne() {
        assertEquals(DragScrub.unitsPerPixel(1, false, NONE),
                DragScrub.unitsPerPixel(0.0001, false, NONE), EPS);
        assertEquals(DragScrub.unitsPerPixel(1, false, NONE),
                DragScrub.unitsPerPixel(0, false, NONE), EPS);
    }

    @Test
    public void shiftIsCoarseAndCtrlIsFine() {
        double plain = DragScrub.unitsPerPixel(1, false, NONE);
        assertEquals(plain * DragScrub.COARSE_MULTIPLIER,
                DragScrub.unitsPerPixel(1, false, CgModifiers.SHIFT), EPS);
        assertEquals(plain * DragScrub.FINE_MULTIPLIER,
                DragScrub.unitsPerPixel(1, false, CgModifiers.CTRL), EPS);
    }

    /** Alt is the pan/menu modifier elsewhere in the engine and must not quietly mean something here. */
    @Test
    public void altDoesNothing() {
        assertEquals(DragScrub.unitsPerPixel(1, false, NONE),
                DragScrub.unitsPerPixel(1, false, CgModifiers.ALT), EPS);
    }

    // ── Integral fields ─────────────────────────────────────────────────────

    @Test
    public void anIntegralFieldStepsWholeUnits() {
        double after = DragScrub.value(3, 7f, 0f, NONE, DragScrub.Spec.INTEGRAL);
        assertEquals(10d, after, EPS);
        assertEquals(Math.rint(after), after, 0d);
    }

    /**
     * An integral field cannot use the fractional rate: at 0.03 units/px the first thirty pixels of
     * movement round back to where they started, and the field looks stuck.
     */
    @Test
    public void anIntegralFieldMovesOnTheFirstPixel() {
        assertEquals(1d, DragScrub.value(0, 1f, 0f, NONE, DragScrub.Spec.INTEGRAL), EPS);
    }

    // ── Range ───────────────────────────────────────────────────────────────

    @Test
    public void aRangeClampsBothWays() {
        DragScrub.Spec zeroToOne = DragScrub.Spec.FLOAT.withRange(0, 1);
        assertEquals(1d, DragScrub.value(0.5, 10_000f, 0f, NONE, zeroToOne), EPS);
        assertEquals(0d, DragScrub.value(0.5, -10_000f, 0f, NONE, zeroToOne), EPS);
    }

    /**
     * Rounding happens BEFORE clamping. Reversed, an integral field with a fractional bound rounds its
     * way back out of its own range — max 2.5 becomes 3.
     */
    @Test
    public void roundingCannotPushAnIntegralValueOutOfRange() {
        DragScrub.Spec bounded = new DragScrub.Spec(true, 0, 2.5);
        assertTrue(DragScrub.value(0, 10_000f, 0f, NONE, bounded) <= 2.5);
    }

    @Test
    public void aRangeIsValidatedRatherThanSilentlyInverted() {
        assertThrows(IllegalArgumentException.class, () -> new DragScrub.Spec(false, 5, 1));
    }

    // ── The anti-compounding property ───────────────────────────────────────

    /**
     * <b>Drag out and come back, and you land exactly where you started.</b>
     *
     * <p>This is the whole reason both the starting point and the rate are read from the anchor. Compute
     * either from the running value and the gesture stops being reversible — the rate accelerates as the
     * value grows, so the return trip is worth more per pixel than the outbound one was. It still looks
     * like a working scrub, and the drift is invisible until someone tries to put a value back.</p>
     */
    @Test
    public void draggingOutAndBackReturnsToTheAnchorExactly() {
        double anchor = 1234.5;
        double outbound = DragScrub.value(anchor, 400f, 0f, NONE, DragScrub.Spec.FLOAT);
        assertNotEquals(anchor, outbound, 1d);
        assertEquals(anchor, DragScrub.value(anchor, 0f, 0f, NONE, DragScrub.Spec.FLOAT), 0d);
    }

    /** The same property expressed the way the widget uses it: every frame recomputes from the anchor,
     * so replaying the same delta any number of times is idempotent. */
    @Test
    public void recomputingTheSameDeltaIsIdempotent() {
        double first = DragScrub.value(2, 37f, -11f, NONE, DragScrub.Spec.FLOAT);
        for (int frame = 0; frame < 50; frame++) {
            assertEquals(first, DragScrub.value(2, 37f, -11f, NONE, DragScrub.Spec.FLOAT), 0d);
        }
    }

    // ── Precision ───────────────────────────────────────────────────────────

    /**
     * <b>A scrub never reports more precision than the hand producing it had.</b>
     *
     * <p>{@code 0.5004732} out of a mouse drag is noise with a decimal point in front — nobody aimed at
     * the fourth place — and in a 40px port editor the digits that <em>were</em> aimed at get pushed off
     * the end by the ones that were not.</p>
     */
    @Test
    public void anOrdinaryScrubStopsAtTwoDecimals() {
        for (float dx : new float[] { 3f, 17f, 41f, 96f, 137f }) {
            double v = DragScrub.value(10, dx, 0f, NONE, DragScrub.Spec.FLOAT);
            assertTrue("expected <= 2 decimals, got " + v, decimalsOf(v) <= 2);
        }
    }

    /** Rendered as the short form too, which is what reaches the document via {@code String.valueOf}. */
    @Test
    public void theResultDoesNotCarryABinaryTail() {
        assertEquals("15.69", String.valueOf(DragScrub.value(10, 60f, 0f, NONE, DragScrub.Spec.FLOAT)));
    }

    /**
     * The cut follows the rate, so it holds at every magnitude — a five-digit value does not acquire a
     * meaningless fraction just because a fixed two places were demanded of it.
     */
    @Test
    public void aLargeValueScrubsInWholeUnits() {
        double v = DragScrub.value(10_000, 37f, 0f, NONE, DragScrub.Spec.FLOAT);
        assertEquals(0, decimalsOf(v));
    }

    /** ...and Ctrl stays genuinely fine: a tenth of the rate buys a place the user asked for. */
    @Test
    public void ctrlBuysOneMoreDecimalPlace() {
        assertEquals(2, DragScrub.decimalsFor(DragScrub.unitsPerPixel(1, false, NONE)));
        assertEquals(3, DragScrub.decimalsFor(DragScrub.unitsPerPixel(1, false, CgModifiers.CTRL)));
        assertEquals(1, DragScrub.decimalsFor(DragScrub.unitsPerPixel(1, false, CgModifiers.SHIFT)));
    }

    /**
     * Rounding must not reach the anchor itself. Applied to the absolute value rather than the movement,
     * the first frame past the threshold would snap a value like {@code 1234.5} to {@code 1235} — and the
     * out-and-back property above would break, since the return trip would land somewhere the outbound
     * one never was.
     */
    @Test
    public void zeroMovementIsNotRounded() {
        assertEquals(1234.5678d, DragScrub.value(1234.5678d, 0f, 0f, NONE, DragScrub.Spec.FLOAT), 0d);
    }

    /** Decimal places of a double as it would actually be printed. */
    private static int decimalsOf(double v) {
        String s = String.valueOf(v);
        int dot = s.indexOf('.');
        if (dot < 0 || s.contains("E")) return 0;
        String fraction = s.substring(dot + 1);
        return fraction.equals("0") ? 0 : fraction.length();
    }

    // ── Threshold ───────────────────────────────────────────────────────────

    @Test
    public void theThresholdIsRadialNotPerAxis() {
        float t = DragScrub.DEFAULT_THRESHOLD_PX;
        assertFalse(DragScrub.passesThreshold(t - 1f, 0f, t));
        assertTrue(DragScrub.passesThreshold(t, 0f, t));
        assertTrue(DragScrub.passesThreshold(0f, -t, t));
        // Diagonal movement counts toward it, or a 45-degree drag needs 1.4x the travel of a straight one.
        assertTrue(DragScrub.passesThreshold(t * 0.8f, t * 0.8f, t));
    }
}
