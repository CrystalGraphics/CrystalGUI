package com.crystalgui.style.theme;

import com.crystalgui.render.texture.asset.FileIconTheme;
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
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

    /** Re-setting the active id is a NO-OP — settings re-apply wholesale on every change, and an
     * unrelated toggle must not re-substitute every stylesheet. Observed through onChanged. */
    @Test
    public void settingTheSameThemeTwiceAppliesOnce() {
        ThemeRegistry.registerSource(DARK);
        int[] emits = {0};
        UiThemeManager.getInstance().onChanged.connect(() -> emits[0]++);

        assertTrue(UiThemeManager.getInstance().setTheme("test:dark"));
        assertTrue(UiThemeManager.getInstance().setTheme("test:dark"));
        assertEquals("the second identical set must not re-apply", 1, emits[0]);
    }

    // ── the user's own overrides ────────────────────────────────────────────────────────────────

    /**
     * <b>An override beats the theme, and SURVIVES a theme swap.</b>
     *
     * <p>VS Code's {@code workbench.colorCustomizations} and the reason nobody there forks a theme to
     * change one colour. Last in the merge, so it re-applies on top of whatever is picked next —
     * "I always want this token pink" is a statement about the user, not about the theme.</p>
     */
    @Test
    public void anOverrideBeatsTheThemeAndSurvivesASwap() {
        ThemeRegistry.registerSource(DARK);
        UiThemeManager.getInstance().setTheme("test:dark");
        assertEquals(0.25f, opacityOf(varProbe), 0.001f);

        UiThemeManager.getInstance().setOverrides(Map.of("probe-opacity", "0.9"));
        assertEquals("the override must beat the theme", 0.9f, opacityOf(varProbe), 0.001f);

        ThemeRegistry.registerSource("""
                /* @theme  Other
                 * @id     test:other
                 * @kind   dark */
                theme { --probe-opacity: 0.3; }
                """);
        UiThemeManager.getInstance().setTheme("test:other");
        assertEquals("the override must survive the swap", 0.9f, opacityOf(varProbe), 0.001f);

        UiThemeManager.getInstance().setOverrides(Map.of());
        assertEquals("and clearing it must hand the token back to the theme",
                0.3f, opacityOf(varProbe), 0.001f);
    }

    /** An override works with no theme at all — the user has not opted into a theme to have an opinion. */
    @Test
    public void anOverrideAppliesWithNoThemeActive() {
        assertEquals(0.5f, opacityOf(varProbe), 0.001f);
        UiThemeManager.getInstance().setOverrides(Map.of("--probe-opacity", "0.15"));
        assertEquals(0.15f, opacityOf(varProbe), 0.001f);
    }

    /** The {@code --} prefix is optional — a hand-written settings file must not fail on a detail
     * that carries no information. */
    @Test
    public void overrideKeysAreNormalised() {
        UiThemeManager.getInstance().setOverrides(Map.of("probe-opacity", "0.15"));
        assertTrue(UiThemeManager.getInstance().overrides().containsKey("--probe-opacity"));
        assertEquals(0.15f, opacityOf(varProbe), 0.001f);
    }

    /** An override may point one token at another, not just at a literal. */
    @Test
    public void anOverrideMayReferenceAnotherToken() {
        ThemeRegistry.registerBuiltins();
        UiThemeManager.getInstance().setTheme("crystalgui:crystal-dark");
        UiThemeManager.getInstance().setOverrides(Map.of("--probe-opacity", "var(--themed-probe)",
                "--themed-probe", "0.42"));
        assertEquals(0.42f, opacityOf(varProbe), 0.001f);
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
     * <b>The unthemed fallbacks and the shipped theme are two different looks, and both are
     * deliberate.</b>
     *
     * <p>Through step 8 these were identical — the migration's contract, so that tokenizing 485
     * hexes could be proven a pure transform. Step 9's paydown ended that on purpose: the ua/
     * fallbacks stay the FUNCTIONAL look (default.css's original promise — every widget usable with
     * no theme installed, and every test and harness scene that installs no theme unaffected),
     * while {@code crystal-dark} became a DESIGNED look whose component tokens derive from one
     * ~30-role palette. That is the function/look split the plan once wanted as two files, arrived
     * at by value instead — and the button is the clearest case: unthemed it is a pale slab with
     * dark text (legacy), themed it is a raised dark surface with light text (coherent).</p>
     */
    @Test
    public void theShippedThemeIsADesignedLookNotTheRawFallbacks() {
        Button button = new Button("probe");
        root.addChild(button);
        window.updateWithoutPainting();
        int unthemed = button.getStyle().getGeneralGroup().color();

        ThemeRegistry.registerBuiltins();
        assertTrue(UiThemeManager.getInstance().setTheme("crystalgui:crystal-dark"));
        window.updateWithoutPainting();
        int themed = button.getStyle().getGeneralGroup().color();

        assertNotEquals("crystal-dark must be a design, not a restatement of the fallbacks",
                unthemed, themed);

        // ...and specifically: the themed label is LIGHT, because a dark theme's button is a dark
        // surface. The unthemed one is near-black on pale grey.
        assertTrue("a dark theme's button label must be light, was " + Integer.toHexString(themed),
                luminance(themed) > 0.5f);
        assertTrue("the unthemed button label must stay dark (the legacy functional look)",
                luminance(unthemed) < 0.3f);
    }

    /** Switching between the two shipped themes moves a value in both directions — the dropdown
     * doing what a dropdown promises. */
    @Test
    public void theTwoShippedThemesDiffer() {
        Button button = new Button("probe");
        root.addChild(button);
        ThemeRegistry.registerBuiltins();

        UiThemeManager.getInstance().setTheme("crystalgui:crystal-dark");
        window.updateWithoutPainting();
        int dark = button.getStyle().getGeneralGroup().color();

        assertTrue(UiThemeManager.getInstance().setTheme("crystalgui:crystal-light"));
        window.updateWithoutPainting();
        int light = button.getStyle().getGeneralGroup().color();

        assertNotEquals(dark, light);
        assertTrue("a light theme's button label must be dark", luminance(light) < 0.3f);
    }

    /**
     * <b>The file-type icon set follows the theme's kind.</b>
     *
     * <p>These are drawings, not tinted glyphs: JetBrains ships {@code java.svg} and
     * {@code java_dark.svg} because a filled multi-colour shape cannot be recoloured for the opposite
     * background the way a monochrome stroke can. So this is the one thing a theme changes that no token
     * can express, and {@code FileIconTheme}'s own javadoc had been waiting for themes to land to drive
     * it. Unthemed stays DARK, matching the ua/ fallbacks' dark-first look.</p>
     */
    @Test
    public void theIconSetFollowsTheThemesKind() {
        ThemeRegistry.registerBuiltins();

        UiThemeManager.getInstance().setTheme("crystalgui:crystal-dark");
        assertEquals(FileIconTheme.Variant.DARK, FileIconTheme.getVariant());
        assertTrue("a dark theme must reach the _dark drawings",
                FileIconTheme.withVariant("crystalgui:filetypes/java").endsWith("_dark"));

        UiThemeManager.getInstance().setTheme("crystalgui:crystal-light");
        assertEquals(FileIconTheme.Variant.LIGHT, FileIconTheme.getVariant());
        assertEquals("a light theme must use the base drawings",
                "crystalgui:filetypes/java", FileIconTheme.withVariant("crystalgui:filetypes/java"));

        UiThemeManager.getInstance().setTheme(null);
        assertEquals("unthemed keeps the dark drawings", FileIconTheme.Variant.DARK,
                FileIconTheme.getVariant());
    }

    /** Rough perceptual luminance of an ARGB int, 0..1 — enough to ask "is this light or dark". */
    private static float luminance(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** The scheme axis IS still identity-preserving: dark-plus's values were extracted from the
     * editor's own fallbacks, so it restates them rather than redesigning them. (Unlike the UI
     * theme, which step 9 turned into a design — the editor's colours were already coherent.) */
    @Test
    public void theShippedSchemeRestatesTheEditorsFallbacks() {
        ThemeRegistry.registerBuiltins();
        assertTrue(UiThemeManager.getInstance().setScheme("crystalgui:dark-plus"));

        StyleSheet late = StyleSheet.parse(".x { opacity: var(--syntax-keyword); }");
        assertEquals("#569CD6", late.getRules().get(0).declarations().get(0).value().rawValue);
    }

    /** crystal-dark suggests its bundled scheme, IntelliJ's editorScheme — offered, never forced. */
    @Test
    public void theShippedThemeSuggestsItsScheme() {
        ThemeRegistry.registerBuiltins();
        assertEquals("crystalgui:dark-plus",
                ThemeRegistry.get("crystalgui:crystal-dark").editorScheme());
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
