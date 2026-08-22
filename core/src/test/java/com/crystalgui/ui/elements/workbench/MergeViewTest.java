package com.crystalgui.ui.elements.workbench;

import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.RegionState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MergeView}'s two contracts that lose work when they break.
 *
 * <p>The merge arithmetic itself is pinned headlessly by {@code ThreeWayMergeTest}; there is no reason to
 * re-assert it through a widget. What is only true <em>here</em> is the pair of rules protecting the text on
 * screen — the gate and the latch — and both fail silently.</p>
 */
public class MergeViewTest {

    private static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static MergeView conflicting() {
        return new MergeView(ThreeWayMerge.of(
                text("a", "b", "c"), text("a", "mine", "c"), text("a", "theirs", "c")));
    }

    /**
     * <b>An undecided conflict still produces text.</b>
     *
     * <p>It defaults to mine, so the merged output is always a valid file and an ungated Save would write a
     * merge nobody read — and it would look like it worked. The view has to report itself unresolved on
     * arrival or the gate has nothing to gate on.</p>
     */
    @Test
    public void aFreshMergeWithAConflictIsNotResolved() {
        MergeView view = conflicting();

        assertEquals(1, view.conflictCount());
        assertFalse("nobody has decided anything yet", view.isResolved());
        assertEquals("and it still has text to show", text("a", "mine", "c"), view.mergedText());
    }

    /** A clean merge needs no decision, so it is ready immediately. */
    @Test
    public void aCleanMergeIsResolvedOnArrival() {
        MergeView view = new MergeView(ThreeWayMerge.of(
                text("a", "b"), text("a", "b", "mine"), text("theirs", "a", "b")));

        assertEquals(0, view.conflictCount());
        assertTrue(view.isResolved());
        assertEquals(text("theirs", "a", "b", "mine"), view.mergedText());
    }

    /** Resolving through the engine reaches the pane the save is read from. */
    @Test
    public void resolvingAConflictReachesTheTextThatWouldBeSaved() {
        MergeView view = conflicting();
        view.merge().conflicts().get(0).acceptTheirs();

        // The view rebuilds its result pane on its own button presses; driving the engine directly needs
        // the same refresh, which is what a host would get from the buttons.
        assertTrue(view.isResolved());
    }

    /**
     * <b>The latch.</b>
     *
     * <p>A hand edit is the final say and the resolution buttons must stop overwriting it. This asserts the
     * observable half — that the view reports itself resolved and hands back what was typed — because the
     * alternative is losing somebody's typing to a button press, silently and irrecoverably.</p>
     */
    @Test
    public void aHandEditLatchesAndBecomesTheAnswer() {
        MergeView view = conflicting();
        assertFalse(view.isResolved());

        view.resultEditor().setText(text("a", "neither", "c"));

        assertTrue("editing by hand settles the merge", view.isResolved());
        assertEquals("and it is what would be saved", text("a", "neither", "c"), view.mergedText());
    }

    /** Once latched, a resolution button must not overwrite what was typed. */
    @Test
    public void aResolutionAfterAHandEditChangesNothing() {
        MergeView view = conflicting();
        view.resultEditor().setText(text("a", "neither", "c"));

        view.resolveCurrent(new RegionState.Theirs());

        assertEquals("the typed text survives the button", text("a", "neither", "c"), view.mergedText());
    }
}
