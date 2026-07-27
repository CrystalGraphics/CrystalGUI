package com.crystalgui.style.property.visual;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-time handling for {@code outline:}, which is deliberately polymorphic — it is both a
 * drawable slot (like {@code background}/{@code overlay}/{@code mask}) and a CSS-style shorthand
 * over {@code outline-width}/{@code outline-color}:
 *
 * <pre>
 *   outline: asset("crystalgui:ore", "focus-ring");  -> OUTLINE (drawable, 9-slice ring texture)
 *   outline: 2px #4488ff;                            -> OUTLINE_WIDTH + OUTLINE_COLOR (SDF ring)
 *   outline: 1px;                                    -> OUTLINE_WIDTH
 *   outline: #4488ff;                                -> OUTLINE_COLOR
 *   outline: none;                                   -> OUTLINE_WIDTH: 0
 * </pre>
 *
 * <p>A bare color resolves to {@code outline-color}, NOT to a solid-fill drawable: a solid drawable
 * outline would simply cover the element, so it is never what an author meant.</p>
 *
 * <p>Mirrors {@code BorderRadiusShorthand}'s architecture — {@code StyleSheet.parseDeclarations}
 * tests shorthands before the property registry, so this intercepts {@code outline} first and
 * decides which real properties to emit.</p>
 */
public final class OutlineShorthand {
    public static final String NAME = "outline";

    private OutlineShorthand() {
    }

    public static boolean isOutline(String declarationName) {
        return NAME.equals(declarationName);
    }

    public static void expand(List<StyleRule.Declaration> out, String rawValue, boolean important) {
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) return;

        // Any function call means the drawable form. Checked first and on the whole value, since a
        // drawable's own arguments can contain colors/lengths that would otherwise be mistaken for
        // shorthand tokens (e.g. image("x.png", #ff0000)).
        if (containsFunctionCall(trimmed)) {
            StyleProperty<?> drawable = StylePropertyRegistry.OUTLINE;
            out.add(new StyleRule.Declaration(drawable, drawable.valueParser.parse(trimmed), important));
            return;
        }

        if (trimmed.equalsIgnoreCase("none")) {
            out.add(declaration(StylePropertyRegistry.OUTLINE_WIDTH, "0", important));
            return;
        }

        // Order-independent, like real CSS shorthands.
        String width = null;
        String color = null;
        for (String token : trimmed.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (isColorToken(token)) {
                if (color == null) color = token;
            } else {
                if (width == null) width = token;
            }
        }

        if (width == null && color == null) {
            CrystalGuiCore.LOGGER.warn("Unparseable 'outline' shorthand value '{}' — expected a drawable "
                    + "function, 'none', or <width> and/or <color>", rawValue);
            return;
        }
        if (width != null) out.add(declaration(StylePropertyRegistry.OUTLINE_WIDTH, width, important));
        if (color != null) out.add(declaration(StylePropertyRegistry.OUTLINE_COLOR, color, important));
    }

    /** True if the value contains a {@code name(...)} call at any depth — the marker for the
     * drawable form ({@code asset}/{@code image}/{@code sprite}/...). Deliberately not a whitelist
     * of function names, so a new drawable function works here without touching this class.
     * {@code rgb()}/{@code rgba()} are excluded, since those are colors, not drawables. */
    private static boolean containsFunctionCall(String value) {
        int paren = value.indexOf('(');
        if (paren <= 0) return false;
        String name = value.substring(0, paren).trim().toLowerCase();
        // Take only the trailing identifier, so "2px rgb(...)" reports name "rgb".
        int lastSpace = name.lastIndexOf(' ');
        if (lastSpace >= 0) name = name.substring(lastSpace + 1);
        return !name.isEmpty() && !name.equals("rgb") && !name.equals("rgba");
    }

    private static boolean isColorToken(String token) {
        return token.startsWith("#")
                || token.toLowerCase().startsWith("rgb(")
                || token.toLowerCase().startsWith("rgba(");
    }

    private static <T> StyleRule.Declaration declaration(StyleProperty<T> property, String rawValue, boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(rawValue), important);
    }

    /** Whether {@code transition: outline ...} should animate {@code longhand}. */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        return NAME.equals(transitionPropertyName) && (
                longhand == StylePropertyRegistry.OUTLINE
                        || longhand == StylePropertyRegistry.OUTLINE_WIDTH
                        || longhand == StylePropertyRegistry.OUTLINE_COLOR
        );
    }

    /** Convenience for tests/tools: the longhands this shorthand can emit. */
    public static List<StyleProperty<?>> longhands() {
        List<StyleProperty<?>> list = new ArrayList<>();
        list.add(StylePropertyRegistry.OUTLINE);
        list.add(StylePropertyRegistry.OUTLINE_WIDTH);
        list.add(StylePropertyRegistry.OUTLINE_COLOR);
        return list;
    }
}
