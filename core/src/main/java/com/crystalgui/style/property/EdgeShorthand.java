package com.crystalgui.style.property;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

/**
 * CSS's four-edge shorthand: 1–4 values, clockwise from the top.
 *
 * <pre>
 *   padding: 4px;              all four
 *   padding: 4px 8px;          top and bottom, left and right
 *   padding: 4px 8px 2px;      top, left and right, bottom
 *   padding: 4px 8px 2px 0;    top, right, bottom, left
 * </pre>
 *
 * <p>{@code margin}, {@code padding}, {@code border-width}, {@code border-style}, {@code border-color} and
 * {@code outline-offset} are each one of these over their four longhands.</p>
 */
public record EdgeShorthand(String name, StyleProperty<?> top, StyleProperty<?> right,
                            StyleProperty<?> bottom, StyleProperty<?> left) implements Shorthand {

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(top, right, bottom, left);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> v = CssParsingUtil.splitTopLevelSpaces(value);
        String t, r, b, l;
        switch (v.size()) {
            case 1 -> t = r = b = l = v.get(0);
            case 2 -> { t = b = v.get(0); r = l = v.get(1); }
            case 3 -> { t = v.get(0); r = l = v.get(1); b = v.get(2); }
            case 4 -> { t = v.get(0); r = v.get(1); b = v.get(2); l = v.get(3); }
            default -> { return false; }
        }
        out.add(Shorthand.declaration(top, t, important));
        out.add(Shorthand.declaration(right, r, important));
        out.add(Shorthand.declaration(bottom, b, important));
        out.add(Shorthand.declaration(left, l, important));
        return true;
    }
}
