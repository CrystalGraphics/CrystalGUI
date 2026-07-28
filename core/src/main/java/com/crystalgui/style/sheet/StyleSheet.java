package com.crystalgui.style.sheet;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.HashMap;
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

    private StyleSheet(List<StyleRule> rules) {
        this(rules, StyleOrigin.STYLESHEET);
    }

    private StyleSheet(List<StyleRule> rules, StyleOrigin origin) {
        this.rules = rules;
        this.origin = origin;
        for (var rule : rules) index(rule);
    }

    public static StyleSheet parse(String source) {
        String stripped = DeclarationParser.stripComments(source);
        Map<String, String> variables = DeclarationParser.collectVariables(stripped);

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
        return new StyleSheet(rules);
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
        StyleSheet sheet = StyleSheetRegistry.of("crystalgui:default");
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
}
