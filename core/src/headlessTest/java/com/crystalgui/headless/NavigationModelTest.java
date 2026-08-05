package com.crystalgui.headless;

import com.crystalgui.core.nav.NavigationHistory;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.ui.elements.tree.FilteredTreeSource;
import com.crystalgui.ui.elements.tree.PathTreeSource;
import com.crystalgui.ui.elements.tree.TreeDataSource;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The navigation model behind a master/detail window — history, the path tree and filtering.
 *
 * <p>Headless on purpose: all three are statements about a <b>model</b>, and a model that needed a window
 * to be testable would not be one. The shell that assembles them is a separate concern.</p>
 */
public class NavigationModelTest {

    private static final List<String> IDS = List.of(
            "editor.general.autoImport",
            "editor.general.tabSize",
            "editor.appearance.fontSize",
            "editor.appearance.a11y.zoom",
            "explorer.confirmDelete",
            "workbench.restoreSession");

    private PathTreeSource tree;

    @Before
    public void setUp() {
        SettingsCategory.clear();
        SettingsCategory.page("editor", "Editor");
        SettingsCategory.page("editor.general", "General");
        SettingsCategory.page("editor.appearance", "Appearance");
        SettingsCategory.section("editor.appearance.a11y", "Accessibility");
        SettingsCategory.page("explorer", "Explorer");
        SettingsCategory.page("workbench", "Workbench");
        tree = new PathTreeSource(IDS, SettingsCategory::isPage, SettingsCategory::titleOf);
    }

    // ── NavigationHistory ───────────────────────────────────────────────────────────────────────

    @Test
    public void historyStepsBackAndForward() {
        NavigationHistory<String> history = new NavigationHistory<>();
        assertFalse(history.canGoBack());
        assertNull(history.back());

        history.visit("a");
        history.visit("b");
        history.visit("c");
        assertEquals("c", history.current());

        assertEquals("b", history.back());
        assertEquals("a", history.back());
        assertFalse("walked off the start", history.canGoBack());
        assertEquals("b", history.forward());
        assertEquals("c", history.forward());
        assertFalse(history.canGoForward());
    }

    /**
     * <b>Visiting somewhere new discards the forward tail.</b>
     *
     * <p>A browser's rule. Keeping it would make Forward mean "somewhere I was going to go", which is a
     * stranger promise than it sounds and has no way to be presented.</p>
     */
    @Test
    public void visitingAfterGoingBackDiscardsTheFuture() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.visit("a");
        history.visit("b");
        history.visit("c");
        history.back();

        history.visit("d");
        assertEquals(List.of("a", "b", "d"), history.entries());
        assertFalse("c should be gone", history.canGoForward());
    }

    /**
     * <b>Re-visiting the current place is a no-op.</b>
     *
     * <p>A tree that re-selects its current node on every refresh would otherwise fill the history with
     * duplicates, and Back would take a press per refresh while appearing to do nothing.</p>
     */
    @Test
    public void consecutiveVisitsToTheSamePlaceCollapse() {
        NavigationHistory<String> history = new NavigationHistory<>();
        history.visit("a");
        assertFalse(history.visit("a"));
        assertFalse(history.visit("a"));
        assertEquals(1, history.size());
        assertFalse(history.canGoBack());
    }

    /** Bounded, so a long session cannot grow it without limit. */
    @Test
    public void historyIsBounded() {
        NavigationHistory<String> history = new NavigationHistory<>(3);
        history.visit("a");
        history.visit("b");
        history.visit("c");
        history.visit("d");
        assertEquals(List.of("b", "c", "d"), history.entries());
        assertEquals("d", history.current());
    }

    // ── PathTreeSource ──────────────────────────────────────────────────────────────────────────

    @Test
    public void theTreeIsBuiltFromDeclaredPagesOnly() {
        assertEquals(List.of("editor", "explorer", "workbench"), tree.roots());
        assertEquals(List.of("editor.general", "editor.appearance"), tree.children("editor"));
        assertTrue(tree.hasChildren("editor"));
        assertFalse("explorer declares no sub-pages", tree.hasChildren("explorer"));
    }

    /**
     * <b>A section is not a tree node.</b>
     *
     * <p>{@code editor.appearance.a11y} is declared SECTION, so Appearance has no children and the zoom
     * setting stays on the Appearance page — under an "Accessibility" heading, which is the whole
     * distinction.</p>
     */
    @Test
    public void aSectionDoesNotBecomeANode() {
        assertFalse("a section must not appear in the tree", tree.hasChildren("editor.appearance"));
        assertEquals("editor.appearance", tree.nodeOf("editor.appearance.a11y.zoom"));
        assertEquals("a11y", tree.sectionOf("editor.appearance.a11y.zoom"));
        assertEquals("a setting on the page itself is in no section",
                "", tree.sectionOf("editor.appearance.fontSize"));
    }

    /**
     * <b>An undeclared level is a section, not a node.</b>
     *
     * <p>So a setting can never be unreachable for want of a declaration — it lands on its nearest
     * declared page. Declaring the level buys a title and the choice of kind, not the ability to exist.
     * The converse is the property that matters: <b>adding a setting cannot change the tree's shape.</b></p>
     */
    @Test
    public void anUndeclaredLevelDoesNotGrowTheTree() {
        PathTreeSource grown = new PathTreeSource(
                List.of("editor.general.imports.addUnambiguous"),
                SettingsCategory::isPage, SettingsCategory::titleOf);
        assertFalse("an undeclared level grew a node in the navigation",
                grown.hasChildren("editor.general"));
        assertEquals("editor.general", grown.nodeOf("editor.general.imports.addUnambiguous"));
        assertEquals("imports", grown.sectionOf("editor.general.imports.addUnambiguous"));
    }

    @Test
    public void aPageKnowsWhichIdsBelongToIt() {
        assertEquals(List.of("editor.general.autoImport", "editor.general.tabSize"),
                tree.idsDirectlyUnder("editor.general"));
        assertEquals("a section's settings belong to its page, not to a node of their own",
                List.of("editor.appearance.fontSize", "editor.appearance.a11y.zoom"),
                tree.idsDirectlyUnder("editor.appearance"));
    }

    @Test
    public void titlesComeFromTheDeclarationAndFallBackToTheSegment() {
        assertEquals("General", tree.title("editor.general"));
        // "Ui options", not "UI options": the fallback splits camelCase and has no idea `ui` is an
        // acronym. Acronym detection is a guess that is wrong as often as it is right -- the answer for a
        // heading somebody will read is to DECLARE a title, which is what SettingsCategory is for. This
        // exists so an undeclared level is legible, not so it is perfect.
        assertEquals("an undeclared level must still read as a heading",
                "Ui options", PathTreeSource.prettify("uiOptions"));
        assertEquals("Imports", PathTreeSource.prettify("imports"));
    }

    // ── FilteredTreeSource ──────────────────────────────────────────────────────────────────────

    /**
     * <b>A match keeps its ancestors, or it cannot be reached.</b>
     *
     * <p>The whole difficulty of filtering a tree. Filtering each level independently hides the path to
     * every deep hit, and the search then appears to find nothing while quietly matching plenty.</p>
     */
    @Test
    public void aDeepMatchKeepsThePathToItself() {
        FilteredTreeSource<String> filtered = new FilteredTreeSource<>(tree)
                .setFilter(path -> path.equals("editor.appearance"));

        assertEquals("the ancestor of the only match was dropped", List.of("editor"), filtered.roots());
        assertEquals(List.of("editor.appearance"), filtered.children("editor"));
        assertTrue(filtered.hasChildren("editor"));
    }

    /** A matching node keeps everything under it, so searching a category shows its contents. */
    @Test
    public void aMatchingNodeKeepsItsSubtree() {
        FilteredTreeSource<String> filtered = new FilteredTreeSource<>(tree)
                .setFilter(path -> path.equals("editor"));
        assertEquals(List.of("editor"), filtered.roots());
        assertEquals("a match must not have its own children filtered away",
                List.of("editor.general", "editor.appearance"), filtered.children("editor"));
    }

    /** Nothing matching means nothing shown — not everything shown. */
    @Test
    public void nothingMatchingLeavesAnEmptyTree() {
        FilteredTreeSource<String> filtered = new FilteredTreeSource<>(tree)
                .setFilter(path -> false);
        assertTrue(filtered.roots().isEmpty());
    }

    /** No filter delegates entirely, so an unfiltered tree pays nothing for the decorator. */
    @Test
    public void noFilterIsNotAnEmptyFilter() {
        FilteredTreeSource<String> filtered = new FilteredTreeSource<>(tree);
        assertFalse(filtered.isFiltering());
        assertEquals(tree.roots(), filtered.roots());
        assertEquals(tree.children("editor"), filtered.children("editor"));
        assertTrue(filtered.hasChildren("editor"));
    }

    /** A source that loops is a bug in its author; the walk must not hang the frame over it. */
    @Test
    public void aCyclicSourceIsBoundedRatherThanFatal() {
        TreeDataSource<String> looping = new TreeDataSource<>() {
            @Override public List<String> roots() { return List.of("a"); }
            @Override public List<String> children(String parent) { return List.of("a"); }
            @Override public boolean hasChildren(String item) { return true; }
        };
        FilteredTreeSource<String> filtered = new FilteredTreeSource<>(looping)
                .setFilter(path -> false);
        assertTrue(filtered.roots().isEmpty());
    }
}
