package com.crystalgui.style.transition;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TransitionSpecTest {

    @Test
    public void parsesPropertyAndDurationInMilliseconds() {
        var specs = TransitionSpec.parse("background-color 200ms");
        assertEquals(1, specs.size());
        var spec = specs.get(0);
        assertEquals("background-color", spec.propertyNameOrAll());
        assertEquals(200_000_000L, spec.durationNanos());
        assertEquals(0L, spec.delayNanos());
    }

    @Test
    public void parsesDurationInSeconds() {
        var spec = TransitionSpec.parse("width 0.3s").get(0);
        assertEquals(300_000_000L, spec.durationNanos());
    }

    @Test
    public void parsesOptionalDelay() {
        var spec = TransitionSpec.parse("color 200ms 50ms").get(0);
        assertEquals(200_000_000L, spec.durationNanos());
        assertEquals(50_000_000L, spec.delayNanos());
    }

    @Test
    public void parsesNamedTimingFunctionKeyword() {
        // Named keywords resolve without throwing; exact curve shape is covered by ActiveTransitionTest.
        for (String keyword : List.of("linear", "ease", "ease-in", "ease-out", "ease-in-out")) {
            var spec = TransitionSpec.parse("opacity 100ms " + keyword).get(0);
            assertNotNull(spec.easing());
        }
    }

    @Test
    public void parsesArbitraryCubicBezierFunction() {
        var spec = TransitionSpec.parse("opacity 100ms cubic-bezier(0.1, 0.2, 0.3, 0.4)").get(0);
        assertNotNull(spec.easing());
        // Sanity check it's a real bezier curve, not a linear fallback: t=0 -> 0, t=1 -> 1.
        assertEquals(0.0, spec.easing().ease(0.0), 1e-9);
        assertEquals(1.0, spec.easing().ease(1.0), 1e-9);
    }

    @Test
    public void parsesDurationAndTimingFunctionWithoutDelay() {
        var spec = TransitionSpec.parse("all 300ms ease-in-out").get(0);
        assertEquals(TransitionSpec.ALL, spec.propertyNameOrAll());
        assertEquals(0L, spec.delayNanos());
    }

    @Test
    public void commaSeparatedEntriesParseIndependently() {
        var specs = TransitionSpec.parse("color 200ms, width 300ms 50ms ease-in");
        assertEquals(2, specs.size());
        assertEquals("color", specs.get(0).propertyNameOrAll());
        assertEquals("width", specs.get(1).propertyNameOrAll());
        assertEquals(50_000_000L, specs.get(1).delayNanos());
    }

    @Test
    public void commasInsideCubicBezierDoNotSplitEntries() {
        var specs = TransitionSpec.parse("opacity 100ms cubic-bezier(0.1, 0.2, 0.3, 0.4), color 200ms");
        assertEquals(2, specs.size());
        assertEquals("opacity", specs.get(0).propertyNameOrAll());
        assertEquals("color", specs.get(1).propertyNameOrAll());
    }

    @Test
    public void missingDurationThrows() {
        assertThrows(IllegalArgumentException.class, () -> TransitionSpec.parse("color"));
    }

    @Test
    public void unknownTimingFunctionThrows() {
        assertThrows(IllegalArgumentException.class, () -> TransitionSpec.parse("color 200ms not-a-real-easing"));
    }
}
