package com.crystalgui.ui;

import com.crystalgui.core.collection.tree.FilteredTreeSource;
import org.joml.Vector2f;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.elements.tree.TreeSearch;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.ui.elements.chrome.NavigatorView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.fs.InMemoryConfigStorage;
import com.crystalgui.graph.shader.ShaderGraphSettings;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.chrome.Preferences;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Preferences} — the settings window and the preferences file behind it.
 */
public class PreferencesTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void setUp() {
        WorkbenchSettings.declare();
        ShaderGraphSettings.register();
        // A root that REFUSES public children, which is what every composite is -- CrystalEditor
        // included. addOverlay then falls back to the window's own zero-sized overlay layer, and an
        // unpromoted dialog parented there clamps every position write to zero. A permissive root hides
        // that entirely, so the window centres and drags for a reason production does not have.
        UIElement root = new UIElement() {
            @Override public boolean acceptsPublicChildren() { return false; }
        };
        root.layout(l -> l.widthPercent(100f).heightPercent(100f));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        // Big enough that a 720x480 window FITS. At 1200x800 physical the logical viewport is 600x400,
        // the dialog is wider than it, and every centring assertion is really testing the clamp.
        window.init(2000, 1400);
    }

    /**
     * A frame, INCLUDING the input half.
     *
     * <p>{@code updateWithoutPainting} alone is not a frame as far as the input handler is concerned:
     * {@code firstFrameOver} is set in {@code endFrame()}, and until it is, every keyboard event is
     * dropped. A fixture that skips it proves nothing about any key.</p>
     */
    private void settle() {
        for (int i = 0; i < 4; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    private Preferences open() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        return preferences;
    }

    // ── Structure ───────────────────────────────────────────────────────────────────────────────

    /** The tree is the declared categories, and the window lands on one of them. */
    @Test
    public void itOpensOnACategoryWithATreeBesideIt() {
        Preferences preferences = open();

        List<String> roots = preferences.paths().roots();
        assertTrue("no categories were declared, so nothing below proves anything", roots.size() >= 3);
        assertTrue(roots.contains("explorer"));
        assertEquals("the window must land somewhere rather than on an empty pane",
                roots.get(0), preferences.navigator().pages().current());
        assertNotNull("the landing category built no page", preferences.page());
    }

    /**
     * <b>Pages are built lazily and then kept.</b>
     *
     * <p>Lazily so a hundred categories do not cost a hundred panels to open; kept so coming back to a
     * page finds it as you left it rather than scrolled to the top with its groups reopened.</p>
     */
    @Test
    public void pagesAreBuiltOnFirstVisitAndKept() {
        Preferences preferences = open();
        String first = preferences.paths().roots().get(0);
        String second = preferences.paths().roots().get(1);

        assertEquals("only the landing page should exist yet",
                List.of(first), preferences.navigator().pages().builtKeys());

        UIElement firstPage = preferences.page();
        assertNotNull("the landing category built no page, so reuse cannot be observed", firstPage);

        preferences.navigator().navigateTo(second);
        settle();
        // NOT a count of built pages: a parent category with no settings of its own legitimately builds
        // none, so counting would be asserting which categories happen to be leaves.
        preferences.navigator().navigateTo(first);
        settle();
        assertTrue("the page was rebuilt instead of reused, so its scroll and groups were lost",
                firstPage == preferences.page());
    }

    /** Every row a page built is really in the tree, not parented to a detached group. */
    @Test
    public void everyRowIsReallyInTheWindow() {
        Preferences preferences = open();
        for (String root : preferences.paths().roots()) {
            preferences.navigator().navigateTo(root);
            settle();
        }
        List<UIElement> rows = preferences.dialog().querySelectorAll(".__configurator__");
        assertEquals("a row was built but never attached, so its page renders empty",
                preferences.shownSettings().size(), rows.size());
        assertTrue("nothing was built at all", rows.size() >= 5);
    }

    /**
     * <b>A setting that is not writable at the user layer never appears.</b>
     *
     * <p>{@code ShaderGraphSettings} declares its render queue {@code writableAt(DOCUMENT, MEMORY)}
     * exactly so it cannot become a global preference — the failure {@code Setting.writableAt}'s own
     * documentation describes.</p>
     */
    @Test
    public void aDocumentOnlySettingIsNotOfferedAsAPreference() {
        Preferences preferences = open();
        for (String root : preferences.paths().roots()) {
            preferences.navigator().navigateTo(root);
            settle();
        }
        for (Setting<?> shown : preferences.shownSettings()) {
            assertTrue("'" + shown.getId() + "' is not writable at the user layer",
                    shown.isWritableAt(SettingsLayer.USER));
        }
        assertFalse("a document-scoped setting was offered as a global preference",
                preferences.shownSettings().contains(ShaderGraphSettings.QUEUE));
    }

    /** The breadcrumb says where you are. */
    @Test
    public void theBreadcrumbFollowsTheSelection() {
        Preferences preferences = open();
        String root = preferences.paths().roots().get(0);
        preferences.navigator().navigateTo(root);
        settle();
        assertEquals(List.of(preferences.paths().title(root)),
                preferences.navigator().breadcrumbs().trail());

        // And a nested page shows its ancestors, which is the half a single-level trail cannot prove.
        List<String> children = preferences.paths().children(root);
        if (!children.isEmpty()) {
            preferences.navigator().navigateTo(children.get(0));
            settle();
            assertEquals(List.of(preferences.paths().title(root),
                            preferences.paths().title(children.get(0))),
                    preferences.navigator().breadcrumbs().trail());
        }
    }

    /** Back walks the places visited. */
    @Test
    public void historyWalksTheVisitedCategories() {
        Preferences preferences = open();
        String first = preferences.navigator().pages().current();
        // Somewhere OTHER than where it landed, whatever that happens to be -- naming a category here
        // makes the test depend on declaration order, which is not what it is about.
        String second = preferences.paths().roots().stream()
                .filter(root -> !root.equals(first)).findFirst().orElseThrow();
        preferences.navigator().navigateTo(second);
        settle();

        assertTrue(preferences.navigator().history().canGoBack());
        assertEquals(first, preferences.navigator().history().back());
    }

    // ── Search ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Search matches settings, not only category titles.</b>
     *
     * <p>A filter that only matched the tree's own labels would be decorative: typing a setting's name is
     * the one thing somebody opening a search box is trying to do.</p>
     */
    @Test
    public void searchMatchesSettingsAndKeepsTheirCategory() {
        Preferences preferences = open();
        preferences.navigator().search().setText("tab size");
        settle();

        List<String> visible = new java.util.ArrayList<>();
        for (var row : preferences.navigator().tree().visibleRows()) visible.add(row.item());
        assertTrue("the category holding the match was filtered away: " + visible,
                visible.contains("editor"));
        assertFalse("an unrelated category survived a query it does not match: " + visible,
                visible.contains("workbench"));
    }

    /** An empty query shows everything again. */
    @Test
    public void clearingTheSearchRestoresTheTree() {
        Preferences preferences = open();
        preferences.navigator().search().setText("tab size");
        settle();
        preferences.navigator().search().setText("");
        settle();
        assertEquals(preferences.paths().roots().size(),
                preferences.navigator().tree().visibleRows().size());
    }

    // ── The window itself ───────────────────────────────────────────────────────────────────────

    @Test
    public void itOpensCentredAndResizable() {
        Preferences preferences = open();
        float width = preferences.dialog().getRuntimeCache().getWidth();
        float left = preferences.dialog().getRuntimeCache().getX();
        assertTrue("opened at x=" + left,
                Math.abs(left - (window.getScreenWidth() - width) / 2f) < 2f);
        assertEquals(com.crystalgui.style.property.visual.Resize.BOTH,
                preferences.dialog().getStyle().getGeneralGroup().resize());
        assertTrue("an unpromoted dialog is clamped into a zero-sized overlay layer",
                preferences.dialog().isInTopLayer());
    }

    private void escape() {
        window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                (char) 0, CgKeyCodes.KEY_ESCAPE, true, false, 20L));
        settle();
    }

    @Test
    public void escapeClosesItWhileFocused() {
        Preferences preferences = open();
        window.getInputHandler().requestFocus(preferences.dialog());
        settle();
        escape();
        assertFalse("Escape did not close the window", preferences.dialog().isOpen());
    }

    /**
     * <b>Escape elsewhere leaves it open.</b>
     *
     * <p>Why it is a bubbling listener and not a close watcher: a close watcher is window-wide and wins
     * wherever focus is, so a modeless dialog would eat Escape from the editor behind it.</p>
     */
    @Test
    public void escapeElsewhereLeavesItOpen() {
        Preferences preferences = open();
        UIElement outside = new UIElement();
        outside.setFocusPolicy(com.crystalgui.ui.input.FocusPolicy.FOCUSABLE);
        window.addOverlay(outside, null);
        settle();
        window.getInputHandler().requestFocus(outside);
        settle();
        escape();
        assertTrue("a modeless window must not eat Escape from elsewhere",
                preferences.dialog().isOpen());
    }

    /**
     * <b>Closing keeps the box laid out, so the fade-out has something to fade.</b>
     *
     * <p>{@code display} is discrete: without a transition naming it, the box snaps to {@code none} on
     * the closing frame and takes the opacity transition with it. Asserting the frame after the close
     * rather than an intermediate opacity, because {@code TransitionEngine} runs on
     * {@code System.nanoTime()} and ignores the delta a test hands it.</p>
     */
    @Test
    public void closingKeepsTheBoxLaidOutWhileItFades() {
        Preferences preferences = open();
        preferences.dialog().close();
        window.updateWithoutPainting();

        assertFalse("only the pixels should linger", preferences.dialog().isOpen());
        assertEquals("display snapped to none, so nothing can be seen fading out",
                dev.vfyjxf.taffy.style.TaffyDisplay.FLEX,
                preferences.dialog().getStyle().getComputed(
                        com.crystalgui.style.property.layout.LayoutProperties.DISPLAY));
    }

    @Test
    public void theOpenStateIsVisibleToTheStylesheet() {
        Preferences preferences = open();
        assertTrue("a dialog with no open class cannot be animated at all",
                preferences.dialog().hasClass(Dialog.OPEN_CLASS));
        preferences.dialog().close();
        settle();
        assertFalse(preferences.dialog().hasClass(Dialog.OPEN_CLASS));
    }

    // ── The file behind it ──────────────────────────────────────────────────────────────────────

    @Test
    public void preferencesSurviveAReload() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        com.crystalgui.core.settings.Settings settings = window.ui.rootElement.settings();
        settings.set(SettingsLayer.USER, WorkbenchSettings.TAB_SIZE, 8);
        settings.set(SettingsLayer.USER, WorkbenchSettings.SORT_ORDER, "FILES_FIRST");
        storage.write("settings.json", SettingsCodec.toJson(settings.layer(SettingsLayer.USER)));

        com.crystalgui.core.settings.Settings reloaded = new com.crystalgui.core.settings.Settings();
        reloaded.replaceLayer(SettingsLayer.USER,
                SettingsCodec.fromJson(storage.read("settings.json")).asMap());

        assertEquals(Integer.valueOf(8), reloaded.get(WorkbenchSettings.TAB_SIZE));
        assertEquals("FILES_FIRST", reloaded.get(WorkbenchSettings.SORT_ORDER));
    }

    /** A malformed file degrades to the defaults rather than refusing to start. */
    @Test
    public void aMalformedPreferencesFileDoesNotStopAnythingStarting() {
        SettingsModel model = SettingsCodec.fromJson("{ this is not json");
        assertTrue("an unreadable file must yield nothing, not throw", model.isEmpty());
        com.crystalgui.core.settings.Settings settings = new com.crystalgui.core.settings.Settings();
        settings.replaceLayer(SettingsLayer.USER, model.asMap());
        assertEquals(Integer.valueOf(4), settings.get(WorkbenchSettings.TAB_SIZE));
    }

    @Test
    public void aReadOnlyStoreIsKnownToBeReadOnly() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage().setWritable(false);
        assertFalse(storage.isWritable());
        try {
            storage.write("settings.json", "{}");
            org.junit.Assert.fail("expected a read-only store to refuse");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("read-only"));
        }
    }

    /** Every declaration the workbench makes is registered, or the window cannot show it. */
    @Test
    public void everyWorkbenchSettingIsDeclared() {
        SettingsRegistry registry = SettingsRegistry.get();
        for (Setting<?> setting : new Setting<?>[]{
                WorkbenchSettings.AUTO_REVEAL, WorkbenchSettings.CONFIRM_DELETE,
                WorkbenchSettings.SORT_ORDER, WorkbenchSettings.FOLDING,
                WorkbenchSettings.SCROLL_BEYOND_LAST_LINE, WorkbenchSettings.TAB_SIZE,
                WorkbenchSettings.CARET_BLINK, WorkbenchSettings.RESTORE_SESSION,
                WorkbenchSettings.RESTORE_VIEW_STATE}) {
            assertEquals(setting, registry.get(setting.getId()));
        }
    }

    /** Every category the workbench navigates to is declared, or its settings have nowhere to live. */
    @Test
    public void everyWorkbenchCategoryIsDeclared() {
        WorkbenchSettings.declare();
        for (String path : new String[]{"explorer", "editor", "workbench"}) {
            assertTrue("'" + path + "' is not a declared page",
                    SettingsCategory.isPage(path));
        }
    }

    // -- The search, since it became TreeSearch's --------------------------------------------------

    /** The component, which the sidebar's search box now belongs to. */
    private TreeSearch<String> search(Preferences preferences) {
        TreeSearch<String> search = preferences.navigator().treeSearch();
        assertNotNull("the navigator built no search — setSource installs it", search);
        return search;
    }

    /**
     * <b>Typing still narrows the tree.</b>
     *
     * <p>The whole point of the migration is that this did not change. What used to be a {@code TextField},
     * a {@code FilteredTreeSource}, a query field and an {@code applyFilter} in {@link NavigatorView} is
     * now one {@code installOn} call, and the matcher — the one part that is genuinely the host's, since
     * it reads setting labels and descriptions this widget has never heard of — stayed.</p>
     */
    @Test
    public void searchStillFiltersTheSidebar() {
        Preferences preferences = open();
        int all = preferences.navigator().tree().visibleRows().size();
        assertTrue("nothing in the tree, so this asserts nothing", all > 0);

        search(preferences).setQuery("zzzz-matches-nothing");
        settle();
        assertEquals("a query matching nothing must leave no rows",
                0, preferences.navigator().tree().visibleRows().size());

        search(preferences).setQuery("");
        settle();
        assertEquals("clearing the query must put every row back",
                all, preferences.navigator().tree().visibleRows().size());
    }

    /**
     * <b>It filters rather than highlights, and the mode is the host's choice.</b>
     *
     * <p>IntelliJ's settings search and VS Code's settings editor both narrow the tree; a highlight-only
     * settings search would leave you scrolling a full tree looking for a mark.</p>
     */
    @Test
    public void theSettingsSearchFiltersByDefault() {
        Preferences preferences = open();
        assertEquals(TreeSearch.Mode.FILTER, search(preferences).mode());
    }

    /**
     * <b>The bar is permanent — nothing dismisses it.</b>
     *
     * <p>A transient bar is right for a tree in a panel and wrong here: the box is the first thing in the
     * sidebar and it is how you are expected to start, so a stray Escape leaving the panel with no visible
     * search and no hint that Ctrl+F brings it back is a dead end rather than a dismissal.</p>
     */
    @Test
    public void theSettingsSearchCannotBeDismissed() {
        Preferences preferences = open();
        TreeSearch<String> search = search(preferences);
        assertTrue(search.isOpen());

        search.setQuery("tab");
        settle();
        search.close();
        settle();

        assertTrue("the permanent bar was dismissed", search.isOpen());
        assertEquals("close on a permanent bar clears rather than hides", "", search.query());
    }

    /**
     * <b>The arrows still walk the tree, not the matches.</b>
     *
     * <p>{@link NavigatorView}'s arrows open the page for whatever they land on, with or without a query,
     * so the component's match-stepping is turned off there. It would both fight the navigation and go
     * dead the moment the box was empty — and filtering already narrows the rows, which makes "arrow
     * through what is left" the same gesture with a better answer.</p>
     */
    @Test
    public void arrowsStillWalkTheTreeWithNoQuery() {
        Preferences preferences = open();
        List<String> rows = preferences.navigator().tree().visibleRows().stream()
                .map(TreeRow::item).toList();
        assertTrue("fewer than two rows, so stepping proves nothing", rows.size() >= 2);
        preferences.navigator().navigateTo(rows.get(0));
        settle();

        TextField box = preferences.navigator().search();
        assertNotNull(box);
        window.getInputHandler().requestFocus(box);
        settle();
        window.getInputHandler().sendInputEvent(box,
                new KeyboardEvent.Down(box, CgKeyCodes.KEY_DOWN, '\0', false, 0, 0L));
        settle();

        assertEquals("Down in the search box did not step the tree",
                rows.get(1), preferences.navigator().pages().current());
    }


    /**
     * <b>A query shows the branches that matched, not every sibling of one.</b>
     *
     * <p><b>Not mutation-caught, and worth saying so.</b> Distinguishing the two implementations needs a
     * query matching a setting that lives <em>only</em> on a child page; every setting in this fixture
     * that is easy to name is declared on the parent, where {@code Editor} matches itself and correctly
     * keeps its subtree. What this pins is the outcome — the visual check on the real settings tree is
     * what caught the regression.</p>
     *
     * <p>Two rules stacked into a wrong answer. {@code matches} walked every setting <em>at or under</em> a
     * path, so {@code Editor} reported a match because {@code Editor ▸ General} held one — and
     * {@link com.crystalgui.core.collection.tree.FilteredTreeSource} has two branches, where a node whose own
     * predicate is true "keeps its whole subtree, unfiltered". So {@code ge} listed Appearance and Code
     * Style beside General, purely for being Editor's children.</p>
     *
     * <p>The matcher answers for the node itself now; keeping the path to a deep match reachable was always
     * the source's job, and its own descendant walk still does it. VS Code's tree filter draws the same
     * line — Recurse for a node that matched, Visible for one carrying a match.</p>
     */
    @Test
    public void filteringKeepsOnlyTheBranchesThatMatched() {
        Preferences preferences = open();
        search(preferences).setQuery("General");
        settle();

        List<String> rows = preferences.navigator().tree().visibleRows().stream()
                .map(TreeRow::item).toList();
        assertTrue("the page holding the match must survive, got " + rows,
                rows.contains("editor.general"));
        assertTrue("and the path to it, got " + rows, rows.contains("editor"));
        assertFalse("a sibling of the match was kept for sharing its parent, got " + rows,
                rows.contains("editor.appearance") || rows.contains("editor.codeStyle"));
    }


    /**
     * <b>Folding a long branch gives the width back.</b>
     *
     * <p>The sidebar's minimum grows to fit the widest realised row, and it used to grow only. The reason
     * was sound — the measure reads <em>realised</em> rows, so letting it fall every frame would make the
     * pane breathe as you scrolled — but it is a <em>minimum</em>, so one long label seen once pinned the
     * floor for the rest of the session: unfold a deep name and the split could never be dragged narrow
     * again, even after folding it away.</p>
     *
     * <p>Only the shrink is gated now, on {@code onExpandChanged} — the one event that changes what the
     * widest row could be. Growth still lands the frame a long row appears; scrolling still cannot move
     * it.</p>
     *
     * <p>Built on {@link NavigatorView} directly rather than through {@link Preferences}, because the
     * settings fixture's child titles are all shorter than its root titles, so unfolding it never widens
     * anything and there would be nothing to give back.</p>
     */
    @Test
    public void foldingABranchLetsTheSidebarShrinkAgain() {
        NavigatorView<String> nav = longTitledNavigator();
        UIWindow w = nav.getAttachedWindow();
        float collapsed = nav.sidebarMinimumWidth();

        nav.tree().setExpanded("Short", true);
        for (int i = 0; i < 6; i++) w.updateWithoutPainting();
        float expanded = nav.sidebarMinimumWidth();
        assertTrue("unfolding did not widen the sidebar (" + collapsed + " -> " + expanded
                + "), so this asserts nothing", expanded > collapsed);

        nav.tree().setExpanded("Short", false);
        for (int i = 0; i < 6; i++) w.updateWithoutPainting();
        // THE RATCHET RELEASED, which is the whole subject -- not that the width returns to the exact
        // pixel it started at. It comes back slightly UNDER the baseline, because the minimum is derived
        // from the widest REALISED row and which rows are realised differs either side of an expansion.
        // Pinning the starting value made this a test about one font's metrics: it passed on the old
        // proportional face and failed by two pixels on the new one, reporting a stuck sidebar that was
        // not stuck at all.
        assertTrue("the minimum stayed at the widest row ever seen: " + nav.sidebarMinimumWidth(),
                nav.sidebarMinimumWidth() <= collapsed + 0.5f);
    }


    /** One short root hiding a child far longer than it — something a fold can give width back from. */
    private NavigatorView<String> longTitledNavigator() {
        NavigatorView<String> nav = new NavigatorView<>();
        nav.layout(l -> l.width(400).height(300));
        nav.setSource(new TreeDataSource<String>() {
            @Override public List<String> roots() { return List.of("Short"); }
            @Override public List<String> children(String parent) {
                return "Short".equals(parent)
                        ? List.of("A name far longer than any root here") : List.of();
            }
            @Override public boolean hasChildren(String item) { return "Short".equals(item); }
        });
        nav.setTitleFunction(item -> item);

        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.addChild(nav);
        UIWindow w = new UIWindow(Ui.of(host));
        w.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        w.init(800, 600);
        w.setUiScale(1f);
        for (int i = 0; i < 6; i++) w.updateWithoutPainting();
        return nav;
    }

    /**
     * <b>A press on the divider that moves nothing must not take ownership of the width.</b>
     *
     * <p>The flag was set from the divider's mouse-DOWN, so landing on the handle — or a drag that ended
     * where it began — permanently switched off the auto-sizing. It fails silently and does not look like
     * a click: unfolding a long page name simply stops widening the sidebar for the rest of the session,
     * leaving the label clipped at the pane edge.</p>
     */
    @Test
    public void pressingTheDividerWithoutMovingItKeepsAutoSizing() {
        NavigatorView<String> nav = longTitledNavigator();
        UIWindow w = nav.getAttachedWindow();
        UIElement divider = nav.split().divider();
        w.getInputHandler().sendInputEvent(divider,
                new MouseEvent.Down(divider, new ReadOnlyVec2f(new Vector2f(0f, 0f)), 0, 1));
        for (int i = 0; i < 4; i++) w.updateWithoutPainting();
        // The SPLIT's share, not the minimum -- the minimum grows either way; what ownership gates is
        // whether the pane actually follows it.
        float before = nav.split().getPercentage();

        nav.tree().setExpanded("Short", true);
        for (int i = 0; i < 8; i++) w.updateWithoutPainting();

        assertTrue("a press that moved nothing stopped the sidebar following its content ("
                        + before + " -> " + nav.split().getPercentage() + ")",
                nav.split().getPercentage() > before);
    }


    /**
     * <b>Clearing a query gives the sidebar's width back.</b>
     *
     * <p>The same one-way ratchet {@link #foldingABranchLetsTheSidebarShrinkAgain} fixed, reached the other
     * way. The minimum may only fall when the content changed, and that was wired to {@code onExpandChanged}
     * alone — but a filter replaces the row set outright, and the bulk {@code setExpandedItems} that reveal
     * and restore go through does not emit that signal at all. So a query that revealed a long page name
     * widened the sidebar permanently, and clearing it left the split stuck.</p>
     */
    @Test
    public void clearingAQueryLetsTheSidebarShrinkAgain() {
        NavigatorView<String> nav = longTitledNavigator();
        UIWindow w = nav.getAttachedWindow();
        float idle = nav.sidebarMinimumWidth();

        TreeSearch<String> search = nav.treeSearch();
        assertNotNull(search);
        search.setMode(TreeSearch.Mode.FILTER);
        // Matches the long child, so filtering reveals it and the sidebar has to widen for it.
        search.setQuery("far longer");
        for (int i = 0; i < 8; i++) w.updateWithoutPainting();
        assertTrue("the query did not widen the sidebar, so this asserts nothing",
                nav.sidebarMinimumWidth() > idle);

        search.setQuery("");
        for (int i = 0; i < 8; i++) w.updateWithoutPainting();
        // Came back down, which is the subject. See the note in foldingABranchLetsTheSidebarShrinkAgain
        // for why this is not an equality against the starting pixel.
        assertTrue("clearing the query left the sidebar stuck at the long row's width: "
                + nav.sidebarMinimumWidth(), nav.sidebarMinimumWidth() <= idle + 0.5f);
    }

}
