package com.crystalgui.ui;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.fs.InMemoryConfigStorage;
import com.crystalgui.graph.shader.ShaderGraphSettings;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.chrome.Preferences;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link Preferences} — the generated settings window, and the preferences file behind it.
 */
public class PreferencesTest extends UiTestBase {

    private UIWindow window;

    @Before
    public void setUp() {
        WorkbenchSettings.declare();
        ShaderGraphSettings.register();
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    /**
     * <b>A setting that is not writable at the user layer never appears.</b>
     *
     * <p>The filter is the point of the window, not a detail. {@code ShaderGraphSettings} declares its
     * render queue {@code writableAt(DOCUMENT, MEMORY)} exactly so it cannot become a global preference,
     * and a window listing every registered declaration would put it there — which is the failure
     * {@code Setting.writableAt}'s own documentation describes: a user sets it once and every graph they
     * open silently inherits it.</p>
     */
    @Test
    public void aDocumentOnlySettingIsNotOfferedAsAPreference() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();

        boolean sawWorkbenchSetting = false;
        for (Setting<?> shown : preferences.shownSettings()) {
            assertTrue("'" + shown.getId() + "' is not writable at the user layer and must not be shown "
                            + "as a preference", shown.isWritableAt(SettingsLayer.USER));
            if (shown == WorkbenchSettings.SORT_ORDER) sawWorkbenchSetting = true;
        }
        assertTrue("the window showed nothing at all, so the assertion above proves nothing",
                sawWorkbenchSetting);
        assertFalse("a document-scoped setting was offered as a global preference",
                preferences.shownSettings().contains(ShaderGraphSettings.QUEUE));
    }

    /** Grouped by the first segment of the id, which is the grouping the registry already keys on. */
    @Test
    public void settingsAreGroupedByTheirIdPrefix() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        assertTrue("nothing was built", preferences.shownSettings().size() >= 5);
        assertEquals("every shown declaration must have produced a real control -- shownSettings() is a "
                        + "list this class appends to, so asserting on it alone passes even when every "
                        + "row came back null",
                preferences.shownSettings().size(), preferences.panel().controls().size());
        for (Setting<?> shown : preferences.shownSettings()) {
            org.junit.Assert.assertNotNull("no control was built for " + shown.getId(),
                    preferences.panel().control(shown.getId()));
        }
        assertTrue(Preferences.sectionNames().contains("explorer"));
        assertTrue(Preferences.sectionNames().contains("editor"));
        assertEquals("Explorer", Preferences.labelOf("explorer"));
    }

    /**
     * <b>Preferences survive a reload.</b>
     *
     * <p>Through the real storage interface and the real codec, so what is exercised is what production
     * runs — only the destination of the bytes differs.</p>
     */
    @Test
    public void preferencesSurviveAReload() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        com.crystalgui.core.settings.Settings settings = window.ui.rootElement.settings();
        settings.set(SettingsLayer.USER, WorkbenchSettings.TAB_SIZE, 8);
        settings.set(SettingsLayer.USER, WorkbenchSettings.SORT_ORDER, "FILES_FIRST");

        storage.write("settings.json", SettingsCodec.toJson(settings.layer(SettingsLayer.USER)));

        com.crystalgui.core.settings.Settings reloaded = new com.crystalgui.core.settings.Settings();
        SettingsModel model = SettingsCodec.fromJson(storage.read("settings.json"));
        reloaded.replaceLayer(SettingsLayer.USER, model.asMap());

        assertEquals(Integer.valueOf(8), reloaded.get(WorkbenchSettings.TAB_SIZE));
        assertEquals("FILES_FIRST", reloaded.get(WorkbenchSettings.SORT_ORDER));
    }

    /**
     * <b>A malformed preferences file degrades to the defaults rather than refusing to start.</b>
     *
     * <p>Settings files are hand-edited. Refusing to open the editor because one line is wrong is a
     * support burden with no upside, and is the same call {@code Setting.read} makes for one bad value.</p>
     */
    @Test
    public void aMalformedPreferencesFileDoesNotStopAnythingStarting() {
        SettingsModel model = SettingsCodec.fromJson("{ this is not json");
        assertTrue("an unreadable file must yield nothing, not throw", model.isEmpty());

        com.crystalgui.core.settings.Settings settings = new com.crystalgui.core.settings.Settings();
        settings.replaceLayer(SettingsLayer.USER, model.asMap());
        assertEquals("the declared default must stand", Integer.valueOf(4),
                settings.get(WorkbenchSettings.TAB_SIZE));
    }

    /** Absent is not the same as empty, and neither is an error. */
    @Test
    public void anAbsentPreferencesFileIsNormal() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        assertTrue(SettingsCodec.fromJson(storage.read("settings.json")).isEmpty());
    }

    /** A read-only store refuses writes loudly rather than pretending to save. */
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
            assertEquals("'" + setting.getId() + "' is declared but not registered, so it can never be "
                    + "shown", setting, registry.get(setting.getId()));
        }
    }

    /**
     * <b>The sort-order options are the enum's own constant names.</b>
     *
     * <p>{@code Setting.select} takes strings so an option list can come from a registry, which means
     * nothing stops the two drifting — a renamed constant would leave a dropdown offering a value that
     * silently falls back. This is the check that keeps them together.</p>
     */
    @Test
    public void everySortOrderOptionNamesARealConstant() {
        for (String option : WorkbenchSettings.SORT_ORDER.getOptions()) {
            com.crystalgui.ui.elements.workbench.WorkspaceTreeSource.SortOrder.valueOf(option);
        }
        assertEquals(com.crystalgui.ui.elements.workbench.WorkspaceTreeSource.SortOrder.values().length,
                WorkbenchSettings.SORT_ORDER.getOptions().size());
    }

    /**
     * <b>Every row is actually in the window.</b>
     *
     * <p>The test the empty window got past. {@code ConfiguratorPanel.controls()} is keyed by id and
     * populated by {@code addTo} whether or not the row's parent is attached to anything, so a window
     * whose groups were built and never added still reported a full set of controls — and rendered
     * nothing at all. This asks the <em>tree</em>, which is the only thing that can tell the difference.</p>
     */
    @Test
    public void everyRowIsReallyInTheWindow() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();

        java.util.List<UIElement> rows = preferences.dialog()
                .querySelectorAll(".__configurator__");
        assertEquals("a row was built but never attached, so the window renders empty",
                preferences.shownSettings().size(), rows.size());
        assertTrue("the window showed nothing, so the count above proves nothing", rows.size() >= 5);
        for (UIElement row : rows) {
            assertTrue("a row laid out at zero height is invisible", row.getRuntimeCache().getHeight() > 0f);
        }
    }

    /**
     * <b>It is a dialog with a class, not a dialog subclass.</b>
     *
     * <p>A widget's cascade identity is its tag, so a subclass reports {@code preferences} and every
     * {@code dialog …} rule in the sheet stops applying — which showed up as a title bar with no height
     * and a close button stretched across it. The same failure {@code AGENTS.md} records for
     * {@code Dropdown extends Button}.</p>
     */
    @Test
    public void theWindowIsStyledAsADialog() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        assertEquals("the sheet's dialog rules only reach something whose tag IS dialog",
                "dialog", preferences.dialog().tagName());
        assertTrue(preferences.dialog().hasClass(Preferences.DIALOG_CLASS));
        assertTrue("the title bar has no height, so dialog styling did not reach it",
                preferences.dialog().getTitleBar().getRuntimeCache().getHeight() > 0f);
    }

    /**
     * <b>It opens in the middle, and it can be resized.</b>
     *
     * <p>Centring cannot happen at open time — nothing has laid out, so the width is zero and "centred"
     * is the top-left corner, which is exactly where it appeared. The dialog is therefore held invisible
     * for the frame in between rather than allowed to show up in the corner and jump.</p>
     */
    @Test
    public void itOpensCentredAndResizable() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();

        float width = preferences.dialog().getRuntimeCache().getWidth();
        float left = preferences.dialog().getRuntimeCache().getX();
        float expected = (window.getScreenWidth() - width) / 2f;
        assertTrue("opened at x=" + left + ", expected about " + expected,
                Math.abs(left - expected) < 2f);
        assertEquals("a preferences window with no resize handles cannot recover from a label column "
                        + "that is too narrow for somebody's language",
                com.crystalgui.style.property.visual.Resize.BOTH,
                preferences.dialog().getStyle().getGeneralGroup().resize());
    }

    /**
     * <b>The list scrolls rather than being clipped by the dialog.</b>
     *
     * <p>The panel is the thing that must overflow. Given no height of its own it grows to fit its
     * content, overflows the dialog's own {@code overflow: hidden} content box instead, and the result
     * reads as a missing scrollbar when it is really a panel that was never given a box to scroll
     * inside.</p>
     */
    @Test
    public void theListScrollsInsteadOfBeingClipped() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();

        float panelHeight = preferences.panel().getRuntimeCache().getHeight();
        float dialogHeight = preferences.dialog().getRuntimeCache().getHeight();
        assertTrue("the panel (" + panelHeight + ") grew past the dialog (" + dialogHeight + ") instead "
                        + "of scrolling inside it", panelHeight <= dialogHeight);
        assertTrue("the panel has no height at all", panelHeight > 0f);

        // Shrunk until the content cannot fit, which is the only state the question is about. At its
        // natural size the list fits, and asserting on that would pass with a panel that can never
        // scroll at all.
        preferences.dialog().layout(l -> l.height(120f));
        settle();
        assertTrue("shrinking the window past its content left nothing to scroll, so the rows are being "
                        + "clipped by the dialog rather than scrolled inside the panel",
                preferences.panel().getMaxScrollTop() > 0f);
        assertTrue("the bar is hidden, so there is no way to discover the rows below the fold",
                preferences.panel().isScrollbarsVisible());
    }
}
