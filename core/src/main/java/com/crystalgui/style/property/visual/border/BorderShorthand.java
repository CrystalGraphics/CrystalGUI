package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSS {@code border} and {@code border-<side>}: {@code <width> || <style> || <color>}, any order, every
 * omitted part reset.
 *
 * <pre>
 *   border: 1px solid #46505E;    every side
 *   border-top: 2px #46505E;      the top side; style back to its initial solid
 *   border: none;                 no border
 * </pre>
 *
 * <p>An omitted width is CSS's {@code medium}, 3px; an omitted colour is {@code currentcolor}. The width
 * keywords {@code thin}, {@code medium} and {@code thick} are 1, 3 and 5px.</p>
 */
public record BorderShorthand(String name, List<StyleProperty<?>> widths, List<StyleProperty<?>> styles,
                              List<StyleProperty<?>> colors) implements Shorthand {

    @Override
    public List<StyleProperty<?>> longhands() {
        List<StyleProperty<?>> all = new ArrayList<>(widths);
        all.addAll(styles);
        all.addAll(colors);
        return all;
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        Line line = Line.parse(value);
        if (line == null) return false;
        for (StyleProperty<?> p : widths) {
            out.add(Shorthand.declaration(p, line.width() != null ? line.width() : "3px", important));
        }
        for (StyleProperty<?> p : styles) {
            out.add(line.style() != null ? Shorthand.declaration(p, line.style(), important)
                    : Shorthand.initial(p, important));
        }
        for (StyleProperty<?> p : colors) {
            out.add(line.color() != null ? Shorthand.declaration(p, line.color(), important)
                    : Shorthand.initial(p, important));
        }
        return true;
    }

    /**
     * One {@code <width> || <style> || <color>} value, each part null when omitted. Shared with
     * {@code outline}.
     *
     * @param styleKeywords the style keywords this line takes: a border's or an outline's
     */
    public record Line(@Nullable String width, @Nullable String style, @Nullable String color) {

        public static @Nullable Line parse(String value) {
            return parse(value, BorderStyle.class);
        }

        public static @Nullable Line parse(String value, Class<? extends Enum<?>> styleKeywords) {
            String width = null, style = null, color = null;
            List<String> tokens = CssParsingUtil.splitTopLevelSpaces(value);
            if (tokens.isEmpty() || tokens.size() > 3) return null;
            for (String token : tokens) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (style == null && isStyle(lower, styleKeywords)) style = lower;
                else if (width == null && widthOf(lower) != null) width = widthOf(lower);
                else if (color == null && ColorValue.isColor(token)) color = token;
                else return null;
            }
            return new Line(width, style, color);
        }

        private static boolean isStyle(String token, Class<? extends Enum<?>> keywords) {
            for (Enum<?> e : keywords.getEnumConstants()) {
                if (e.name().toLowerCase(Locale.ROOT).equals(token)) return true;
            }
            return false;
        }

        private static @Nullable String widthOf(String token) {
            return switch (token) {
                case "thin" -> "1px";
                case "medium" -> "3px";
                case "thick" -> "5px";
                default -> CssLength.parse(token) != null ? token : null;
            };
        }
    }
}
