package com.crystalgui.style.sheet;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The variable substrate under theming ({@code plan_styling.md} §3.4): external tables, the
 * {@code var(--x, fallback)} two-arg form, and fixed-point table resolution.
 *
 * <p>String-level on purpose — substitution is textual, so its contract is decidable on strings,
 * and the window-level half (a swap restyling live elements) lives in {@code UiThemeManagerTest}
 * where a window is actually the thing under test.</p>
 */
public class CssVariablesTest {

    // ── substitution ────────────────────────────────────────────────────────────────────────────

    @Test
    public void aDefinedVariableSubstitutes() {
        assertEquals("9px", DeclarationParser.substituteVariables("var(--w)", Map.of("--w", "9px")));
    }

    @Test
    public void theFallbackIsUsedOnlyWhenUndefined() {
        assertEquals("4px", DeclarationParser.substituteVariables("var(--w, 4px)", Map.of()));
        assertEquals("9px", DeclarationParser.substituteVariables("var(--w, 4px)", Map.of("--w", "9px")));
    }

    /** A fallback may itself be a var() chain, resolved depth-first — how a sparse theme degrades. */
    @Test
    public void aNestedFallbackResolvesRecursively() {
        assertEquals("8px", DeclarationParser.substituteVariables("var(--a, var(--b, 8px))", Map.of()));
        assertEquals("3px", DeclarationParser.substituteVariables("var(--a, var(--b, 8px))", Map.of("--b", "3px")));
    }

    /** The pre-existing contract, kept: undefined with no fallback stays literal (and warns), so it
     * fails visibly in the type parser it reaches next rather than vanishing. */
    @Test
    public void undefinedWithoutFallbackStaysLiteral() {
        assertEquals("var(--nope)", DeclarationParser.substituteVariables("var(--nope)", Map.of()));
    }

    @Test
    public void unbalancedParenthesesAreLeftAlone() {
        assertEquals("var(--a", DeclarationParser.substituteVariables("var(--a", Map.of("--a", "x")));
    }

    @Test
    public void surroundingTextSurvivesSubstitution() {
        assertEquals("1px solid #FF0000",
                DeclarationParser.substituteVariables("1px solid var(--c)", Map.of("--c", "#FF0000")));
    }

    // ── table resolution ────────────────────────────────────────────────────────────────────────

    /** The tiering mechanism: a component token derives from a system token through any sane depth. */
    @Test
    public void aTableResolvesDefinitionChainsToAFixedPoint() {
        Map<String, String> resolved = DeclarationParser.resolveTable(Map.of(
                "--sys", "#111111",
                "--comp", "var(--sys)",
                "--deep", "var(--comp)"));
        assertEquals("#111111", resolved.get("--comp"));
        assertEquals("#111111", resolved.get("--deep"));
    }

    /**
     * A reference to a name nothing defines is a LEGITIMATE resting state — a component token
     * waiting for a theme — left literal and, critically, left <em>silent</em> here: the warning
     * belongs at the point of use, not once per table rebind.
     */
    @Test
    public void anUndefinedReferenceRestsLiterallyInTheTable() {
        Map<String, String> resolved = DeclarationParser.resolveTable(Map.of("--comp", "var(--sys)"));
        assertEquals("var(--sys)", resolved.get("--comp"));
    }

    /** A cycle degrades to literal text with a warning — never a hang, never a throw. */
    @Test
    public void aCyclicTableDegradesInsteadOfLooping() {
        Map<String, String> resolved = DeclarationParser.resolveTable(Map.of(
                "--a", "var(--b)",
                "--b", "var(--a)"));
        assertTrue(resolved.get("--a").contains("var("));
        assertTrue(resolved.get("--b").contains("var("));
    }

    // ── the parse seam ──────────────────────────────────────────────────────────────────────────

    /** An external table reaches an ordinary parse — the theme-binding seam. */
    @Test
    public void anExternalTableReachesTheParse() {
        StyleSheet sheet = StyleSheet.parse(".x { opacity: var(--o); }", Map.of("--o", "0.75"));
        assertEquals("0.75", sheet.getRules().get(0).declarations().get(0).value().rawValue);
    }

    /**
     * <b>Locals beat the external table.</b> A sheet's own domain variables ({@code graph.css}'s
     * {@code --graph-*}) are sovereign — a theme reusing a name must not capture them.
     */
    @Test
    public void aSheetsOwnVariablesBeatTheExternalTable() {
        StyleSheet sheet = StyleSheet.parse(
                "theme { --o: 0.25; } .x { opacity: var(--o); }",
                Map.of("--o", "0.75"));
        assertEquals("0.25", sheet.getRules().get(0).declarations().get(0).value().rawValue);
    }

    /** Local definitions may now chain through each other too — var-in-var inside one sheet. */
    @Test
    public void localDefinitionsMayReferenceEachOther() {
        StyleSheet sheet = StyleSheet.parse(
                "theme { --base: 0.5; --derived: var(--base); } .x { opacity: var(--derived); }",
                Map.of());
        assertEquals("0.5", sheet.getRules().get(0).declarations().get(0).value().rawValue);
    }
}
