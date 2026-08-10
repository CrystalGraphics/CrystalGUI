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
import java.util.LinkedHashMap;
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
    /** The NAME inside a {@code var(} reference — used only to classify leftovers in
     * {@link #resolveTable}; substitution itself is the scanner below, which a regex cannot be
     * because a fallback may nest parentheses. */
    static final Pattern VAR_NAME_REF = Pattern.compile("var\\(\\s*(--[\\w-]+)");

    /** Hard cap on nested substitution — a fallback containing {@code var()}, a table chaining
     * definition through definition. Past it the text is left literal and warned about, the same
     * degrade-don't-break posture as every other malformed value. */
    static final int MAX_VAR_DEPTH = 8;

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

    /**
     * Substitutes every {@code var(--name)} / {@code var(--name, fallback)} reference with the
     * variable's raw (unparsed) text.
     *
     * <p>The table handed in is expected to be <b>flat</b> — already run through
     * {@link #resolveTable}, so a defined name substitutes in one step. The one place recursion
     * remains is the <em>fallback</em>, which may itself be a {@code var()} chain
     * ({@code var(--a, var(--b, 8px))}) and is resolved depth-first, capped at
     * {@link #MAX_VAR_DEPTH}.</p>
     *
     * <p>An undefined variable with no fallback is left as literal {@code var(...)} text and warned
     * about — it will then fail in whatever type-specific parser it reaches next, the same failure
     * mode as any other malformed value.</p>
     *
     * <p>A scanner rather than a regex because a fallback nests parentheses, which no single
     * regular expression can pair up.</p>
     */
    static String substituteVariables(String rawValue, Map<String, String> variables) {
        return substituteVariables(rawValue, variables, 0, true);
    }

    private static String substituteVariables(String rawValue, Map<String, String> variables,
                                              int depth, boolean warnUndefined) {
        if (!rawValue.contains("var(")) return rawValue;
        if (depth > MAX_VAR_DEPTH) {
            CrystalGuiCore.LOGGER.warn("var() fallbacks nested deeper than {} in '{}' — leaving as-is",
                    MAX_VAR_DEPTH, rawValue);
            return rawValue;
        }
        StringBuilder out = new StringBuilder(rawValue.length());
        int i = 0;
        while (true) {
            int start = rawValue.indexOf("var(", i);
            if (start < 0) {
                out.append(rawValue, i, rawValue.length());
                break;
            }
            out.append(rawValue, i, start);
            int close = matchingParen(rawValue, start + 3); // start + 3 is the '('
            if (close < 0) {
                CrystalGuiCore.LOGGER.warn("Unbalanced parentheses in var() reference '{}' — leaving as-is", rawValue);
                out.append(rawValue, start, rawValue.length());
                break;
            }
            String inner = rawValue.substring(start + 4, close);
            int comma = topLevelComma(inner);
            String name = (comma < 0 ? inner : inner.substring(0, comma)).trim();
            String fallback = comma < 0 ? null : inner.substring(comma + 1).trim();
            String resolved = variables.get(name);
            if (resolved != null) {
                out.append(resolved);
            } else if (fallback != null) {
                out.append(substituteVariables(fallback, variables, depth + 1, warnUndefined));
            } else {
                if (warnUndefined) {
                    CrystalGuiCore.LOGGER.warn("Undefined CSS variable '{}' referenced via var(...) — leaving as-is", name);
                }
                out.append(rawValue, start, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * Resolves {@code var()} references <b>between a table's own definitions</b> to a fixed point,
     * so a definition may derive from another definition through any sane depth of indirection —
     * the mechanism that lets a component token say {@code --button-bg: var(--surface-raised)} and
     * a theme override forty system tokens instead of four hundred component ones.
     *
     * <p>Iterates whole passes until nothing changes (or {@link #MAX_VAR_DEPTH} passes, which
     * bounds legitimate chains and cycles alike). Afterwards a definition can still contain
     * {@code var()} for exactly two reasons, told apart deliberately:</p>
     * <ul>
     *   <li><b>It references a name this table defines</b> — that is a cycle (an acyclic defined
     *   reference would have resolved), warned about here and left literal.</li>
     *   <li><b>It references a name nothing defines</b> — a legitimate resting state (a component
     *   token waiting for a theme to supply its system token), left <em>silently</em>: the warning
     *   belongs at the point of use, where the declaration pass already emits it, not once per
     *   table rebind.</li>
     * </ul>
     *
     * <p>Undefined-reference warnings are suppressed during resolution for the same reason.</p>
     */
    public static Map<String, String> resolveTable(Map<String, String> definitions) {
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>(definitions);
        boolean changed = true;
        for (int pass = 0; pass < MAX_VAR_DEPTH && changed; pass++) {
            changed = false;
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                String value = entry.getValue();
                if (!value.contains("var(")) continue;
                String next = substituteVariables(value, resolved, 0, false);
                if (!next.equals(value)) {
                    entry.setValue(next);
                    changed = true;
                }
            }
        }
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String value = entry.getValue();
            if (!value.contains("var(")) continue;
            Matcher ref = VAR_NAME_REF.matcher(value);
            while (ref.find()) {
                if (resolved.containsKey(ref.group(1))) {
                    CrystalGuiCore.LOGGER.warn(
                            "Cyclic CSS variable definition: '{}' still references '{}' after resolution — leaving literal",
                            entry.getKey(), ref.group(1));
                    break;
                }
            }
        }
        return resolved;
    }

    /** Index of the {@code ')'} matching the {@code '('} at {@code open}, or -1 if unbalanced. */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** First comma in {@code s} not inside parentheses, or -1 — the name/fallback split point. */
    private static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
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
