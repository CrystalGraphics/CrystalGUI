package com.crystalgui.ui;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.elements.tree.TreeSearch;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.ui.elements.chrome.ProblemNode;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertSame;
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

    /**
     * Folds every heading — the state these tests used to get for free.
     *
     * <p>Headings open as they arrive now, which is what both references do and what the panel is opened
     * for. The tests below are about folding and grouping rather than about the default, so they state the
     * collapsed premise instead of assuming it.</p>
     */
    private void collapseEverything() {
        for (Resource resource : List.of(shader, util)) {
            if (markers.forResource(resource) != null) {
                panel.tree().setExpanded(ProblemNode.file(resource), false);
            }
        }
        settle();
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
        collapseEverything();

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

        // FOLDED FIRST, so activation is an unfold. Headings open on arrival now, and this test is about
        // what activating one DOES rather than about which way it happens to go from the default.
        collapseEverything();

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

        collapseEverything();
        UIElement heading = panel.tree().realisedRows().get(0);
        assertNotNull("no row is realised, so this asserts nothing", heading);
        assertTrue("a collapsed file must say so",
                heading.hasClass(com.crystalgui.ui.elements.tree.TreeView.COLLAPSED_CLASS));

        expandEverything();
        heading = panel.tree().realisedRows().get(0);
        assertNotNull(heading);
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
        collapseEverything();
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
        List<com.crystalgui.core.collection.tree.TreeRow<ProblemNode>> rows = panel.tree().visibleRows();
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

        collapseEverything();
        // The fold has to still land...
        panel.requestFold(ProblemNode.file(shader));
        settle();
        assertEquals("a reopened panel's chevrons are dead", 1, panel.visibleProblems().size());

        // ...and so does a change arriving from the index while it was away.
        give(util, error(2, "broken include"));
        settle();
        assertEquals("a reopened panel stopped following the workspace", 2, panel.visibleFiles().size());
    }

    /** Activates a view-menu row by its label — the route a user takes, not the setter behind it. */
    private void chooseViewOption(String label) {
        for (com.crystalgui.ui.elements.MenuItem item : panel.viewMenu().getItems()) {
            if (label.equals(item.getText())) {
                panel.viewMenu().onItemActivated.emit(item);
                settle();
                return;
            }
        }
        throw new AssertionError("no view-menu row labelled " + label);
    }

    /**
     * <b>The view menu filters, and its rows carry VS Code's wording.</b>
     *
     * <p>{@code Show Errors} / {@code Show Warnings} / {@code Show Infos}, then {@code Show Active File
     * Only} below a separator — the labels and the order are {@code markersViewActions.ts}'s, checked
     * against it rather than remembered. {@code Hide Excluded Files} is deliberately absent: it filters
     * against workspace exclude globs, which this engine does not have, so the row would be inert.</p>
     */
    @Test
    public void theViewMenuTurnsSeveritiesOffAndOn() {
        give(shader, error(4, "undefined variable"));
        give(util, at(2, DiagnosticSeverity.WARNING, "unused uniform"));
        panel.bindTo(markers);
        settle();
        assertEquals(2, panel.visibleFiles().size());

        chooseViewOption("Show Warnings");
        assertEquals("the warning-only file survived", List.of(shader), panel.visibleFiles());

        chooseViewOption("Show Warnings");
        assertEquals("and it must come back", 2, panel.visibleFiles().size());

        chooseViewOption("Show Errors");
        assertEquals("errors are filterable too", List.of(util), panel.visibleFiles());
    }

    /**
     * <b>The File tab narrows to the document in front, and follows it.</b>
     *
     * <p>IntelliJ's two tabs rather than VS Code's {@code Show Active File Only} menu row, because the
     * scope has to be readable without opening anything — an empty Problems panel means two different
     * things depending which scope you are in, and a mode hidden in a menu is one you can be in without
     * knowing.</p>
     *
     * <p>The active file is told to the panel on every tab change whether or not the File scope is in
     * force, so switching to it narrows to what you are looking at <em>now</em> rather than to whatever
     * was in front last time.</p>
     */
    @Test
    public void theFileTabFollowsTheDocumentInFront() {
        give(shader, error(4, "undefined variable"));
        give(util, error(2, "broken include"));
        panel.bindTo(markers);
        panel.setActiveResource(shader);
        settle();

        assertTrue("IntelliJ opens on the File tab", panel.isFileScope());
        assertEquals(List.of(shader), panel.visibleFiles());

        // The document in front changed while the File scope was in force.
        panel.setActiveResource(util);
        settle();
        assertEquals("the scope did not follow the document", List.of(util), panel.visibleFiles());

        panel.setFileScope(false);
        settle();
        assertEquals("Project Errors must show the whole workspace", 2, panel.visibleFiles().size());

        panel.setFileScope(true);
        settle();
        assertEquals(List.of(util), panel.visibleFiles());
    }

    /**
     * <b>An empty tree says which kind of empty it is — and there are three.</b>
     *
     * <p>An empty File tab, an empty workspace and a tree filtered to nothing are the same picture and
     * completely different news; only one is worth celebrating. This is the reason the scope is a visible
     * tab rather than a checkbox.</p>
     */
    @Test
    public void theEmptyStateNamesTheScope() {
        give(util, error(2, "broken include"));
        panel.bindTo(markers);
        panel.setActiveResource(shader);
        settle();

        UIElement empty = panel.querySelector("." + ProblemsPanel.EMPTY_CLASS);
        assertNotNull(empty);
        String inFileScope = ((com.crystalgui.ui.elements.UIText) empty).getText();
        assertTrue("a clean FILE read as a clean workspace: " + inFileScope,
                inFileScope.contains(shader.name()));

        panel.setFileScope(false);
        settle();
        assertFalse("the workspace is not clean — util has a problem",
                panel.visibleFiles().isEmpty());
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

    // -- Keys reach a list nobody has clicked a row in ---------------------------------------------

    private int heldModifiers = 0;

    /**
     * A key through the REAL sink — {@code consumeKeyboardEvent}, not {@code sendInputEvent}.
     *
     * <p>The distinction is the whole reason this bug survived: dispatching straight at an element skips
     * focus resolution, so a test written that way passes against a widget that can never be focused.
     * Modifiers are read from the platform rather than the event, and the sink is dead until a frame has
     * completed, so both have to be real here too.</p>
     */
    private void key(int code, char ch, int mods) {
        heldModifiers = mods;
        TestPlatformService.get().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return heldModifiers; }
            @Override public int translateKeyboardCodes(int c) { return c; }
            @Override public boolean isKeyDown(int c) { return false; }
            @Override public int translateMouseCodes(int c) { return c; }
            @Override public boolean isMouseDown(int c) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String t) { }
        });
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(ch, code, true, false, 0L));
        settle();
    }

    /** Focus on the LIST, which is what clicking the empty space under the rows now gives you. */
    private void focusTheList() {
        window.getInputHandler().requestFocus(panel.tree());
        settle();
        assertSame("the list could not take focus, so nothing below tests anything",
                panel.tree(), window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>Ctrl+F opens the search with only the list focused.</b>
     *
     * <p>It did not, and the reason was two steps back from the keystroke: {@code FocusPolicy} defaults to
     * {@code NONE}, so a {@code ListView} could hold focus only by way of a row, and
     * {@code consumeKeyboardEvent} dispatches <em>nothing</em> while {@code focusedElement} is null. Every
     * key the list owns — the arrows, type-ahead, this — was silently dead until a row had been clicked.</p>
     */
    @Test
    public void ctrlFOpensTheSearchWithOnlyTheListFocused() {
        give(shader, error(1, "output missing"));
        panel.bindTo(markers);
        settle();
        expandEverything();
        focusTheList();

        key(CgKeyCodes.KEY_F, 'f', CgModifiers.CTRL);
        assertTrue("Ctrl+F did not open the find bar", panel.isFindOpen());
    }

    /** And a printable character starts one, which is IntelliJ's speed search. */
    @Test
    public void typingWithOnlyTheListFocusedStartsASearch() {
        give(shader, error(1, "output missing"));
        panel.bindTo(markers);
        settle();
        expandEverything();
        focusTheList();

        key(CgKeyCodes.KEY_O, 'o', 0);
        assertTrue("type-ahead did not open the find bar", panel.isFindOpen());
        assertNotNull(panel.search());
        assertEquals("the first character was dropped", "o", panel.search().query());
    }


    // -- Headings open by default ------------------------------------------------------------------

    /**
     * <b>A problem list arrives to be read.</b>
     *
     * <p>Both references show theirs expanded — VS Code's Problems view and IntelliJ's Problems tool
     * window — because a collapsed heading shows a filename and a count, which is what the status bar
     * already says. The panel is opened to see the messages.</p>
     */
    @Test
    public void headingsOpenWhenTheirProblemsArrive() {
        give(shader, error(1, "one"), error(2, "two"));
        panel.bindTo(markers);
        settle();

        assertEquals("the heading was left folded over its own problems",
                2, panel.visibleProblems().size());
    }

    /**
     * <b>But a heading the user folded stays folded.</b>
     *
     * <p>This runs on every refresh — a diagnostic arriving, a tab switching, a filter changing — so
     * re-opening whatever is closed would make a heading impossible to fold at all.</p>
     */
    @Test
    public void aFoldedHeadingIsNotReopenedByARefresh() {
        give(shader, error(1, "one"));
        panel.bindTo(markers);
        settle();
        panel.tree().setExpanded(ProblemNode.file(shader), false);
        settle();
        assertTrue("the fold did not take", panel.visibleProblems().isEmpty());

        // Something else changes, and the panel refreshes.
        give(util, error(5, "elsewhere"));
        settle();

        assertTrue("a refresh reopened a heading the user had folded",
                panel.visibleProblems().stream().noneMatch(d -> "one".equals(d.message())));
    }

    /** A heading that appears later opens too — it is new, not folded. */
    @Test
    public void aHeadingArrivingLaterOpensAsWell() {
        give(shader, error(1, "one"));
        panel.bindTo(markers);
        settle();

        give(util, error(5, "elsewhere"));
        settle();

        assertTrue("the second file's heading stayed folded",
                panel.visibleProblems().stream().anyMatch(d -> "elsewhere".equals(d.message())));
    }


    /**
     * <b>A query matching the FILE'S NAME keeps the file.</b>
     *
     * <p>The filter read messages only, while the search treats a heading as searchable by its file name —
     * two different answers to "does this match", inside one panel. It showed as {@code g} listing both
     * shadergraphs and {@code graph} listing one: {@code new.shadergraph} has "graph" in its name and not
     * in its message, so the row the search would have marked was filtered away before the marking ran.</p>
     */
    @Test
    public void aQueryMatchingTheFileNameKeepsItsProblems() {
        give(shader, error(1, "no mention of the query in here"));
        give(util, error(2, "also nothing"));
        panel.bindTo(markers);
        settle();

        // "water" is in shaders/water.glsl and in neither message.
        panel.search().setMode(TreeSearch.Mode.FILTER);
        panel.search().setQuery("water");
        settle();

        assertEquals("a file whose NAME matched was filtered away",
                List.of(shader), panel.visibleFiles());
        assertEquals("and its problems came with it — a heading onto nothing is worse than either",
                1, panel.visibleProblems().size());
    }

    /** And a query matching neither still empties the tree. */
    @Test
    public void aQueryMatchingNothingStillEmptiesTheTree() {
        give(shader, error(1, "no mention of the query in here"));
        panel.bindTo(markers);
        settle();

        panel.search().setMode(TreeSearch.Mode.FILTER);
        panel.search().setQuery("zzzz-nothing");
        settle();

        assertTrue("the name check must not keep everything", panel.visibleFiles().isEmpty());
    }


    // -- The toggles reach the matching ------------------------------------------------------------

    /**
     * <b>Match Case and Words reach the panel's own filtering.</b>
     *
     * <p>They did not. The bar held the options, and {@code Model.setQuery} took a {@code String} — so
     * every model rebuilt its own {@code SearchQuery} from the text and dropped them. {@code GRAPH} with
     * both toggles lit still matched {@code new.shadergraph}, which is the shape the interface change
     * (a {@code SearchQuery} in, options and all) exists to make impossible.</p>
     */
    @Test
    public void matchCaseAndWordsReachTheFilter() {
        give(shader, error(1, "nothing relevant"));
        panel.bindTo(markers);
        settle();
        TreeSearch<ProblemNode> search = panel.search();
        assertNotNull(search);
        search.setMode(TreeSearch.Mode.FILTER);

        // "water" is in shaders/water.glsl, so the file name carries the match.
        search.setQuery("water");
        settle();
        assertEquals("the plain query should find it", List.of(shader), panel.visibleFiles());

        search.setSearchOptions(SearchQuery.Options.DEFAULT.withMatchCase(true));
        search.setQuery("WATER");
        settle();
        assertTrue("Match Case never reached the filter", panel.visibleFiles().isEmpty());

        search.setSearchOptions(SearchQuery.Options.DEFAULT.withWholeWords(true));
        search.setQuery("ater");
        settle();
        assertTrue("Words never reached the filter", panel.visibleFiles().isEmpty());

        search.setSearchOptions(SearchQuery.Options.DEFAULT);
        search.setQuery("ater");
        settle();
        assertEquals("and without the options it is a plain substring again",
                List.of(shader), panel.visibleFiles());
    }

    /**
     * <b>Folding a heading lands focus on the row, and never on nothing.</b>
     *
     * <p>{@code emitMouseDown} blurs the focus owner <em>before</em> it dispatches, and a chevron is
     * {@code FocusPolicy.NONE} — it has to be, since focusing a fold arrow is meaningless. So the press
     * used to blur whatever had focus and then focus nothing at all, leaving the whole window with
     * {@code focusedElement == null}: no ring anywhere, and {@code consumeKeyboardEvent} dispatches
     * nothing whatsoever in that state, so the keyboard went dead after a fold. It also let
     * {@code ListView.restoreFocusIfRealised} read null as "nobody owns this" and pull focus onto a row a
     * frame later, so the ring left the editor tab and reappeared somewhere the user never clicked —
     * reported as the panel flickering.</p>
     *
     * <p>The fix is the DOM's own rule, in {@code emitMouseDown}: click-focus walks up to the nearest
     * focusable ancestor, which is why clicking a {@code <button>}'s inner text focuses the button. Here
     * that ancestor is the row, so focus moves <em>into</em> the panel — which is also what both
     * references do when you click anything in a tree.</p>
     *
     * <p>Driven through {@code emitMouseDown}'s own route rather than {@code sendInputEvent}: focus
     * resolution is the thing under test and dispatching straight at the element skips it entirely.</p>
     */
    @Test
    public void foldingAHeadingLandsFocusOnTheRow() {
        give(shader, error(3, "one"), error(9, "two"));
        panel.bindTo(markers);
        settle();

        // Somewhere else in the window entirely -- standing in for the editor tab or the rail button the
        // user was last in. Focusable on click, which is what every such control is.
        UIElement elsewhere = new UIElement().layout(l -> l.width(10).height(10));
        elsewhere.setFocusPolicy(FocusPolicy.CLICK);
        panel.getParent().addChild(elsewhere);
        settle();
        window.getInputHandler().requestFocus(elsewhere);
        settle();
        assertSame(elsewhere, window.getInputHandler().getFocusedElement());

        // Through the realised row rather than querySelector: rows are internal children, which public
        // traversal skips by design.
        UIElement headingRow = panel.tree().realisedRows().get(0);
        assertNotNull("the heading row is not realised, so this asserts nothing", headingRow);
        UIElement twisty = headingRow.querySelector("." + ProblemsPanel.TWISTY_CLASS);
        assertNotNull("no chevron, so this asserts nothing", twisty);
        press(twisty);

        // EVERY FRAME, not just once it settles. The second half of this bug was a single frame with no
        // focus owner at all: the fold recycles the row, recycling blurs it, and the restore used to be
        // deferred to the next frame. One frame is enough to see — every :focus ring in the window goes
        // out and comes back, which is what "the focus rings of everything flash" was. Asserting after a
        // settle passes against the broken version, because by then the restore has run.
        for (int frame = 0; frame < 8; frame++) {
            window.updateWithoutPainting();
            assertNotNull("frame " + frame + " had no focus owner at all — every ring in the window blinks,"
                            + " and consumeKeyboardEvent dispatches nothing while focus is null",
                    window.getInputHandler().getFocusedElement());
        }

        assertSame("focus should land on the row the chevron belongs to",
                headingRow, window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>Collapsing moves focus to the heading, and unfolding does not resurrect the old row.</b>
     *
     * <p>Two faults, one stale number. {@code focusedIndex} is clamped only by {@code setFocusedIndex},
     * which nothing calls when the model <em>shrinks</em> — so folding a heading with focus on its last
     * child left the index at 2 with one row in the list. While out of range that is invisible: nothing
     * is realised there, so focus simply went nowhere and the panel lost its ring. It stops being
     * invisible when the list grows back — unfolding found index 2 realised again and put focus on
     * whatever now occupies it, which is the last problem. Reported as "unfolding opens with the last
     * item focused for a split second".</p>
     *
     * <p>So: the index is clamped where the model is whole ({@code ListView.updateWindow}), and the fold
     * hands focus to the node it collapsed — the ARIA tree pattern, and the same rule the editor already
     * applies to folding a block the caret is in. A focus owner that is not on screen cannot be painted,
     * scrolled to or typed at.</p>
     */
    @Test
    public void collapsingMovesFocusToTheHeadingAndUnfoldingKeepsItThere() {
        give(shader, error(3, "one"), error(9, "two"));
        panel.bindTo(markers);
        settle();
        assertEquals("the heading plus its two problems", 3, panel.tree().visibleRows().size());

        UIElement lastRow = panel.tree().realisedRows().get(2);
        assertNotNull(lastRow);
        window.getInputHandler().requestFocus(lastRow);
        settle();
        assertEquals(2, panel.tree().getFocusedIndex());

        ProblemNode heading = ProblemNode.file(shader);
        panel.tree().requestToggle(heading);
        for (int frame = 0; frame < 6; frame++) {
            window.updateWithoutPainting();
            assertNotNull("folding left frame " + frame + " with no focus owner — the arrows cannot walk"
                    + " back out of what was just collapsed", window.getInputHandler().getFocusedElement());
        }
        assertEquals("focus should sit on the heading that was collapsed", 0, panel.tree().getFocusedIndex());

        panel.tree().requestToggle(heading);
        for (int frame = 0; frame < 6; frame++) window.updateWithoutPainting();
        assertEquals("unfolding resurrected the stale index and grabbed the last problem",
                0, panel.tree().getFocusedIndex());
    }

    /**
     * <b>A recycled row must not come back wearing {@code :hover}.</b>
     *
     * <p>A pooled row is deliberately kept in the tree as a {@code display: none} child, so nothing
     * detaches and nothing tells the input handler the element has stopped meaning what it meant.
     * {@code recycle} gives up <em>focus</em> for exactly that reason — "the element must give focus up
     * the moment it stops representing anything" — and hover was simply never included in that sentence.
     * So the flag rode the element through the pool: fold the heading with the pointer on its chevron,
     * unfold it, and the element that <em>was</em> the heading came back as the last problem, still
     * hovered. An untouched row lit up.</p>
     *
     * <p>The next hover diff corrects it, which is why it showed as a two-or-three-frame flash on the
     * wrong row rather than a stuck highlight — and why it read as a paint glitch rather than as state.
     * The whole gesture goes through real presses, because the hover the pooling carries is the one the
     * pointer left on the chevron.</p>
     */
    @Test
    public void unfoldingDoesNotBringARowBackHovered() {
        give(shader, error(3, "one"), error(9, "two"));
        panel.bindTo(markers);
        settle();

        UIElement twisty = panel.tree().realisedRows().get(0).querySelector("." + ProblemsPanel.TWISTY_CLASS);
        assertNotNull("no chevron, so this asserts nothing", twisty);
        press(twisty);
        settle();
        assertEquals("the heading should be folded", 1, panel.tree().visibleRows().size());

        press(panel.tree().realisedRows().get(0).querySelector("." + ProblemsPanel.TWISTY_CLASS));
        for (int frame = 0; frame < 6; frame++) {
            window.updateWithoutPainting();
            for (var entry : panel.tree().realisedRows().entrySet()) {
                // Row 0 is the one the pointer is genuinely on — its chevron is what was pressed.
                if (entry.getKey() == 0) continue;
                assertFalse("frame " + frame + ": row " + entry.getKey() + " came back from the pool"
                                + " hovered, with the pointer on the heading's chevron",
                        entry.getValue().isHovered());
            }
        }
    }

    /**
     * <b>A double click on a problem reports it; a single click only selects.</b>
     *
     * <p>Two failures with one symptom — "clicking does nothing, it does not even highlight". The panel
     * wired navigation to {@code onRowActivated}, whose javadoc says <em>Enter on the focused row</em>
     * and says the pointer half is the renderer's to raise; nobody raised it, so the panel was fully
     * keyboard-navigable and inert to the mouse. And {@code ListView} put {@code __selected__} on the row
     * while no stylesheet gave this panel a rule for it, so selection worked perfectly and painted
     * nothing — which is what made a wired-up widget look completely dead and sent the search to the
     * input layer twice.</p>
     *
     * <p>Through the real press route, because {@code sendInputEvent} skips focus resolution and
     * selection here is driven entirely by focus — a test that dispatches straight at the row passes
     * against a panel no click can ever select.</p>
     */
    @Test
    public void doubleClickingAProblemReportsItAndSelectsIt() {
        give(shader, error(4, "undefined variable"), error(9, "no output node"));
        panel.bindTo(markers);
        settle();

        List<ProblemNode> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);

        UIElement problemRow = panel.tree().realisedRows().get(1);
        assertNotNull("row 1 should be the first problem under the heading", problemRow);
        assertFalse("row 1 is a heading, so this asserts the wrong thing",
                panel.tree().rowAt(1).item().isFile());

        press(problemRow);
        settle();
        assertTrue("one press must only select — it is how you aim at a row, not how you leave it",
                chosen.isEmpty());
        assertTrue("the row was never marked selected, so nothing can highlight",
                problemRow.hasClass(com.crystalgui.ui.elements.list.ListView.SELECTED_CLASS));

        press(problemRow);
        settle();
        assertEquals("a double click should report exactly one problem", 1, chosen.size());
    }

    /**
     * <b>Clicking a file heading does not navigate.</b> It is not a destination — {@code chooseRow} says
     * so and folds it instead — and folding on a single click would be a second spelling of what the
     * chevron already does, on a tree that also has to support selecting a row.
     */
    @Test
    public void clickingAFileHeadingDoesNotReportAProblem() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        List<ProblemNode> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);

        // TWICE, so this asserts the heading rule rather than merely re-asserting that one press does
        // nothing — which is true of every row now and would make this pass for the wrong reason.
        press(panel.tree().realisedRows().get(0));
        press(panel.tree().realisedRows().get(0));
        settle();

        assertTrue("a heading is not a destination", chosen.isEmpty());
    }

    /**
     * <b>Copy puts the message on the clipboard, not the record.</b>
     *
     * <p>{@code ListRenderer.copyTextFor} defaults to {@code String.valueOf}, which for a record is its
     * generated {@code toString} — so copying a problem produced the whole object graph, {@code code=}
     * and {@code tags=[]} included. The override has to travel through {@code TreeView}'s renderer
     * adapter as well, which unwraps the flattened {@code TreeRow} the list actually holds; without that
     * forward the override exists and is never called.</p>
     */
    @Test
    public void copyingAProblemPutsItsMessageOnTheClipboard() {
        give(shader, error(4, "undefined variable"));
        panel.bindTo(markers);
        settle();

        int problemIndex = -1;
        for (int i = 0; i < panel.tree().visibleRows().size(); i++) {
            if (!panel.tree().rowAt(i).item().isFile()) { problemIndex = i; break; }
        }
        assertTrue("no problem row, so this asserts nothing", problemIndex >= 0);

        panel.tree().select(problemIndex);
        assertTrue("nothing to copy", panel.tree().canCopy());
        panel.tree().copy();

        assertEquals("undefined variable", CgPlatform.input().getClipboard());
    }

    /**
     * <b>A right-click does not change the selection.</b>
     *
     * <p>It opens a menu <em>about</em> a row; it does not choose it. Two separate things had to be told
     * so: {@code emitMouseDown} settled focus on any button (and a list drives selection from focus), and
     * the row's own press listener selected on any button too. Either alone left the menu destroying the
     * selection it was opened over — unrecoverable once a multi-selection exists.</p>
     */
    @Test
    public void rightClickingARowLeavesTheSelectionAlone() {
        give(shader, error(4, "undefined variable"), error(9, "no output node"));
        panel.bindTo(markers);
        settle();

        press(panel.tree().realisedRows().get(1));
        settle();
        assertTrue("the left press should have selected row 1", panel.tree().isSelected(1));

        rightPress(panel.tree().realisedRows().get(2));
        settle();

        assertTrue("a right-click moved the selection off the row the user had chosen",
                panel.tree().isSelected(1));
        assertFalse("a right-click selected the row it was merely asking about",
                panel.tree().isSelected(2));
    }

    /** A secondary-button press, through the same accumulate-and-dispatch route as a real one. */
    private void rightPress(UIElement target) {
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        int cx = (int) (target.getRuntimeCache().getX() + target.getRuntimeCache().getWidth() / 2f);
        int cy = (int) (target.getRuntimeCache().getY() + target.getRuntimeCache().getHeight() / 2f);
        window.getInputHandler().beginFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(cx, cy, 0, 0, 1, true, 0f, 0L));
        window.getInputHandler().endFrame();
    }

    /**
     * <b>Worst first, then document order.</b>
     *
     * <p>The panel is read top-down to decide what to fix, and an error is not one of six things to scan
     * for — it is why the panel is open. Sorted positionally, two syntax errors on line 509 sat under four
     * unused-import warnings purely because imports live at the top of the file, so the only rows that
     * stopped it compiling were the last ones you reached.</p>
     *
     * <p>Only the view is reordered: {@code Diagnostic}'s natural order stays positional, because a
     * squiggle lookup and "what is on this row" both want document order.</p>
     */
    @Test
    public void problemsAreListedWorstFirst() {
        give(shader,
                at(3, DiagnosticSeverity.WARNING, "unused import"),
                at(7, DiagnosticSeverity.INFORMATION, "a note"),
                at(40, DiagnosticSeverity.ERROR, "second error"),
                at(20, DiagnosticSeverity.ERROR, "first error"),
                at(9, DiagnosticSeverity.WARNING, "unused local"));
        panel.bindTo(markers);
        settle();
        expandEverything();

        List<Diagnostic> shown = panel.visibleProblems();
        assertEquals(List.of("first error", "second error", "unused import", "unused local", "a note"),
                shown.stream().map(Diagnostic::message).toList());
    }

    /** A press through the real route — accumulated and dispatched by the frame pair, as input is. */
    private void press(UIElement target) {
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        int cx = (int) (target.getRuntimeCache().getX() + target.getRuntimeCache().getWidth() / 2f);
        int cy = (int) (target.getRuntimeCache().getY() + target.getRuntimeCache().getHeight() / 2f);
        window.getInputHandler().beginFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(cx, cy, 0, 0, 0, true, 0f, 0L));
        window.getInputHandler().endFrame();
    }

}
