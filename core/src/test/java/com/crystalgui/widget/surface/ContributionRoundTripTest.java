package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.widget.surface.extension.SurfaceExtensions;

/**
 * <b>Activate, dispose, and nothing remains</b> — for the registries a surface owns and for the two
 * process-wide ones it writes to.
 *
 * <p>A surface is opened and closed many times in a session, so anything an extension leaves behind
 * accumulates: a stale command in the global registry, an inspector section describing a document that
 * is gone. Checked here rather than trusted, because both failures are invisible until the second or
 * third open.</p>
 */
public class ContributionRoundTripTest {

    @Before
    @After
    public void isolate() {
        SurfaceExtensions.resetForTesting();
        InspectorRegistry.resetForTesting();
        CommandRegistry.global().resetForTesting();
    }

    private SurfaceEditor openWithEverything() {
        SurfaceExtensions.contribute(new TestSurface.Everything());
        return new SurfaceEditor(TestSurface.policy(), List.of(TestSurface.Everything.ID));
    }

    @Test
    public void anExtensionRegistersThroughTheSeam() {
        SurfaceEditor surface = openWithEverything();

        assertEquals(1, surface.tools().size());
        assertEquals(1, surface.overlays().size());
        assertEquals(1, surface.viewModes().size());
        assertEquals(1, surface.insertSources().size());
        assertEquals(1, surface.dropHandlers().size());
        assertEquals(1, surface.sections().size());
        assertEquals(1, surface.commands().size());
        assertTrue(CommandRegistry.global().contains("test.surface.thing"));
        assertEquals(1, InspectorRegistry.all().size());
    }

    @Test
    public void disposingTheSurfaceTakesEverythingWithIt() {
        SurfaceEditor surface = openWithEverything();
        surface.dispose();

        assertEquals(List.of(), surface.tools());
        assertEquals(List.of(), surface.overlays());
        assertEquals(List.of(), surface.viewModes());
        assertEquals(List.of(), surface.insertSources());
        assertEquals(List.of(), surface.dropHandlers());
        assertEquals(List.of(), surface.sections());
        assertEquals(List.of(), surface.commands());
        assertFalse("the global command registry keeps nothing",
                CommandRegistry.global().contains("test.surface.thing"));
        assertEquals("the inspector keeps nothing", 0, InspectorRegistry.all().size());
    }

    /** Twice round, because a leak of one is invisible and a leak of two is a pattern. */
    @Test
    public void twiceRoundLeavesNothingBehind() {
        SurfaceEditor first = openWithEverything();
        first.dispose();
        SurfaceEditor second = new SurfaceEditor(TestSurface.policy(),
                List.of(TestSurface.Everything.ID));
        second.dispose();

        assertEquals(0, InspectorRegistry.all().size());
        assertFalse(CommandRegistry.global().contains("test.surface.thing"));
    }

    /** Disposing twice is not a second withdrawal — a handle that ran once must be inert. */
    @Test
    public void disposingTwiceIsIdempotent() {
        SurfaceEditor surface = openWithEverything();
        surface.dispose();
        surface.dispose();

        assertEquals(0, InspectorRegistry.all().size());
    }

    /** A feature holds nothing across surfaces: what one registered is not the next one's. */
    @Test
    public void aFeatureHoldsNothingAcrossSurfaces() {
        SurfaceEditor first = openWithEverything();
        SurfaceEditor second = new SurfaceEditor(TestSurface.policy(),
                List.of(TestSurface.Everything.ID));

        assertEquals(1, first.tools().size());
        assertEquals(1, second.tools().size());
        assertFalse("each surface built its own tool",
                first.tools().get(0) == second.tools().get(0));

        first.dispose();
        assertEquals("the second surface still has its own", 1, second.tools().size());
        second.dispose();
    }

    /** Nothing at activation is fatal: a broken feature costs itself and the surface still opens. */
    @Test
    public void anExtensionThatThrowsCostsOnlyItself() {
        SurfaceExtensions.contribute(new TestSurface.Broken());
        SurfaceExtensions.contribute(new TestSurface.Everything());

        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(),
                List.of(TestSurface.Broken.ID, TestSurface.Everything.ID));

        assertEquals("the good one still activated", 1, surface.tools().size());
        surface.dispose();
    }

    /** An id nothing ships is a logged absence, never an error. */
    @Test
    public void anIdNothingShipsIsAnAbsence() {
        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(),
                List.of("nobody:ships-this"));

        assertEquals(List.of(), surface.tools());
    }

    /** Two extensions claiming one id is a packaging mistake: the second is ignored, not swapped in. */
    @Test
    public void aDuplicateIdIsIgnored() {
        SurfaceExtensions.contribute(new TestSurface.Everything());
        SurfaceExtensions.contribute(new TestSurface.Everything());

        assertEquals(1, SurfaceExtensions.all().size());
    }

    /** The consumer's policy is reachable by type, and only by the right type. */
    @Test
    public void thePolicyIsReachableByType() {
        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(), List.of());

        assertTrue(surface.policy(SurfacePolicy.class) == surface.surfacePolicy());
        try {
            surface.policy(String.class);
            assertTrue("a feature asking for the wrong policy must be told", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("String"));
        }
    }
}
