package com.crystalgui.style.theme;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The live theme swap ({@code plan_styling.md} §3.5) — the spine, not the pixels: a swap restyles
 * a live window through in-place sheet mutation, the sheet <em>list</em> never changes, inheritance
 * merges child-over-parent, and the scheme is a genuinely independent axis.
 */
public class UiThemeManagerTest extends UiTestBase {

    private static final String DARK = """
            /* @theme  Test Dark
             * @id     test:dark
             * @kind   dark */
            theme {
                --probe-opacity: 0.25;
            }
            .rule-probe { opacity: 0.75; }
            """;

    private UIWindow window;
    private UIElement root;
    private UIElement varProbe;
    private UIElement ruleProbe;

    @Before
    public void setUpWindow() {
        ThemeRegistry.resetForTesting();
        UiThemeManager.getInstance().resetForTesting();

        varProbe = new UIElement().layout(l -> l.width(10).height(10));
        varProbe.addClass("theme-probe");
        ruleProbe = new UIElement().layout(l -> l.width(10).height(10));
        ruleProbe.addClass("rule-probe");

        root = new UIElement().layout(l -> l.width(100).height(100));
        root.addChildren(varProbe, ruleProbe);
        window = new UIWindow(Ui.of(root));

        UiThemeManager.getInstance().installInto(window.getStyleEngine());
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:theme-probe"));
        window.init(100, 100);
        window.updateWithoutPainting();
    }

    /** The bound table and the manager's sheets are process-global — leaking either into the next
     * test class would be exactly the cross-test contamination CommandRegistry already taught us. */
    @After
    public void tearDown() {
        UiThemeManager.getInstance().resetForTesting();
        ThemeRegistry.resetForTesting();
    }

    private float opacityOf(UIElement element) {
        window.updateWithoutPainting();
        return element.getStyle().getGeneralGroup().opacity();
    }

    // ── the swap ────────────────────────────────────────────────────────────────────────────────

    /** A theme's override RULES restyle a live window, and deactivation restores it. */
    @Test
    public void overrideRulesRestyleALiveWindow() {
        assertEquals("unthemed, the rule probe is untouched", 1f, opacityOf(ruleProbe), 0.001f);

        assertTrue(ThemeRegistry.registerSource(DARK));
        assertTrue(UiThemeManager.getInstance().setTheme("test:dark"));
        assertEquals(0.75f, opacityOf(ruleProbe), 0.001f);

        assertTrue(UiThemeManager.getInstance().setTheme(null));
        assertEquals("deactivation must restore the unthemed value", 1f, opacityOf(ruleProbe), 0.001f);
    }

    /**
     * <b>A theme's VARIABLES rebind a registry sheet in place.</b> The probe sheet says
     * {@code var(--probe-opacity, 0.5)}; the theme supplies the token; nothing about the sheet's
     * registration changes. This is the mechanism the whole tokenization migration stands on.
     */
    @Test
    public void themeVariablesRebindARegistrySheet() {
        assertEquals("the fallback is the unthemed resting value", 0.5f, opacityOf(varProbe), 0.001f);

        ThemeRegistry.registerSource(DARK);
        UiThemeManager.getInstance().setTheme("test:dark");
        assertEquals("the theme's token must reach the already-registered sheet",
                0.25f, opacityOf(varProbe), 0.001f);

        UiThemeManager.getInstance().setTheme(null);
        assertEquals(0.5f, opacityOf(varProbe), 0.001f);
    }

    /**
     * <b>The sheet list never changes across a swap.</b> Identity-stable in-place mutation is what
     * keeps "re-adding a sheet appends at highest priority" out of the picture by construction —
     * asserted on instances, not counts, because a remove-and-re-add would keep the count.
     */
    @Test
    public void theSheetListIsStableAcrossASwap() {
        List<StyleSheet> before = window.getStyleEngine().getSheets();
        ThemeRegistry.registerSource(DARK);
        UiThemeManager.getInstance().setTheme("test:dark");
        UiThemeManager.getInstance().setTheme(null);
        assertEquals(before, window.getStyleEngine().getSheets());
    }

    /** installInto is idempotent — a host that already added DEFAULT is not double-registered. */
    @Test
    public void installIntoIsIdempotent() {
        int count = window.getStyleEngine().getSheets().size();
        UiThemeManager.getInstance().installInto(window.getStyleEngine());
        assertEquals(count, window.getStyleEngine().getSheets().size());
    }

    // ── inheritance ─────────────────────────────────────────────────────────────────────────────

    /** {@code @extends}: the child overrides the parent — variables by merge order, rules by source
     * order across the concatenated chain. */
    @Test
    public void aChildThemeOverridesItsParent() {
        ThemeRegistry.registerSource("""
                /* @theme  Base
                 * @id     test:base
                 * @kind   dark */
                theme { --probe-opacity: 0.4; }
                .rule-probe { opacity: 0.6; }
                """);
        ThemeRegistry.registerSource("""
                /* @theme  Child
                 * @id     test:child
                 * @kind   dark
                 * @extends test:base */
                theme { --probe-opacity: 0.2; }
                .rule-probe { opacity: 0.9; }
                """);

        assertTrue(UiThemeManager.getInstance().setTheme("test:child"));
        assertEquals("child variable must beat the parent's", 0.2f, opacityOf(varProbe), 0.001f);
        assertEquals("child rule must beat the parent's equal-specificity rule",
                0.9f, opacityOf(ruleProbe), 0.001f);
    }

    /** A parent's rules still apply where the child is silent — a skin extends, not restates. */
    @Test
    public void aParentsRulesApplyWhereTheChildIsSilent() {
        ThemeRegistry.registerSource("""
                /* @theme  Base
                 * @id     test:base
                 * @kind   dark */
                .rule-probe { opacity: 0.6; }
                """);
        ThemeRegistry.registerSource("""
                /* @theme  Child
                 * @id     test:child
                 * @kind   dark
                 * @extends test:base */
                """);
        UiThemeManager.getInstance().setTheme("test:child");
        assertEquals(0.6f, opacityOf(ruleProbe), 0.001f);
    }

    // ── the second axis ─────────────────────────────────────────────────────────────────────────

    /** Scheme variables sit above theme variables in the merge — the editor's own axis wins for
     * the tokens it names, and deactivating it hands them back. */
    @Test
    public void schemeVariablesSitAboveThemeVariables() {
        ThemeRegistry.registerSource(DARK);
        ThemeRegistry.registerSource("""
                /* @scheme Test Scheme
                 * @id     test:scheme
                 * @kind   dark */
                theme { --probe-opacity: 0.1; }
                """);

        UiThemeManager.getInstance().setTheme("test:dark");
        assertTrue(UiThemeManager.getInstance().setScheme("test:scheme"));
        assertEquals(0.1f, opacityOf(varProbe), 0.001f);

        UiThemeManager.getInstance().setScheme(null);
        assertEquals("dropping the scheme must fall back to the theme's value",
                0.25f, opacityOf(varProbe), 0.001f);
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnknownIdIsRefusedAndTheStateKept() {
        ThemeRegistry.registerSource(DARK);
        UiThemeManager.getInstance().setTheme("test:dark");

        assertFalse(UiThemeManager.getInstance().setTheme("nope:nothing"));
        assertEquals("a refused swap must leave the previous theme standing",
                "test:dark", UiThemeManager.getInstance().activeThemeId());
        assertEquals(0.75f, opacityOf(ruleProbe), 0.001f);
    }

    @Test
    public void aSchemeIdIsRefusedAsATheme() {
        ThemeRegistry.registerSource("""
                /* @scheme S
                 * @id     test:s
                 * @kind   dark */
                """);
        assertFalse(UiThemeManager.getInstance().setTheme("test:s"));
        assertNull(UiThemeManager.getInstance().activeThemeId());
    }

    // ── the shipped theme ───────────────────────────────────────────────────────────────────────

    /**
     * <b>Binding crystal-dark is identical to running unthemed.</b> The migration's contract: the
     * shipped theme pins today's look exactly (its fine-tune block exists for this), so the whole
     * tokenization is a pure mechanical transform and every visual change later is a deliberate
     * edit to one theme file. Asserted through the real cascade on a real widget.
     */
    @Test
    public void bindingCrystalDarkChangesNothing() {
        Button button = new Button("probe");
        root.addChild(button);
        window.updateWithoutPainting();

        int unthemedColor = button.getStyle().getGeneralGroup().color();
        float unthemedProbe = opacityOf(varProbe);

        ThemeRegistry.registerBuiltins();
        assertTrue(UiThemeManager.getInstance().setTheme("crystalgui:crystal-dark"));
        window.updateWithoutPainting();

        assertEquals("crystal-dark must reproduce the unthemed look exactly",
                unthemedColor, button.getStyle().getGeneralGroup().color());
        assertEquals(unthemedProbe, opacityOf(varProbe), 0.001f);
    }

    // ── late arrivals ───────────────────────────────────────────────────────────────────────────

    /** A sheet parsed AFTER the bind resolves the active table — load order must not matter. */
    @Test
    public void aSheetParsedAfterTheBindSeesTheTable() {
        ThemeRegistry.registerSource(DARK);
        UiThemeManager.getInstance().setTheme("test:dark");

        UIElement late = new UIElement().layout(l -> l.width(10).height(10));
        late.addClass("late-probe");
        root.addChild(late);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".late-probe { opacity: var(--probe-opacity); }"));

        assertEquals(0.25f, opacityOf(late), 0.001f);
    }
}
