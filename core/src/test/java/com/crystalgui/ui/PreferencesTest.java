package com.crystalgui.ui;

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
        preferences.navigator().navigateTo(second);
        settle();
        assertEquals(2, preferences.navigator().pages().builtKeys().size());

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
        preferences.navigator().navigateTo("editor");
        settle();
        assertEquals(List.of("Editor"), preferences.navigator().breadcrumbs().trail());
    }

    /** Back walks the places visited. */
    @Test
    public void historyWalksTheVisitedCategories() {
        Preferences preferences = open();
        String first = preferences.navigator().pages().current();
        preferences.navigator().navigateTo("editor");
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
}
