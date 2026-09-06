package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.widget.surface.extension.SurfaceExtensions;

/**
 * <b>The engine is not a product.</b> A surface with no extensions enabled has no tools, no overlays,
 * no view modes, no insert sources, no drop handlers and no sections.
 *
 * <p>The first invariant of the engine, and the one the rest depend on: if anything ships built in,
 * some feature is reachable without going through the seam, and the seam stops being the only door.
 * {@code Workbench} makes the same claim and {@code new Workbench(workspace, List.of())} is how it is
 * checked.</p>
 */
public class SurfaceShipsNothingTest {

    @Before
    @After
    public void isolate() {
        SurfaceExtensions.resetForTesting();
    }

    @Test
    public void aSurfaceWithNothingEnabledRegistersNothing() {
        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(), List.of());

        assertEquals("tools", List.of(), surface.tools());
        assertEquals("overlays", List.of(), surface.overlays());
        assertEquals("view modes", List.of(), surface.viewModes());
        assertEquals("insert sources", List.of(), surface.insertSources());
        assertEquals("drop handlers", List.of(), surface.dropHandlers());
        assertEquals("inspector sections", List.of(), surface.sections());
        assertEquals("commands", List.of(), surface.commands());
    }

    /** And it is still a working plane — the point of shipping nothing is not shipping nothing useful. */
    @Test
    public void andItIsStillAPlane() {
        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(), List.of());

        assertEquals(List.of(), surface.surface().items());
        assertEquals(1f, surface.surface().zoom(), 0.0001f);
        surface.surface().setZoom(2f);
        assertEquals(2f, surface.surface().zoom(), 0.0001f);
    }

    /** A declaration that does nothing is refused where it is written, not where it is clicked. */
    @Test
    public void aToolWithNoFactoryIsRefused() {
        SurfaceEditor surface = new SurfaceEditor(TestSurface.policy(), List.of());
        try {
            surface.registerTool(ToolKind.of("test:empty", "Empty"));
            assertTrue("a tool with no factory must be refused", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("test:empty"));
        }
    }
}
