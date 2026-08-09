package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.FilteredTreeSource;
import com.crystalgui.ui.elements.tree.TreeDataSource;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeSearch;
import com.crystalgui.ui.elements.tree.TreeView;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Installing {@link TreeSearch} is the whole of adopting it.
 *
 * <p>Marking used to be a second, unwritten step in the host's renderer — which is where VS Code and
 * IntelliJ both put it, and defensible: a renderer knows which of its elements is the label. But it makes
 * adoption a two-part contract with a silent failure. The Problems panel installed the component, got a
 * working bar, working arrows and a truthful "1 of 1", and highlighted nothing whatsoever. Nothing threw;
 * the component simply had no way to reach the rows.</p>
 *
 * <p>So this fixture is deliberately the <em>minimum</em> a consumer can write: a renderer that puts a
 * label in a row and does nothing else.</p>
 */
public class TreeSearchInstallTest extends UiTestBase {

    private static final List<String> ITEMS =
            List.of("mama.glsl", "gradle.properties", "notes.txt", "scheme.css");

    /** A two-level tree: Editor holds General, which is the row a query has to reach. */
    private static final Map<String, List<String>> NESTED = Map.of(
            "Editor", List.of("General", "Folding"),
            "Workbench", List.of("Appearance"));

    private UIWindow window;
    private TreeView<String> tree;
    private TreeSearch<String> search;

    /** Every label the view ever built, so a pooled row can be asked what it is painting now. */
    private final List<UIText> labels = new ArrayList<>();

    @Before
    public void setUp() {
        tree = new TreeView<>(new TreeDataSource<String>() {
            @Override public List<String> roots() { return ITEMS; }
            @Override public List<String> children(String parent) { return List.of(); }
            @Override public boolean hasChildren(String item) { return false; }
        });
        tree.layout(l -> l.width(240).height(200));
        tree.setItemHeight(20f);

        // THE MINIMUM A CONSUMER WRITES. No call to markRow anywhere.
        tree.setRenderer(new TreeRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                UIText label = new UIText("");
                row.addChild(label);
                labels.add(label);
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                ((UIText) template.getChildren().get(0)).setText(item);
            }
        });

        UIElement host = new UIElement().layout(l -> l.width(240).height(220));
        host.addChild(tree);
        window = new UIWindow(Ui.of(host));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(
                "text::highlight(find-match) { background-color: #C8873C; }"));
        window.init(320, 260);
        window.setUiScale(1f);
        settle();

        search = TreeSearch.installOn(tree, host, TreeSearch.byText(s -> s), item -> { });
        settle();
    }

    private void settle() {
        for (int i = 0; i < 5; i++) window.updateWithoutPainting();
    }

    /** The band on a label showing {@code text}, or -1 if no row is showing it. */
    private int bandOn(String text) {
        for (UIText label : labels) {
            if (text.equals(label.getText())) return label.highlightBandCount();
        }
        return -1;
    }

    /**
     * <b>A query marks the matched characters with no help from the host.</b>
     *
     * <p>Four characters of {@code mama.glsl}, and nothing on the three rows that do not match.</p>
     */
    @Test
    public void installingAloneMarksTheMatchedCharacters() {
        search.setQuery("mama");
        settle();

        assertEquals("the component found the match but marked nothing", 4, bandOn("mama.glsl"));
        assertEquals("a non-matching row must carry no band", 0, bandOn("gradle.properties"));
        assertEquals(0, bandOn("notes.txt"));
    }

    /**
     * <b>And a row that stops matching stops being marked.</b>
     *
     * <p>Rows are pooled, so this is the case that showed as an entire filename banded amber: the element
     * that had been {@code mama.glsl} is reused, and the band belonged to the text that left.</p>
     */
    @Test
    public void aRowThatStopsMatchingLosesItsBand() {
        search.setQuery("mama");
        settle();
        assertTrue(bandOn("mama.glsl") > 0);

        search.setQuery("scheme");
        settle();
        assertEquals("the old match kept its band after the query moved on", 0, bandOn("mama.glsl"));
        assertEquals(6, bandOn("scheme.css"));
    }

    /** Clearing the query leaves nothing marked anywhere. */
    @Test
    public void anEmptyQueryMarksNothing() {
        search.setQuery("mama");
        settle();
        search.setQuery("");
        settle();

        for (UIText label : labels) {
            assertEquals("a cleared query left a band on " + label.getText(),
                    0, label.highlightBandCount());
        }
    }

    // -- Revealing --------------------------------------------------------------------------------

    /** A fresh fixture over {@link #NESTED}, collapsed, with the mode the test wants. */
    private TreeSearch<String> nested(TreeSearch.Mode mode) {
        FilteredTreeSource<String> nestedSource = new FilteredTreeSource<>(new TreeDataSource<String>() {
            @Override public List<String> roots() { return List.of("Editor", "Workbench"); }
            @Override public List<String> children(String parent) {
                return NESTED.getOrDefault(parent, List.of());
            }
            @Override public boolean hasChildren(String item) { return NESTED.containsKey(item); }
        });
        TreeView<String> nestedTree = new TreeView<>(nestedSource);
        nestedTree.layout(l -> l.width(240).height(200));
        nestedTree.setItemHeight(20f);
        nestedTree.setRenderer(new TreeRenderer<String>() {
            @Override public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addChild(new UIText(""));
                return row;
            }
            @Override public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                ((UIText) template.getChildren().get(0)).setText(item);
            }
        });
        UIElement nestedHost = new UIElement().layout(l -> l.width(240).height(220));
        nestedHost.addChild(nestedTree);
        window = new UIWindow(Ui.of(nestedHost));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(320, 260);
        window.setUiScale(1f);
        settle();

        TreeSearch<String> nestedSearch =
                TreeSearch.installOn(nestedTree, nestedHost, TreeSearch.byText(t -> t, nestedSource), item -> { });
        nestedSearch.setMode(mode);
        settle();
        return nestedSearch;
    }

    private List<String> visible(TreeSearch<String> search) {
        return search.tree().visibleRows().stream().map(TreeRow::item).toList();
    }

    /**
     * <b>Filtering reveals, because in Filter mode the tree IS the result set.</b>
     *
     * <p>Everything left is there because it matched or because it contains something that did, so a match
     * inside a collapsed branch is not hidden — it is missing. Preferences showed exactly that for
     * {@code gene}: it kept {@code Editor}, because {@code Editor ▸ General} matched, and drew one
     * collapsed row and "no matches here". The count agreed, because a count can only see visible rows.</p>
     */
    @Test
    public void filteringOpensTheBranchesItKept() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.FILTER);
        assertEquals("the fixture should start collapsed",
                List.of("Editor", "Workbench"), visible(nested));

        nested.setQuery("General");
        settle();

        assertTrue("the match was filtered to and then left inside a closed branch",
                visible(nested).contains("General"));
        assertTrue("a match nobody can see is not a match the count can find",
                nested.matchCount() >= 1);
    }

    /**
     * <b>Highlighting does not, because the tree is untouched by construction.</b>
     *
     * <p>That is the whole distinction between the modes, and expanding would move rows under the cursor
     * on every keystroke — which is what somebody chose Highlight to avoid. IntelliJ's speed search behaves
     * this way, and it is why a folder carries a count badge instead.</p>
     */
    @Test
    public void highlightingLeavesTheTreeAsItFoundIt() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.HIGHLIGHT);

        nested.setQuery("General");
        settle();

        assertEquals("Highlight mode expanded the tree", List.of("Editor", "Workbench"), visible(nested));
    }

    /**
     * <b>And the expansion is put back.</b>
     *
     * <p>Without this one search leaves the tree sprawled open for the rest of the session, and the user
     * re-folds by hand what they never unfolded. VS Code restores expansion when its filter clears.</p>
     */
    @Test
    public void clearingTheQueryPutsTheExpansionBack() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.FILTER);
        nested.tree().setExpanded("Workbench", true);
        settle();
        List<String> before = visible(nested);

        nested.setQuery("General");
        settle();
        assertTrue(visible(nested).contains("General"));

        nested.setQuery("");
        settle();
        assertEquals("the tree stayed open after the query was cleared", before, visible(nested));
    }

    /** Leaving Filter mode restores it too — the same exit, reached the other way. */
    @Test
    public void leavingFilterModePutsTheExpansionBack() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.FILTER);
        List<String> before = visible(nested);

        nested.setQuery("General");
        settle();
        nested.setMode(TreeSearch.Mode.HIGHLIGHT);
        settle();

        assertEquals(before, visible(nested));
    }



    /** The row element currently showing {@code text}, or null. */
    private UIElement rowShowing(TreeSearch<String> search, String text) {
        for (var entry : search.tree().realisedRows().entrySet()) {
            TreeRow<String> row = search.tree().rowAt(entry.getKey());
            if (row != null && text.equals(row.item())) return entry.getValue();
        }
        return null;
    }

    /**
     * <b>Filtering never dims — the mirror of the reveal rule.</b>
     *
     * <p>Filtering <em>removes</em> what does not belong; saying it again in grey about what is left is a
     * second, weaker statement that mostly reads as "disabled". It is not even reliably true:
     * {@link FilteredTreeSource} keeps a matching node's whole subtree unfiltered, so a filtered
     * Preferences tree greyed out {@code Code Style} purely for sitting under a category that matched.
     * That reads as the font colour changing at random rather than as an answer.</p>
     */
    @Test
    public void filteringDimsNothing() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.FILTER);
        nested.setQuery("Editor");
        settle();

        // Editor matched, so its whole subtree comes along -- Folding matches nothing itself.
        UIElement folding = rowShowing(nested, "Folding");
        assertNotNull("the matching node did not bring its subtree, so this asserts nothing", folding);
        assertFalse("a filtered tree dimmed a row it had chosen to keep",
                folding.hasClass(TreeSearch.DIMMED_CLASS));
    }

    /** Highlighting still does, because there the tree is complete and dimming is the only signal. */
    @Test
    public void highlightingStillDimsWhatDoesNotMatch() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.HIGHLIGHT);
        nested.tree().setExpanded("Editor", true);
        settle();
        nested.setQuery("General");
        settle();

        UIElement folding = rowShowing(nested, "Folding");
        assertNotNull(folding);
        assertTrue("Highlight mode stopped dimming non-matches",
                folding.hasClass(TreeSearch.DIMMED_CLASS));
    }


    /**
     * <b>Filtering does not brighten either — the same rule as the dimming, from the other side.</b>
     *
     * <p>Highlight mode paints a three-state answer over a complete tree: match white, ordinary grey,
     * irrelevant dimmed. Filter mode has already said it by narrowing, so colouring what survived is
     * redundant — and it did not read as redundancy. The Preferences sidebar was {@code #CCCCCC} with no
     * query and white with one, so typing appeared to restyle the whole panel; the navigator's matcher
     * answers "does anything at or under this path match", which makes nearly every surviving row a
     * match.</p>
     */
    @Test
    public void filteringDoesNotBrightenWhatItKept() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.FILTER);
        nested.setQuery("General");
        settle();

        UIElement general = rowShowing(nested, "General");
        assertNotNull("the match is not on screen, so this asserts nothing", general);
        assertFalse("a filtered tree recoloured a row it had already chosen to keep",
                general.hasClass(TreeSearch.MATCH_CLASS));
        assertTrue("the band is what says WHERE, and it must survive both modes",
                bandOnIn(nested, "General") > 0);
    }

    /** Highlight mode still marks the match white, because there colour is the only signal. */
    @Test
    public void highlightingStillBrightensTheMatch() {
        TreeSearch<String> nested = nested(TreeSearch.Mode.HIGHLIGHT);
        nested.tree().setExpanded("Editor", true);
        settle();
        nested.setQuery("General");
        settle();

        UIElement general = rowShowing(nested, "General");
        assertNotNull(general);
        assertTrue("Highlight mode stopped marking the match",
                general.hasClass(TreeSearch.MATCH_CLASS));
    }

    /** The band on the label of the row showing {@code text}, or -1. */
    private int bandOnIn(TreeSearch<String> search, String text) {
        UIElement row = rowShowing(search, text);
        if (row == null) return -1;
        for (UIElement child : row.getChildren()) {
            if (child instanceof UIText label) return label.highlightBandCount();
        }
        return -1;
    }

}
