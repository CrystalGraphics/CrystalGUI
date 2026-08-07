package com.crystalgui.ui.elements.dock;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DockInput} and {@link DockPaneProvider} selection — the runtime half of what a tab shows.
 *
 * <p>A {@code DockPanelRef} is the <b>persisted</b> form, shaped by what a session must write down. An
 * input is the runtime form and answers what the ref cannot without every caller re-deriving it: what
 * {@link Resource} this panel is about.</p>
 */
public class DockInputTest {

    @Test
    public void anInputCarriesTheResourceItsRefNames() {
        DockPanelRef ref = new DockPanelRef("file")
                .withState(DockPanelRef.PATH, "mymod.proj:src/Main.java");
        DockInput input = DockInput.of(ref);

        assertEquals("file", input.typeId());
        assertNotNull(input.resource());
        assertEquals(Resource.of(CgPath.parse("mymod.proj:src/Main.java")), input.resource());
        assertEquals("the ref is the persisted form and must survive untouched", ref, input.ref());
    }

    /** A tool window is a perfectly good input that is about nothing. */
    @Test
    public void anInputWithNoPathHasNoResource() {
        assertNull(DockInput.of(new DockPanelRef("problems")).resource());
    }

    /**
     * <b>Unparseable state degrades to null rather than throwing.</b>
     *
     * <p>This runs while a layout is being built from a saved session. A single odd value must cost that
     * one panel its content, never the whole restore — the same rule {@code DockPanelRegistry} already
     * follows for a panel type nobody registers any more.</p>
     */
    @Test
    public void anUnparseablePathYieldsNoResourceRatherThanAThrow() {
        DockInput input = DockInput.of(new DockPanelRef("file").withState(DockPanelRef.PATH, "::::"));
        assertNull(input.resource());
        assertEquals("file", input.typeId());
    }

    @Test
    public void aDerivedResourceSurvivesTheRoundTripThroughARef() {
        Resource generated = Resource.derived("shader-generated",
                Resource.of(CgPath.parse("mymod.proj:fire.shadergraph")));
        DockInput input = DockInput.of("shadersource", generated);

        assertEquals(generated, input.resource());
        assertEquals(generated, DockInput.of(input.ref()).resource());
        assertNotNull(input.resource().origin());
    }

    /**
     * <b>Matching is ref equality, deliberately.</b>
     *
     * <p>A ref's identity includes its state, which is what makes two file tabs on different paths
     * different panels. Comparing resources alone would make two panel <em>types</em> over one file look
     * like the same input — and a retarget would then be skipped, leaving a pane pointed at the wrong
     * thing while everything still looked right.</p>
     */
    @Test
    public void twoTypesOverOneResourceAreDifferentInputs() {
        Resource file = Resource.of(CgPath.parse("mymod.proj:a.txt"));
        assertFalse(DockInput.of("file", file).matches(DockInput.of("preview", file)));
        assertTrue(DockInput.of("file", file).matches(DockInput.of("file", file)));
    }

    // ── Provider selection ──────────────────────────────────────────────────────────────────────

    private static DockPaneProvider provider(String acceptedType, int priority) {
        return new DockPaneProvider() {
            @Override public boolean accepts(DockInput input) { return acceptedType.equals(input.typeId()); }
            @Override public DockPane create() { throw new AssertionError("must not be asked to create"); }
            @Override public int priority() { return priority; }
        };
    }

    @Test
    public void aProviderThatRefusesIsNotSelected() {
        DockPanelRegistry<Object> registry = new DockPanelRegistry<>();
        registry.registerPane(provider("file", 0));
        assertNull(registry.paneProviderFor(DockInput.of("problems", null)));
    }

    /** Two providers accepting one input: the higher priority wins — IntelliJ's FileEditorPolicy. */
    @Test
    public void theHigherPriorityProviderWins() {
        DockPanelRegistry<Object> registry = new DockPanelRegistry<>();
        DockPaneProvider plain = provider("file", 0);
        DockPaneProvider special = provider("file", 10);
        registry.registerPane(plain);
        registry.registerPane(special);

        assertEquals(special, registry.paneProviderFor(DockInput.of("file", null)));

        // And registration order must not decide it.
        DockPanelRegistry<Object> reversed = new DockPanelRegistry<>();
        reversed.registerPane(special);
        reversed.registerPane(plain);
        assertEquals(special, reversed.paneProviderFor(DockInput.of("file", null)));
    }
}
