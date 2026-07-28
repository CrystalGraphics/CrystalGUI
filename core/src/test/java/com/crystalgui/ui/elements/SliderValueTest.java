package com.crystalgui.ui.elements;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Slider value math — clamping, step snapping, and fraction mapping.
 *
 * <p>Exercised through the real element rather than a helper: constructing a {@code Slider} touches
 * only layout/style state (no GL), so the actual behaviour is under test rather than a
 * reimplementation of it that could drift.</p>
 */
public class SliderValueTest {

    private static final float EPS = 0.0001f;

    @Test
    public void defaultsToZeroInAUnitRange() {
        Slider s = new Slider();
        assertEquals(0f, s.getValue(), EPS);
        assertEquals(0f, s.getFraction(), EPS);
    }

    @Test
    public void valuesAreClampedToTheRange() {
        Slider s = new Slider().setRange(0f, 10f);
        s.setValue(20f);
        assertEquals(10f, s.getValue(), EPS);
        s.setValue(-5f);
        assertEquals(0f, s.getValue(), EPS);
    }

    @Test
    public void fractionMapsLinearlyAcrossTheRange() {
        Slider s = new Slider().setRange(10f, 20f);
        s.setValue(15f);
        assertEquals(0.5f, s.getFraction(), EPS);
        s.setValue(20f);
        assertEquals(1f, s.getFraction(), EPS);
        s.setValue(10f);
        assertEquals(0f, s.getFraction(), EPS);
    }

    /** A zero-width range must not divide by zero. */
    @Test
    public void degenerateRangeYieldsZeroFraction() {
        Slider s = new Slider().setRange(5f, 5f);
        s.setValue(5f);
        assertEquals(0f, s.getFraction(), EPS);
    }

    @Test
    public void stepSnapsToTheNearestMultiple() {
        Slider s = new Slider().setRange(0f, 10f).setStep(2f);
        s.setValue(4.9f);
        assertEquals(4f, s.getValue(), EPS);
        s.setValue(5.1f);
        assertEquals(6f, s.getValue(), EPS);
    }

    /** Snapping is relative to min, not to zero — a range that doesn't start at 0 must still land
     * on min exactly. */
    @Test
    public void stepIsRelativeToMinNotZero() {
        Slider s = new Slider().setRange(1f, 11f).setStep(5f);
        s.setValue(1.4f);
        assertEquals(1f, s.getValue(), EPS);
        s.setValue(4f);
        assertEquals(6f, s.getValue(), EPS);
    }

    /** Snapping must never push the value outside the range, even when the step doesn't divide it
     * evenly. */
    @Test
    public void snappingCannotEscapeTheRange() {
        Slider s = new Slider().setRange(0f, 10f).setStep(3f);
        s.setValue(10f);
        assertTrue("snapped value " + s.getValue() + " left the range", s.getValue() <= 10f);
        assertTrue(s.getValue() >= 0f);
    }

    @Test
    public void signalsOnlyOnAnActualChange() {
        Slider s = new Slider().setRange(0f, 10f);
        int[] fired = {0};
        s.attachListener(v -> fired[0]++);

        s.setValue(5f);
        assertEquals(1, fired[0]);
        s.setValue(5f);              // same value
        assertEquals(1, fired[0]);
        s.setValue(20f);             // clamps to 10 — a real change
        assertEquals(2, fired[0]);
        s.setValue(15f);             // clamps to 10 again — no change
        assertEquals(2, fired[0]);
    }

    /** Snapping happens before the change check, so sub-step nudges must not spam the signal. */
    @Test
    public void subStepNudgesDoNotSignal() {
        Slider s = new Slider().setRange(0f, 10f).setStep(2f);
        int[] fired = {0};
        s.attachListener(v -> fired[0]++);

        s.setValue(4f);
        assertEquals(1, fired[0]);
        s.setValue(4.2f);            // snaps back to 4
        assertEquals(1, fired[0]);
    }

    /** Narrowing the range must pull an out-of-bounds value back in. */
    @Test
    public void changingRangeReclampsTheCurrentValue() {
        Slider s = new Slider().setRange(0f, 100f);
        s.setValue(80f);
        s.setRange(0f, 50f);
        assertEquals(50f, s.getValue(), EPS);
    }
}
