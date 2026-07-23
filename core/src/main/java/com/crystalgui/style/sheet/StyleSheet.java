package com.crystalgui.style.sheet;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.BoxEdgeShorthands;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public final class StyleSheet {
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    private static final Pattern DECL_PATTERN = Pattern.compile("(?m)\\s*([\\w-]+)\\s*:\\s*([^;]+)\\s*;?");
    private static final Pattern IMPORTANT_SUFFIX = Pattern.compile("(?i)!\\s*important\\s*$");

    private final List<StyleRule> rules;
    private final Map<String, List<StyleRule>> byId = new HashMap<>();
    private final Map<String, List<StyleRule>> byClass = new HashMap<>();
    private final Map<String, List<StyleRule>> byType = new HashMap<>();
    private final List<StyleRule> universal = new ArrayList<>();

    private StyleSheet(List<StyleRule> rules) {
        this.rules = rules;
        for (var rule : rules) index(rule);
    }

    public static StyleSheet parse(String source) {
        String stripped = BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(source).replaceAll("")).replaceAll("");

        List<StyleRule> rules = new ArrayList<>();
        int sourceOrder = 0;
        Matcher ruleMatcher = RULE_PATTERN.matcher(stripped);
        while (ruleMatcher.find()) {
            String selectorList = ruleMatcher.group(1).trim();
            if (selectorList.isEmpty()) continue;

            List<StyleRule.Declaration> declarations = parseDeclarations(ruleMatcher.group(2));
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

    private static List<StyleRule.Declaration> parseDeclarations(String block) {
        List<StyleRule.Declaration> declarations = new ArrayList<>();
        Matcher declMatcher = DECL_PATTERN.matcher(block);
        while (declMatcher.find()) {
            String name = declMatcher.group(1);
            String rawValue = declMatcher.group(2).trim();

            boolean important = false;
            Matcher importantMatcher = IMPORTANT_SUFFIX.matcher(rawValue);
            if (importantMatcher.find()) {
                important = true;
                rawValue = rawValue.substring(0, importantMatcher.start()).trim();
            }

            // margin/padding/border-width (and their -all/-horizontal/-vertical aliases) are pure
            // shorthand syntax, never registered StyleProperty instances — expand into the real
            // longhand declarations here, at parse time, so normal cascade resolution (origin/
            // specificity/source-order — all shared with this declaration) decides ties, exactly
            // like a real browser's shorthand-to-longhand expansion. See BoxEdgeShorthands.
            BoxEdgeShorthands.Match shorthand = BoxEdgeShorthands.lookup(name);
            if (shorthand != null) {
                expandBoxEdgeShorthand(declarations, shorthand, rawValue, important);
                continue;
            }

            if (com.crystalgui.style.property.visual.border.BorderRadiusShorthand.isBorderRadius(name)) {
                com.crystalgui.style.property.visual.border.BorderRadiusShorthand.expand(declarations, rawValue, important);
                continue;
            }

            StyleProperty<?> property = StylePropertyRegistry.byName(name);
            if (property == null) {
                CrystalGuiCore.LOGGER.warn("Unknown style property '{}' in stylesheet — skipping declaration", name);
                continue;
            }
            var value = property.valueParser.parse(rawValue);
            declarations.add(new StyleRule.Declaration(property, value, important));
        }
        return declarations;
    }

    private static void expandBoxEdgeShorthand(List<StyleRule.Declaration> out, BoxEdgeShorthands.Match match,
                                                String rawValue, boolean important) {
        BoxEdgeShorthands.Group group = match.group();
        switch (match.kind()) {
            case ALL -> {
                out.add(edgeDeclaration(group.left(), rawValue, important));
                out.add(edgeDeclaration(group.top(), rawValue, important));
                out.add(edgeDeclaration(group.right(), rawValue, important));
                out.add(edgeDeclaration(group.bottom(), rawValue, important));
            }
            case HORIZONTAL -> {
                out.add(edgeDeclaration(group.left(), rawValue, important));
                out.add(edgeDeclaration(group.right(), rawValue, important));
            }
            case VERTICAL -> {
                out.add(edgeDeclaration(group.top(), rawValue, important));
                out.add(edgeDeclaration(group.bottom(), rawValue, important));
            }
            case COMPOSITE -> {
                // Real CSS 1/2/3/4-value shorthand rule (clockwise from top for the 4-value form).
                String[] tokens = rawValue.trim().split("\\s+");
                String top, right, bottom, left;
                switch (tokens.length) {
                    case 1 -> { top = right = bottom = left = tokens[0]; }
                    case 2 -> { top = bottom = tokens[0]; left = right = tokens[1]; }
                    case 3 -> { top = tokens[0]; left = right = tokens[1]; bottom = tokens[2]; }
                    case 4 -> { top = tokens[0]; right = tokens[1]; bottom = tokens[2]; left = tokens[3]; }
                    default -> {
                        CrystalGuiCore.LOGGER.warn("Invalid {}-value shorthand '{}' for '{}' — expected 1-4 values",
                                tokens.length, rawValue, group.prefix());
                        return;
                    }
                }
                out.add(edgeDeclaration(group.left(), left, important));
                out.add(edgeDeclaration(group.top(), top, important));
                out.add(edgeDeclaration(group.right(), right, important));
                out.add(edgeDeclaration(group.bottom(), bottom, important));
            }
        }
    }

    private static StyleRule.Declaration edgeDeclaration(StyleProperty<LengthPercentageAuto> property,
                                                           String rawValue, boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(rawValue), important);
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

    public List<StyleRule> getRules() {
        return List.copyOf(rules);
    }
}
