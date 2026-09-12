package com.crystalgui.style.property.visual.text;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-time handling for {@code text-stroke:}, a shorthand over {@code text-stroke-width} and
 * {@code text-stroke-color} — the web's {@code -webkit-text-stroke} without the prefix.
 *
 * <pre>
 *   text-stroke: 2px #0B5D8F;            -&gt; TEXT_STROKE_WIDTH + TEXT_STROKE_COLOR
 *   text-stroke: 2px;                     -&gt; TEXT_STROKE_WIDTH
 *   text-stroke: #0B5D8F;                 -&gt; TEXT_STROKE_COLOR
 *   text-stroke: 0.04em rgb(11, 93, 143); -&gt; both; the colour's own commas and spaces survive
 *   text-stroke: none;                    -&gt; TEXT_STROKE_WIDTH: 0
 * </pre>
 *
 * <p>Order-independent, as CSS shorthands are: {@code #0B5D8F 2px} means the same thing. Only the
 * two properties named appear here — {@code stroke-align} and {@code paint-order} are separate
 * declarations, matching CSS, where neither is part of {@code -webkit-text-stroke} either.</p>
 *
 * <p>Easy to get wrong: a shorthand must be intercepted in {@link
 * com.crystalgui.style.sheet.DeclarationParser} BEFORE the registry lookup, and it must be matched
 * with {@code equals} rather than a prefix test — {@code text-stroke} is a prefix of both its own
 * longhands, so a {@code startsWith} would swallow them.</p>
 */
public final class TextStrokeShorthand {

    public static final String NAME = "text-stroke";

    private TextStrokeShorthand() {
    }

    public static boolean isTextStroke(String declarationName) {
        return NAME.equals(declarationName);
    }

    public static void expand(List<StyleRule.Declaration> out, String rawValue, boolean important) {
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) return;

        if (trimmed.equalsIgnoreCase("none")) {
            out.add(declaration(StylePropertyRegistry.TEXT_STROKE_WIDTH, "0", important));
            return;
        }

        String width = null;
        String color = null;
        for (String token : tokenize(trimmed)) {
            if (isColorToken(token)) {
                if (color == null) color = token;
            } else if (width == null) {
                width = token;
            }
        }

        if (width == null && color == null) {
            CrystalGuiCore.LOGGER.warn("Unparseable 'text-stroke' shorthand value '{}' — expected "
                    + "'none', or <width> and/or <color>", rawValue);
            return;
        }
        if (width != null) out.add(declaration(StylePropertyRegistry.TEXT_STROKE_WIDTH, width, important));
        if (color != null) out.add(declaration(StylePropertyRegistry.TEXT_STROKE_COLOR, color, important));
    }

    /**
     * Splits on whitespace but never inside parentheses, so {@code rgb(11, 93, 143)} arrives as one
     * token. A plain {@code split("\\s+")} tears it into three, and the pieces then read as neither a
     * colour nor a width and are silently dropped.
     */
    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>(2);
        int depth = 0;
        int start = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);

            boolean separator = depth == 0 && Character.isWhitespace(c);
            if (separator) {
                if (start >= 0) {
                    tokens.add(value.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) tokens.add(value.substring(start));
        return tokens;
    }

    /** The colour syntaxes {@code ColorValue} actually accepts, and nothing else is one. */
    private static boolean isColorToken(String token) {
        String lower = token.toLowerCase();
        return lower.startsWith("#")
                || lower.startsWith("rgb(")
                || lower.startsWith("rgba(")
                || lower.equals("transparent");
    }

    private static <T> StyleRule.Declaration declaration(StyleProperty<T> property, String rawValue,
                                                         boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(rawValue), important);
    }

    /** Whether {@code transition: text-stroke ...} should animate {@code longhand}. */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        return NAME.equals(transitionPropertyName) && (
                longhand == StylePropertyRegistry.TEXT_STROKE_WIDTH
                        || longhand == StylePropertyRegistry.TEXT_STROKE_COLOR
        );
    }

    /** Convenience for tests/tools: the longhands this shorthand can emit. */
    public static List<StyleProperty<?>> longhands() {
        List<StyleProperty<?>> list = new ArrayList<>();
        list.add(StylePropertyRegistry.TEXT_STROKE_WIDTH);
        list.add(StylePropertyRegistry.TEXT_STROKE_COLOR);
        return list;
    }
}
