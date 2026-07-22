package com.crystalgui.style.transition;

import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.style.property.general.floats.FloatProperty;
import org.junit.Test;

import static org.junit.Assert.*;

public class ActiveTransitionTest {

    private static final long START = 1_000_000_000L; // arbitrary fixed "startNanos" reference point

    @Test
    public void beforeDelayHoldsAtFromValue() {
        var property = new FloatProperty("test-float", 0f);
        var transition = new ActiveTransition<>(property, 0f, 100f, START, 50_000_000L, 100_000_000L, ProgressFunctions.Premade.LINEAR);

        // Still within the delay window.
        assertEquals(0f, transition.currentValue(START + 25_000_000L), 0.0001f);
        assertEquals(0.0, transition.progress(START + 25_000_000L), 0.0001);
        assertFalse(transition.isFinished(START + 25_000_000L));
    }

    @Test
    public void linearInterpolationAtMidpointIsHalfway() {
        var property = new FloatProperty("test-float", 0f);
        var transition = new ActiveTransition<>(property, 0f, 100f, START, 0L, 100_000_000L, ProgressFunctions.Premade.LINEAR);

        assertEquals(50f, transition.currentValue(START + 50_000_000L), 0.001f);
        assertEquals(0.5, transition.progress(START + 50_000_000L), 0.001);
    }

    @Test
    public void reachesToValueExactlyAtDuration() {
        var property = new FloatProperty("test-float", 0f);
        var transition = new ActiveTransition<>(property, 10f, 20f, START, 0L, 100_000_000L, ProgressFunctions.Premade.LINEAR);

        assertEquals(20f, transition.currentValue(START + 100_000_000L), 0.0001f);
        assertTrue(transition.isFinished(START + 100_000_000L));
    }

    @Test
    public void clampsPastDuration() {
        var property = new FloatProperty("test-float", 0f);
        var transition = new ActiveTransition<>(property, 10f, 20f, START, 0L, 100_000_000L, ProgressFunctions.Premade.LINEAR);

        // Long after the transition should have ended — still clamps to toValue, doesn't overshoot.
        assertEquals(20f, transition.currentValue(START + 10_000_000_000L), 0.0001f);
        assertTrue(transition.isFinished(START + 10_000_000_000L));
    }

    @Test
    public void zeroDurationFinishesImmediately() {
        var property = new FloatProperty("test-float", 0f);
        var transition = new ActiveTransition<>(property, 0f, 1f, START, 0L, 0L, ProgressFunctions.Premade.LINEAR);

        assertTrue(transition.isFinished(START));
        assertEquals(1f, transition.currentValue(START), 0.0001f);
    }

    @Test
    public void nonLinearEasingWarpsProgressButStillReachesEndpoints() {
        var property = new FloatProperty("test-float", 0f);
        var easeIn = ProgressFunctions.cubicBezier(0.42, 0.0, 1.0, 1.0); // starts slow
        var transition = new ActiveTransition<>(property, 0f, 100f, START, 0L, 100_000_000L, easeIn);

        float atStart = transition.currentValue(START);
        float atQuarter = transition.currentValue(START + 25_000_000L);
        float atEnd = transition.currentValue(START + 100_000_000L);

        assertEquals(0f, atStart, 0.01f);
        assertEquals(100f, atEnd, 0.01f);
        // ease-in should be behind a linear equivalent (25) at the same normalized time.
        assertTrue("ease-in should lag linear progress early on", atQuarter < 25f);
    }
}
