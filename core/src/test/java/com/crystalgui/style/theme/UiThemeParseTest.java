package com.crystalgui.style.theme;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Theme-file parsing and the registry's refusal posture ({@code plan/style-overhaul.md} §4.4): a file is
 * either valid and offered, or refused with a log — never half-installed. Theme files arrive from
 * outside eventually, so every malformation here is a real input, not a hypothetical.
 */
public class UiThemeParseTest {

    @After
    public void tearDown() {
        ThemeRegistry.resetForTesting();
    }

    private static final String FULL_HEADER = """
            /* @theme  Crystal Dark
             * @id     crystalgui:crystal-dark
             * @kind   dark
             * @extends —
             * @editor-scheme crystalgui:dark-plus
             * @author crystalgui */
            theme {
                --surface-panel: #2B2D30;
            }
            """;

    @Test
    public void aFullHeaderParses() {
        UiTheme theme = UiTheme.parse(FULL_HEADER);
        assertEquals("crystalgui:crystal-dark", theme.id());
        assertEquals("Crystal Dark", theme.displayName());
        assertEquals(UiTheme.Role.THEME, theme.role());
        assertEquals(UiTheme.Kind.DARK, theme.kind());
        assertNull("'—' means no parent", theme.parentId());
        assertEquals("crystalgui:dark-plus", theme.editorScheme());
        assertEquals("crystalgui", theme.author());
        assertEquals("#2B2D30", theme.variables().get("--surface-panel"));
    }

    @Test
    public void aSchemeDeclaresItselfByItsFirstTag() {
        UiTheme scheme = UiTheme.parse("""
                /* @scheme Dark+
                 * @id     crystalgui:dark-plus
                 * @kind   dark */
                """);
        assertEquals(UiTheme.Role.SCHEME, scheme.role());
        assertEquals("Dark+", scheme.displayName());
    }

    @Test
    public void anExtendsParentIsCarried() {
        UiTheme theme = UiTheme.parse("""
                /* @theme  Child
                 * @id     test:child
                 * @kind   dark
                 * @extends test:parent */
                """);
        assertEquals("test:parent", theme.parentId());
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMissingIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> UiTheme.parse("/* @theme X\n * @kind dark */"));
    }

    @Test
    public void aMalformedIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> UiTheme.parse("/* @theme X\n * @id not-namespaced\n * @kind dark */"));
    }

    @Test
    public void aMissingKindIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> UiTheme.parse("/* @theme X\n * @id t:x */"));
    }

    @Test
    public void declaringBothArtifactsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> UiTheme.parse("/* @theme X\n * @scheme Y\n * @id t:x\n * @kind dark */"));
    }

    /**
     * <b>The poison check.</b> An unknown pseudo-class poisons a whole sheet at parse — one
     * {@code :focus-within} rule once broke six unrelated panels. For a theme that must surface at
     * REGISTRATION as a refusal, not at apply as a blank window.
     *
     * <p>The example is {@code :nth-child} because {@code :focus-within} is <b>supported now</b> —
     * registered in {@code PseudoClasses} when the workbench needed "the focus is inside me" to tint
     * the focused region's tab. This test failing on that change was the correct outcome and not a
     * nuisance: the guard is about UNKNOWN names, so the moment a name joins the set it stops being
     * an example of one. {@code :nth-child} is still genuinely unimplemented (see AGENTS.md's
     * unsupported-selector list), which is what makes it a valid stand-in.</p>
     */
    @Test
    public void aBadSelectorCostsOneRuleAndNotTheTheme() {
        // WAS "poisonedCssIsRefusedAtRegistration". The whole-sheet refusal it pinned was the defect
        // (audit §5 S3: one unknown pseudo-class took six unrelated panels down); since M5's 5.2 a
        // selector that will not parse drops ITS rule, warns, and the rest of the sheet applies -- the
        // CSS rule. So the theme registers, is offered, and simply lacks the row it could not read.
        assertTrue(ThemeRegistry.registerSource("""
                /* @theme Poisoned
                 * @id    test:poisoned
                 * @kind  dark */
                :nth-child(2) { opacity: 0.5; }
                """));
        assertNotNull("one bad rule does not refuse the theme", ThemeRegistry.get("test:poisoned"));
    }

    // ── the registry ────────────────────────────────────────────────────────────────────────────

    @Test
    public void listingFiltersByRole() {
        assertTrue(ThemeRegistry.registerSource("/* @theme A\n * @id t:a\n * @kind dark */"));
        assertTrue(ThemeRegistry.registerSource("/* @scheme B\n * @id t:b\n * @kind dark */"));
        assertEquals(1, ThemeRegistry.themes().size());
        assertEquals(1, ThemeRegistry.schemes().size());
        assertEquals("t:a", ThemeRegistry.themes().get(0).id());
        assertEquals("t:b", ThemeRegistry.schemes().get(0).id());
    }

    /** Re-registering an id replaces — the theme author's edit-look loop. */
    @Test
    public void reRegisteringAnIdReplacesTheEntry() {
        assertTrue(ThemeRegistry.registerSource("/* @theme First\n * @id t:x\n * @kind dark */"));
        assertTrue(ThemeRegistry.registerSource("/* @theme Second\n * @id t:x\n * @kind dark */"));
        assertEquals("Second", ThemeRegistry.get("t:x").displayName());
        assertEquals(1, ThemeRegistry.themes().size());
    }
}
