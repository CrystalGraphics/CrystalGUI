package com.crystalgui.style.sheet;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A parsed LSS-subset stylesheet: an ordered list of {@link StyleRule}s, bucket-indexed by the
 * rightmost compound selector's id/class/type for fast per-element candidate lookup.
 *
 * <p>Supported syntax: {@code selector, selector { name: value; name: value !important; }} — see
 * the stylesheet+transitions plan for the documented CSS subset (id/class/type/pseudo-class
 * selectors, descendant/child combinators, comma-separated selector lists, {@code !important}; NOT
 * {@code :not()}, attribute selectors, sibling combinators, pseudo-elements, at-rules).
 *
 * <p>{@code sourceOrder} is local to one sheet — two different sheets that both match the same
 * element+property at identical specificity fall back to sheet registration order in
 * {@code StyleEngine}, not a single global counter. Acceptable v1 simplification: real-world
 * stylesheets rarely need cross-sheet tie-breaking at the same specificity.
 *
 * <h3>Client-side only</h3>
 * <p><b>This class cannot be loaded without CrystalGraphics</b>, and that is deliberate rather than
 * an oversight. {@link #DEFAULT} is a {@code static final}, so merely touching the class reads
 * {@code default.css} through {@code CgIO} — which means even {@link #parse(String)}, an API that
 * reads no file, throws {@code NoClassDefFoundError} on a dedicated server.</p>
 *
 * <p>Nothing server-side needs it: a networked UI ships stylesheets <em>by reference</em> and the
 * client — which always has CrystalGraphics — resolves and parses them. Code that only needs to turn
 * declaration text into values, such as an element's inline {@code style}, uses
 * {@link DeclarationParser} instead, which is free of both this class and CrystalGraphics.</p>
 */
public final class StyleSheet {

    /**
     * The engine's user-agent stylesheet: {@code assets/crystalgui/ui/styles/default.css}.
     *
     * <p>Gives every widget functional (deliberately unthemed) geometry, plus a few generic layout
     * helpers. <b>Not applied automatically</b> — hand it to the engine like any other sheet:</p>
     * <pre>{@code window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);}</pre>
     *
     * <p>It carries {@link StyleOrigin#USER_AGENT}, so any author sheet added alongside it wins at
     * any specificity. Add it before or after a theme — the ordering genuinely doesn't matter.</p>
     */
    public static final StyleSheet DEFAULT = loadUserAgentSheet();

    private final List<StyleRule> rules;
    /** Cascade origin every declaration in this sheet is applied at — see {@link StyleEngine}. */
    private final StyleOrigin origin;
    private final Map<String, List<StyleRule>> byId = new HashMap<>();
    private final Map<String, List<StyleRule>> byClass = new HashMap<>();
    private final Map<String, List<StyleRule>> byType = new HashMap<>();
    private final List<StyleRule> universal = new ArrayList<>();

    /**
     * The CSS text this sheet was parsed from — retained so the sheet can be <b>re-substituted
     * in place</b> against a different external variable table ({@link #rebind}), which is what a
     * theme swap is. {@code null} only for rule-copy instances ({@link #DEFAULT}), which the
     * registry refills by mirroring the cached source sheet instead.
     *
     * <p>Deliberately kept in memory rather than re-read through {@code CgIO} on demand: a theme
     * swap must not depend on the resource still being readable, and inline sheets
     * ({@code StyleSheet.parse(...)} in a scene or test) have no path to re-read from at all.</p>
     */
    private String rawSource;

    private StyleSheet(List<StyleRule> rules) {
        this(rules, StyleOrigin.STYLESHEET);
    }

    private StyleSheet(List<StyleRule> rules, StyleOrigin origin) {
        // A MUTABLE COPY the sheet owns. Callers hand in whatever they have -- parse() a fresh ArrayList,
        // loadUserAgentSheet() the immutable result of getRules() -- and replaceRules has to be able to
        // empty it. Storing the caller's list meant DEFAULT held a List.copyOf and every hot reload threw
        // UnsupportedOperationException on the one sheet the feature exists for.
        this.rules = new ArrayList<>(rules);
        this.origin = origin;
        for (var rule : this.rules) index(rule);
    }

    /**
     * Parses {@code source} against the registry's currently {@linkplain
     * StyleSheetRegistry#boundVariables() bound variable table} — so a sheet parsed while a theme
     * is active resolves the theme's tokens, regardless of load order. With no theme bound the
     * table is empty and only the sheet's own variables apply.
     */
    public static StyleSheet parse(String source) {
        return parse(source, StyleSheetRegistry.boundVariables());
    }

    /**
     * Parses {@code source}, resolving {@code var()} references against the sheet's own variable
     * definitions merged over {@code externalVariables} — <b>locals win</b>, so a sheet's own
     * domain variables ({@code graph.css}'s {@code --graph-*}) are sovereign and can never be
     * captured by a theme that happens to reuse a name. The merged table is resolved to a fixed
     * point first, so definitions may reference definitions ({@link DeclarationParser#resolveTable}).
     */
    public static StyleSheet parse(String source, Map<String, String> externalVariables) {
        String stripped = DeclarationParser.stripComments(source);
        Map<String, String> locals = DeclarationParser.collectVariables(stripped);
        Map<String, String> variables;
        if (externalVariables.isEmpty()) {
            variables = DeclarationParser.resolveTable(locals);
        } else {
            Map<String, String> merged = new LinkedHashMap<>(externalVariables);
            merged.putAll(locals);
            variables = DeclarationParser.resolveTable(merged);
        }

        List<StyleRule> rules = new ArrayList<>();
        int sourceOrder = 0;
        Matcher ruleMatcher = DeclarationParser.RULE_PATTERN.matcher(stripped);
        while (ruleMatcher.find()) {
            String selectorList = ruleMatcher.group(1).trim();
            if (selectorList.isEmpty()) continue;

            List<StyleRule.Declaration> declarations = DeclarationParser.parseBlock(ruleMatcher.group(2), variables);
            if (declarations.isEmpty()) continue;

            for (String selectorText : selectorList.split(",")) {
                String trimmed = selectorText.trim();
                if (trimmed.isEmpty()) continue;
                rules.add(new StyleRule(Selector.parse(trimmed), declarations, sourceOrder));
            }
            sourceOrder++;
        }
        StyleSheet sheet = new StyleSheet(rules);
        sheet.rawSource = source;
        return sheet;
    }

    /** Collects the {@code --name: value} definitions in {@code source} (comments stripped, every
     * rule, unresolved) — the public face of variable collection, for the theme layer. */
    public static Map<String, String> variablesOf(String source) {
        return DeclarationParser.collectVariables(DeclarationParser.stripComments(source));
    }

    private void index(StyleRule rule) {
        var compounds = rule.selector().compounds();
        var rightmost = compounds.get(compounds.size() - 1);
        boolean indexed = false;
        for (var part : rightmost.parts()) {
            switch (part.type()) {
                case ID -> {
                    byId.computeIfAbsent(part.identity(), k -> new ArrayList<>()).add(rule);
                    indexed = true;
                }
                case CLASS -> {
                    byClass.computeIfAbsent(part.identity(), k -> new ArrayList<>()).add(rule);
                    indexed = true;
                }
                case TYPE -> {
                    byType.computeIfAbsent(part.identity(), k -> new ArrayList<>()).add(rule);
                    indexed = true;
                }
                default -> { /* UNIVERSAL / PSEUDO_CLASS alone can't narrow the bucket */ }
            }
        }
        if (!indexed) universal.add(rule);
    }

    /**
     * All rules whose bucket key could plausibly match {@code element} — a bucket hit only narrows
     * the candidate set, callers must still verify with {@link Selector#matches}.
     */
    public List<StyleRule> candidatesFor(UIElement element) {
        Set<StyleRule> candidates = new LinkedHashSet<>(universal);
        if (!element.getId().isEmpty()) {
            candidates.addAll(byId.getOrDefault(element.getId(), List.of()));
        }
        for (String cls : element.getClasses()) {
            candidates.addAll(byClass.getOrDefault(cls, List.of()));
        }
        candidates.addAll(byType.getOrDefault(element.tagName(), List.of()));
        return new ArrayList<>(candidates);
    }

    /** Cascade origin every non-{@code !important} declaration in this sheet is applied at. */
    public StyleOrigin getOrigin() {
        return origin;
    }

    /** Re-reads {@code default.css} at {@link StyleOrigin#USER_AGENT}.
     *
     * <p>Shouts if it comes back empty rather than failing quietly: {@code StyleSheetRegistry.of}
     * returns an empty sheet for a missing resource, and because {@link #DEFAULT} holds the result
     * forever, a packaging slip would otherwise surface only as every widget silently laying out at
     * 0x0 — which is precisely the failure this file exists to prevent. */
    private static StyleSheet loadUserAgentSheet() {
        StyleSheet sheet = StyleSheetRegistry.of(StyleSheetRegistry.DEFAULT_SHEET);
        if (sheet.getRules().isEmpty()) {
            CrystalGuiCore.LOGGER.error(
                    "StyleSheet.DEFAULT is EMPTY — 'assets/crystalgui/ui/styles/default.css' is missing or "
                            + "failed to parse. Widgets have no default geometry and will lay out at zero size.");
        }
        return new StyleSheet(sheet.getRules(), StyleOrigin.USER_AGENT);
    }

    public List<StyleRule> getRules() {
        return List.copyOf(rules);
    }

    /**
     * Swaps this sheet's rules for a freshly parsed set, <b>keeping the instance</b>.
     *
     * <p>Identity is the whole point. {@link StyleEngine} holds sheets in a list, {@link #DEFAULT} is a
     * {@code static final}, and every window that has ever called {@code addStylesheet} holds the same
     * reference — so a reload that produced a <em>new</em> sheet would update nothing that is already on
     * screen, and {@code DEFAULT} could not be replaced at all. Refilling in place means every existing
     * holder sees the new rules with no re-registration.</p>
     *
     * <p>The indices are rebuilt too, not merely appended to: {@link #candidatesFor} answers out of them,
     * so a rule deleted from the file has to leave the buckets or it keeps matching. Dropping a stale
     * <em>candidate</em> is then {@code StyleEngine.rematch}'s job, which it already does correctly — it
     * remembers what it last applied per element and swaps the whole set atomically.</p>
     *
     * <p>Package-private, and reached through {@link StyleSheetRegistry#reloadAll()} rather than called
     * directly: a sheet does not know which file it came from, and the registry is what does.</p>
     */
    void replaceRules(List<StyleRule> replacement) {
        rules.clear();
        rules.addAll(replacement);
        byId.clear();
        byClass.clear();
        byType.clear();
        universal.clear();
        for (var rule : rules) index(rule);
    }

    /**
     * Re-substitutes this sheet's retained source against {@code externalVariables} and swaps the
     * rules in place — the <b>theme-binding</b> operation. No I/O; identity-stable, so every window
     * holding this sheet sees the new values with no re-registration (the same guarantee
     * {@link #replaceRules} documents). No-op for rule-copy instances with no source
     * ({@link #DEFAULT} — the registry mirrors it from the cached source sheet instead).
     */
    void rebind(Map<String, String> externalVariables) {
        if (rawSource == null) return;
        replaceRules(parse(rawSource, externalVariables).getRules());
    }

    /**
     * Replaces both the retained source and the rules — the <b>hot-reload</b> operation, for when
     * the file's text itself changed. Parses against {@code externalVariables} so a reload while a
     * theme is active keeps the theme's values.
     */
    void refill(String newSource, Map<String, String> externalVariables) {
        this.rawSource = newSource;
        replaceRules(parse(newSource, externalVariables).getRules());
    }

    /**
     * Public identity-stable swap: adopts {@code freshlyParsed}'s rules and source, keeping
     * <em>this</em> instance — for holders like {@code UiThemeManager} whose sheets live in engine
     * lists and must never be re-registered. The parsed argument is a carrier, not a peer: parse
     * into a throwaway, hand it here, drop it.
     */
    public void refillFrom(StyleSheet freshlyParsed) {
        this.rawSource = freshlyParsed.rawSource;
        replaceRules(freshlyParsed.getRules());
    }
}
