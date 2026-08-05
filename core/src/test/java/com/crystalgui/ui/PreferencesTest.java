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
        // A root that REFUSES public children, which is what every composite is -- CrystalEditor
        // included. addOverlay then falls back to the window's own zero-sized overlay layer, and a
        // dialog parented there clamps every position write to zero. A permissive root hides that
        // entirely: the dialog lands on a full-size parent and centres, drags and resizes correctly for
        // a reason production does not have.
        UIElement root = new UIElement() {
            @Override public boolean acceptsPublicChildren() { return false; }
        };
        root.layout(l -> l.widthPercent(100f).heightPercent(100f));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
    }

    /**
     * A frame, INCLUDING the input half.
     *
     * <p>{@code updateWithoutPainting} alone is not a frame as far as the input handler is concerned:
     * {@code firstFrameOver} is set in {@code endFrame()}, and until it is, every keyboard event is
     * dropped on the floor. A fixture that skips it silently proves nothing about any key.</p>
     */
    private void settle() {
        for (int i = 0; i < 4; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
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
        assertTrue("an unpromoted dialog is clamped against whatever addOverlay parented it to -- a "
                        + "zero-sized layer under any root that refuses public children -- so it cannot "
                        + "leave the corner by centring, by dragging or by resizing",
                preferences.dialog().isInTopLayer());
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
        assertTrue("the bar is switched off, so there is no way to discover the rows below the fold",
                preferences.panel().isScrollbarsVisible());

        // The FLAG being true is not the bar being visible, and asserting only the flag is how an
        // invisible scrollbar shipped: the sizing rule was a list of tags naming every ScrollerView
        // subclass, ConfiguratorPanel was not among them, and its bars laid out at zero width while the
        // wheel scrolled perfectly.
        UIElement bar = preferences.panel().querySelectorAll(".__v-scroller__").stream()
                .findFirst().orElse(null);
        org.junit.Assert.assertNotNull("the panel built no vertical scrollbar at all", bar);
        assertTrue("the scrollbar has zero width, so it cannot be seen or grabbed",
                bar.getRuntimeCache().getWidth() > 0f);
        assertTrue("the scrollbar has zero height", bar.getRuntimeCache().getHeight() > 0f);
    }

    /**
     * <b>No bars when nothing overflows.</b>
     *
     * <p>{@code overflow: scroll} means ALWAYS SHOW in CSS, and the panel said exactly that — so both
     * bars sat there permanently, a horizontal one included for content that never overflows sideways.
     * It went unseen for as long as the bars had no width to draw with, and became obvious the moment
     * they did.</p>
     */
    @Test
    public void theBarsAreHiddenWhenNothingOverflows() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        // Tall enough that every row fits, which is the state the window opens in.
        preferences.dialog().layout(l -> l.height(700f));
        settle();

        assertEquals("nothing overflows vertically, so there is nothing to scroll",
                0f, preferences.panel().getMaxScrollTop(), 0.01f);
        for (UIElement bar : preferences.panel().querySelectorAll(".__h-scroller__")) {
            assertEquals("a horizontal bar is drawn for content that never overflows sideways",
                    0f, bar.getRuntimeCache().getHeight(), 0.01f);
        }
        for (UIElement bar : preferences.panel().querySelectorAll(".__v-scroller__")) {
            assertEquals("a vertical bar is drawn although everything fits",
                    0f, bar.getRuntimeCache().getWidth(), 0.01f);
        }
    }

    // ── Escape, and the fade ────────────────────────────────────────────────────────────────────

    private void escape() {
        window.getInputHandler().consumeKeyboardEvent(new com.crystalgraphics.platform.input
                .CgSystemInput.Keyboard.Event((char) 0,
                com.crystalgraphics.platform.input.CgKeyCodes.KEY_ESCAPE, true, false, 20L));
        settle();
    }

    /** Escape closes it while focus is inside. */
    @Test
    public void escapeClosesItWhileFocused() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        window.getInputHandler().requestFocus(preferences.dialog());
        settle();

        escape();
        assertFalse("Escape did not close the window", preferences.dialog().isOpen());
    }

    /**
     * <b>Escape outside it does nothing.</b>
     *
     * <p>The reason this is a bubbling listener and not a close watcher. A close watcher is a window-wide
     * stack whose topmost entry wins wherever focus is, which is right for a modal and wrong here: this
     * window is modeless by design, so it would eat Escape from the editor behind it, where Escape
     * already means something.</p>
     */
    @Test
    public void escapeElsewhereLeavesItOpen() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        UIElement outside = new UIElement();
        outside.setFocusPolicy(com.crystalgui.ui.input.FocusPolicy.FOCUSABLE);
        window.addOverlay(outside, null);
        settle();
        window.getInputHandler().requestFocus(outside);
        settle();

        escape();
        assertTrue("a modeless window must not eat Escape from whatever has focus elsewhere",
                preferences.dialog().isOpen());
    }

    /**
     * <b>The fade has both halves of its pair.</b>
     *
     * <p>Asserting the <em>inputs</em>, not an intermediate opacity: {@code TransitionEngine} advances on
     * {@code System.nanoTime()} and ignores the delta it is handed, so a test loop cannot step animation
     * time. What can be checked is that the resting value is in the sheet and that the open state is
     * visible to CSS — a box coming out of {@code display: none} has no previous opacity to interpolate
     * from, so without both halves the fade silently does nothing.</p>
     */
    @Test
    public void theOpenStateIsVisibleToTheStylesheet() {
        Preferences preferences = Preferences.open(window, window.ui.rootElement.settings());
        settle();
        assertTrue("a dialog with no open class cannot be animated at all -- there is nothing for a "
                        + "transition to run between", preferences.dialog().hasClass(
                com.crystalgui.ui.elements.Dialog.OPEN_CLASS));

        preferences.dialog().close();
        settle();
        assertFalse("the open class outlived the open state",
                preferences.dialog().hasClass(com.crystalgui.ui.elements.Dialog.OPEN_CLASS));

        String sheet = com.crystalgraphics.util.io.CgIO.loadSource("crystalgui:ui/styles/default.css");
        assertTrue("no transition is declared, so the pair below fades nothing",
                sheet.contains("dialog.__preferences__ {") && sheet.contains("transition: opacity"));
        assertTrue("no open-state rule, so the base opacity: 0 would simply hide the window",
                sheet.contains("dialog.__preferences__.__open__ {"));
    }

}
