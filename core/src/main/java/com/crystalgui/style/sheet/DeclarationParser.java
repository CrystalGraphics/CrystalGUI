package com.crystalgui.style.sheet;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.BoxEdgeShorthands;
import com.crystalgui.style.property.visual.OutlineOffsetShorthand;
import com.crystalgui.style.property.visual.OutlineShorthand;
import com.crystalgui.style.property.visual.border.BorderRadiusShorthand;
import com.crystalgui.style.property.visual.transform.TransformOriginShorthand;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The declaration half of the CSS grammar — {@code name: value;} pairs, {@code !important},
 * {@code var(--x)} substitution and shorthand expansion — with no notion of selectors, rules or
 * files.
 *
 * <h3>Why it is separate from {@link StyleSheet}</h3>
 * <p>Two reasons, one of them load-bearing.</p>
 *
 * <p>The load-bearing one: {@code StyleSheet} holds
 * {@code public static final StyleSheet DEFAULT = loadUserAgentSheet()}, so merely <em>class-loading</em>
 * it reads {@code default.css} off the classpath. Anything that only wants to parse a handful of
 * declarations — an element's inline style, a value arriving over a network — would otherwise pay
 * that I/O and log a packaging warning if the resource were absent.</p>
 *
 * <p>The other: inline styles and stylesheet blocks are genuinely the same grammar, and having one
 * implementation means {@code style="width: 80px"} and {@code width: 80px} inside a rule can never
 * drift apart.</p>
 */
public final class DeclarationParser {

    static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    static final Pattern RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    static final Pattern DECL_PATTERN = Pattern.compile("(?m)\\s*([\\w-]+)\\s*:\\s*([^;]+)\\s*;?");
    static final Pattern IMPORTANT_SUFFIX = Pattern.compile("(?i)!\\s*important\\s*$");
    static final Pattern VAR_REF = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*\\)");

    private DeclarationParser() {
    }

    /** Parses a bare declaration block — no braces, no selector, no variables in scope. This is the
     * entry point for an element's inline {@code style} text. */
    public static List<StyleRule.Declaration> parseBlock(String declarations) {
        return parseBlock(declarations, Map.of());
    }

    /** As {@link #parseBlock(String)}, resolving {@code var(--name)} against {@code variables}. */
    public static List<StyleRule.Declaration> parseBlock(String block, Map<String, String> variables) {
        List<StyleRule.Declaration> declarations = new ArrayList<>();
        Matcher declMatcher = DECL_PATTERN.matcher(block);
        while (declMatcher.find()) {
            String name = declMatcher.group(1);
            if (name.startsWith("--")) continue; // a variable definition, collected separately
            String rawValue = declMatcher.group(2).trim();

            boolean important = false;
            Matcher importantMatcher = IMPORTANT_SUFFIX.matcher(rawValue);
            if (importantMatcher.find()) {
                important = true;
                rawValue = rawValue.substring(0, importantMatcher.start()).trim();
            }
            rawValue = substituteVariables(rawValue, variables);

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

            if (BorderRadiusShorthand.isBorderRadius(name)) {
                BorderRadiusShorthand.expand(declarations, rawValue, important);
                continue;
            }

            // Also a 1-4 value edge shorthand, and — unlike CSS's single-scalar version — the four
            // edges are the real properties. See OutlineOffsetShorthand for why per-edge.
            if (OutlineOffsetShorthand.isOutlineOffset(name)) {
                OutlineOffsetShorthand.expand(declarations, rawValue, important);
                continue;
            }

            // 1-2 values (or keywords) over transform-origin-x/-y. `transform` itself is a real
            // registered property and falls through to the lookup below — only the origin is syntax.
            if (TransformOriginShorthand.isTransformOrigin(name)) {
                TransformOriginShorthand.expand(declarations, rawValue, important);
                continue;
            }

            // `outline` is polymorphic: a drawable slot OR a width/color shorthand, decided by the
            // value's shape. Must run before the registry lookup below, which would otherwise always
            // resolve it to the drawable property.
            if (OutlineShorthand.isOutline(name)) {
                OutlineShorthand.expand(declarations, rawValue, important);
                continue;
            }

            StyleProperty<?> property = StylePropertyRegistry.byName(name);
            if (property == null) {
                CrystalGuiCore.LOGGER.warn("Unknown style property '{}' — skipping declaration", name);
                continue;
            }
            var value = property.valueParser.parse(rawValue);
            declarations.add(new StyleRule.Declaration(property, value, important));
        }
        return declarations;
    }

    /**
     * Collects every {@code --name: value} definition in {@code stripped}, from every rule, in one
     * pass before any rule is parsed — so a variable can be used above the rule that defines it.
     * Real CSS custom properties don't care about declaration order within their scope.
     */
    static Map<String, String> collectVariables(String stripped) {
        Map<String, String> variables = new HashMap<>();
        Matcher ruleMatcher = RULE_PATTERN.matcher(stripped);
        while (ruleMatcher.find()) {
            Matcher declMatcher = DECL_PATTERN.matcher(ruleMatcher.group(2));
            while (declMatcher.find()) {
                String name = declMatcher.group(1);
                if (!name.startsWith("--")) continue;
                String rawValue = declMatcher.group(2).trim();
                Matcher importantMatcher = IMPORTANT_SUFFIX.matcher(rawValue);
                if (importantMatcher.find()) {
                    rawValue = rawValue.substring(0, importantMatcher.start()).trim();
                }
                variables.put(name, rawValue);
            }
        }
        return variables;
    }

    /** Substitutes every {@code var(--name)} reference with the variable's raw (unparsed) text — no
     * recursive re-substitution, and no {@code var(--name, fallback)} two-arg form; both are natural
     * follow-ups if a real need shows up, not built speculatively. An undefined variable is left as
     * literal {@code var(...)} text and warned about — it will then fail in whatever type-specific
     * parser it reaches next, the same failure mode as any other malformed value. */
    static String substituteVariables(String rawValue, Map<String, String> variables) {
        if (!rawValue.contains("var(")) return rawValue;
        Matcher matcher = VAR_REF.matcher(rawValue);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String varName = matcher.group(1);
            String resolved = variables.get(varName);
            out.append(rawValue, last, matcher.start());
            if (resolved != null) {
                out.append(resolved);
            } else {
                CrystalGuiCore.LOGGER.warn("Undefined CSS variable '{}' referenced via var(...) — leaving as-is", varName);
                out.append(matcher.group());
            }
            last = matcher.end();
        }
        out.append(rawValue, last, rawValue.length());
        return out.toString();
    }

    /** Strips {@code //} and comments from a source string. */
    static String stripComments(String source) {
        return BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(source).replaceAll("")).replaceAll("");
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
}
