package com.crystalgui.ui;

import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.elements.chrome.ProblemNode;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Problems panel — VS Code's markers view, grouped by file over the workspace index.
 *
 * <p>What these pin is the seam: the panel shows what {@link Markers} holds, grouped, filtered, and it
 * <em>reports</em> a choice rather than navigating. Nothing here asserts a pixel.</p>
 */
public class ProblemsPanelTest extends UiTestBase {

    private UIWindow window;
    private ProblemsPanel panel;
    private Markers markers;

    private final Resource shader = Resource.of("project", "shaders/water.glsl");
    private final Resource util = Resource.of("project", "lib/util.glsl");

    @Before
    public void setUp() {
        markers = new Markers();
        panel = new ProblemsPanel();
        panel.layout(l -> l.width(360).height(240));
        UIElement root = new UIElement().layout(l -> l.width(360).height(240));
        root.addChild(panel);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(360, 240);
        window.setUiScale(1f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private DiagnosticSet give(Resource resource, Diagnostic... problems) {
        DiagnosticSet set = markers.forResource(resource);
        if (set == null) set = markers.attach(resource, new DiagnosticSet());
        set.setAll(List.of(problems));
        return set;
    }

    private static Diagnostic error(int row, String message) {
        return at(row, DiagnosticSeverity.ERROR, message);
    }

    private static Diagnostic at(int row, DiagnosticSeverity severity, String message) {
        return new Diagnostic(new TextPoint(row, 0), new TextPoint(row, 1), severity, message, null, null);
    }

    private void expandEverything() {
        for (Resource resource : List.of(shader, util)) {
            if (markers.forResource(resource) != null) {
                panel.tree().setExpanded(ProblemNode.file(resource), true);
            }
        }
        settle();
    }

    /**
     * <b>Grouped by file, which is the whole reason the resource index exists.</b>
     *
     * <p>The panel used to bind to the active document's set, so it could only ever show the file already
     * on screen — the one case where the editor's error stripe already tells you everything.</p>
     */
    @Test
    public void problemsAreGroupedByTheFileTheyAreIn() {
        give(shader, error(4, "undefined variable"), error(9, "no output node"));
        give(util, at(2, DiagnosticSeverity.WARNING, "unused uniform"));
        panel.bindTo(markers);
        settle();

        assertEquals("both files should head the tree", List.of(shader, util), panel.visibleFiles());
        assertTrue("a collapsed file must not spill its problems", panel.visibleProblems().isEmpty());

        expandEverything();
        assertEquals(3, panel.visibleProblems().size());
    }

    /** A file with nothing left after filtering stops being a row, rather than expanding onto nothing. */
    @Test
    public void aFileFilteredToNothingLeavesTheTree() {
        give(shader, error(4, "undefined variable"));
        give(util, at(2, DiagnosticSeverity.WARNING, "unused uniform"));
        panel.bindTo(markers);
        settle();
        assertEquals(2, panel.visibleFiles().size());

        panel.setSeverityShown(DiagnosticSeverity.WARNING, false);
        settle();

        assertEquals("the warning-only file should have gone", List.of(shader), panel.visibleFiles());
    }

    /** The text filter matches the message, and takes its file with it when nothing survives. */
    @Test
    public void theTextFilterNarrowsToMatchingMessages() {
        give(shader, error(4, "undefined variable"), error(9, "no output node"));
        panel.bindTo(markers);
        settle();

        panel.setTextFilter("output");
        expandEverything();

        assertEquals(1, panel.visibleProblems().size());
        assertEquals("no output node", panel.visibleProblems().get(0).message());

        panel.setTextFilter("nothing matches this");
        settle();
        assertTrue(panel.visibleFiles().isEmpty());
    }

    /** "Show active file only" is the same list asked a narrower question, not a second panel. */
    @Test
    public void showingOnlyOneFileHidesTheRest() {
        give(shader, error(4, "undefined variable"));
        give(util, error(2, "broken include"));
        panel.bindTo(markers);
        settle();

        panel.showOnly(util);
        settle();
        assertEquals(List.of(util), panel.visibleFiles());

        panel.showOnly(null);
        settle();
        assertEquals(2, panel.visibleFiles().size());
    }

    /**
     * <b>Choosing a problem names its file.</b>
     *
     * <p>The panel deliberately cannot navigate: the problem clicked is routinely in a file that is not
     * open, so going there means opening the document first — a workspace-level act. The node carries the
     * resource for exactly that reason.</p>
     */
    @Test
    public void choosingAProblemReportsItWithItsFile() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();
        expandEverything();

        List<ProblemNode> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);

        int problemRow = -1;
        for (int i = 0; i < panel.tree().visibleRows().size(); i++) {
            if (!panel.tree().rowAt(i).item().isFile()) {
                problemRow = i;
                break;
            }
        }
        assertTrue("no problem row was realised", problemRow >= 0);
        panel.tree().onRowActivated.emit(problemRow);

        assertEquals(1, chosen.size());
        assertEquals(shader, chosen.get(0).resource());
        assertEquals("undefined variable", chosen.get(0).diagnostic().message());
    }

    /**
     * Activating a file heading expands it rather than reporting a choice.
     *
     * <p>A heading is not a destination, and emitting for one would make "open this problem" mean
     * something different depending on which row you hit.</p>
     */
    @Test
    public void activatingAFileHeadingIsNotAChoice() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        List<ProblemNode> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);
        panel.tree().onRowActivated.emit(0);
        settle();

        assertTrue("a heading reported itself as a destination", chosen.isEmpty());
        assertFalse("and it did not expand", panel.visibleProblems().isEmpty());
    }

    /**
     * <b>A file heading has a chevron, and it is the part that takes the click.</b>
     *
     * <p>The first pass of this panel grouped correctly and shipped with <em>nothing to press</em>: the
     * template had no twisty, so there was no affordance and no state to draw. The tests passed because
     * they toggled through {@code onRowActivated} directly, which is the one route a user does not take.
     * What is pinned here is the affordance's existence, not the toggle.</p>
     *
     * <p>Every other slot refuses the pointer so a press lands on the row — click targeting takes the exact
     * element hit and never walks up to a handler-bearing ancestor, which is why the chevron has to be its
     * own hit target to fold on one click.</p>
     */
    @Test
    public void aFileHeadingHasAChevronThatTakesThePointer() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        UIElement row = panel.tree().getElementsByClassName(ProblemsPanel.ROW_CLASS).get(0);
        UIElement twisty = row.querySelector("." + ProblemsPanel.TWISTY_CLASS);
        assertNotNull("a file heading with no chevron cannot be folded by anyone", twisty);
        assertTrue("the chevron must keep the pointer to fold on one click", twisty.isHitTest());

        UIElement label = row.querySelector("." + ProblemsPanel.MESSAGE_CLASS);
        assertNotNull(label);
        assertFalse("a slot that eats the press stops the row ever being chosen",
                label.isHitTest());
    }

    /**
     * The row carries the fold state as a class, which is the only thing the chevron's artwork keys off.
     *
     * <p>{@code TreeView} stamps it; what this pins is that a problem row is a <em>leaf</em> and a file is
     * not, so the sheet can draw a chevron on one and blank the slot on the other.</p>
     */
    @Test
    public void foldStateReachesTheRowAsAClass() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        UIElement heading = panel.tree().getElementsByClassName(ProblemsPanel.ROW_CLASS).get(0);
        assertTrue("a collapsed file must say so",
                heading.hasClass(com.crystalgui.ui.elements.tree.TreeView.COLLAPSED_CLASS));

        expandEverything();
        heading = panel.tree().getElementsByClassName(ProblemsPanel.ROW_CLASS).get(0);
        assertTrue("an expanded file must say so",
                heading.hasClass(com.crystalgui.ui.elements.tree.TreeView.EXPANDED_CLASS));
    }

    /**
     * <b>Collapsing takes the children with it.</b>
     *
     * <p>It did not: the chevron folded from inside its own mouse-down, and {@code setExpanded} refreshes
     * synchronously — so the refresh recycled every realised row <em>including the one under the pointer</em>,
     * from inside that row's own listener. Two files showed as collapsed with their problems still listed
     * beneath them, one of them drawn twice. <b>Expanding looked perfectly fine</b>, which is how it
     * survived a screenshot, and why this test folds as well as unfolds.</p>
     */
    @Test
    public void collapsingRemovesTheRowsItOpened() {
        give(shader, error(4, "undefined variable"));
        give(util, error(2, "broken include"));
        panel.bindTo(markers);
        settle();

        expandEverything();
        assertEquals(2, panel.visibleProblems().size());

        panel.tree().setExpanded(ProblemNode.file(shader), false);
        panel.tree().setExpanded(ProblemNode.file(util), false);
        settle();

        assertTrue("the children outlived the fold", panel.visibleProblems().isEmpty());
        assertEquals("and the headings went with them", 2, panel.visibleFiles().size());
    }

    /**
     * <b>The chevron's route works, not just the tree underneath it.</b>
     *
     * <p>Both of this panel's fold bugs lived in the route rather than in {@code TreeView}. The first
     * refreshed synchronously from inside the press. The second parked the fold for "the next layout" —
     * and layout only runs when geometry changes, which pressing a chevron does not, so the fold was never
     * applied at all and turned up later when something unrelated disturbed the panel. Both tests I had
     * called {@code setExpanded} directly and sailed past both.</p>
     */
    @Test
    public void foldingThroughTheChevronRouteTakesEffect() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();
        assertTrue(panel.visibleProblems().isEmpty());

        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertEquals("a chevron press never reached the tree", 1, panel.visibleProblems().size());

        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertTrue("and it must fold back", panel.visibleProblems().isEmpty());
    }

    /**
     * <b>What a row says about itself must match what is under it.</b>
     *
     * <p>The invariant both bugs broke, and the one worth asserting because it holds however the fold is
     * reached: a row drawn as collapsed with its children still listed beneath it is the symptom either
     * way. Screenshots showed exactly that — two headings with a chevron pointing right and their problems
     * still on screen.</p>
     */
    @Test
    public void aCollapsedRowNeverHasChildrenUnderIt() {
        give(shader, error(4, "undefined variable"), error(9, "no output node"));
        give(util, error(2, "broken include"));
        panel.bindTo(markers);
        settle();

        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertCollapsedRowsHaveNoChildren();

        panel.requestFold(ProblemNode.file(util));
        settle();
        assertCollapsedRowsHaveNoChildren();

        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertCollapsedRowsHaveNoChildren();
    }

    /** Walks the flattened rows and checks every collapsed file is followed by another file, or nothing. */
    private void assertCollapsedRowsHaveNoChildren() {
        List<com.crystalgui.ui.elements.tree.TreeRow<ProblemNode>> rows = panel.tree().visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (!row.item().isFile() || row.expanded()) continue;
            boolean followedByAChild = i + 1 < rows.size() && !rows.get(i + 1).item().isFile();
            assertFalse("a collapsed " + row.item().resource().name() + " still lists its problems",
                    followedByAChild);
        }
    }

    /**
     * <b>A panel closed and reopened still works.</b>
     *
     * <p>This is what actually broke, and it broke at the framework level rather than in this panel:
     * {@code ListView.tickFrame} called {@code dispose()} when it found itself detached, and dispose is
     * one-way — it drops the model subscription for good. Closing a dock panel detaches it; reopening
     * re-attaches a view that is back on screen, ticking again, and no longer listening to its model. Rows
     * went stale, folds stopped landing, and nothing reported it. <b>Every list and tree in the
     * application had this</b>; it surfaced here because a Problems tree is the thing people close and
     * reopen from the activity bar.</p>
     *
     * <p>A detach now releases the subscription and re-attach re-makes it. An explicit {@code dispose()}
     * stays one-way, because "I am finished with this" must not be undone by the next reparent.</p>
     */
    @Test
    public void aPanelSurvivesBeingClosedAndReopened() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        UIElement root = panel.getParent();
        assertNotNull(root);

        panel.removeSelf();
        settle();
        root.addChild(panel);
        settle();

        assertTrue("the tree stopped listening to its model",
                panel.tree().isListeningToModel());

        // The fold has to still land...
        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertEquals("a reopened panel's chevrons are dead", 1, panel.visibleProblems().size());

        // ...and so does a change arriving from the index while it was away.
        give(util, error(2, "broken include"));
        settle();
        assertEquals("a reopened panel stopped following the workspace", 2, panel.visibleFiles().size());
    }

    /** The panel follows the index rather than being told: a later compile arrives on its own. */
    @Test
    public void aLaterChangeReachesThePanel() {
        panel.bindTo(markers);
        settle();
        assertTrue(panel.visibleFiles().isEmpty());

        give(shader, error(4, "undefined variable"));
        settle();

        assertEquals(List.of(shader), panel.visibleFiles());
    }

    /**
     * <b>Filtered to nothing does not read as "everything is fixed".</b>
     *
     * <p>A clean workspace and an over-narrow filter are the same empty tree and completely different
     * news, and only one of them is worth celebrating.</p>
     */
    @Test
    public void anEmptyTreeSaysWhichKindOfEmptyItIs() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        UIElement empty = panel.querySelector("." + ProblemsPanel.EMPTY_CLASS);
        assertNotNull(empty);

        panel.setTextFilter("nothing matches this");
        settle();
        assertTrue("a filtered-out tree claimed the workspace was clean",
                ((com.crystalgui.ui.elements.UIText) empty).getText().toLowerCase().contains("filter"));
    }
}
