package com.crystalgui.style.property.layout;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;
import java.util.Locale;

/**
 * CSS {@code flex}: grow, shrink and basis in one.
 *
 * <pre>
 *   flex: 1;            1 1 0      a share of the free space
 *   flex: 2 0;          2 0 0
 *   flex: 1 120px;      1 1 120px
 *   flex: 0 0 120px;    a fixed 120px
 *   flex: auto;         1 1 auto
 *   flex: none;         0 0 auto
 *   flex: initial;      0 1 auto
 * </pre>
 *
 * <p>Easy to get wrong: {@code flex: 1} shrinks, because CSS's shorthand sets shrink to 1, while a bare
 * {@code flex-grow: 1} keeps this engine's {@code flex-shrink: 0} default.</p>
 */
public final class FlexShorthand implements Shorthand {

    @Override
    public String name() {
        return "flex";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(LayoutProperties.FLEX_GROW, LayoutProperties.FLEX_SHRINK, LayoutProperties.FLEX_BASIS);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> v = CssParsingUtil.splitTopLevelSpaces(value);
        String grow, shrink, basis;
        if (v.size() == 1 && isKeyword(v.get(0))) {
            switch (v.get(0).toLowerCase(Locale.ROOT)) {
                case "none" -> { grow = "0"; shrink = "0"; basis = "auto"; }
                case "auto" -> { grow = "1"; shrink = "1"; basis = "auto"; }
                default -> { grow = "0"; shrink = "1"; basis = "auto"; }
            }
        } else {
            grow = null; shrink = null; basis = null;
            for (String token : v) {
                if (isNumber(token) && basis == null) {
                    if (grow == null) grow = token;
                    else if (shrink == null) shrink = token;
                    else return false;
                } else if (basis == null && (CssLength.parse(token) != null || isBasisKeyword(token))) {
                    basis = token;
                } else {
                    return false;
                }
            }
            if (grow == null && basis == null) return false;
            if (grow == null) grow = "1";
            if (shrink == null) shrink = "1";
            if (basis == null) basis = "0";
        }
        out.add(Shorthand.declaration(LayoutProperties.FLEX_GROW, grow, important));
        out.add(Shorthand.declaration(LayoutProperties.FLEX_SHRINK, shrink, important));
        out.add(Shorthand.declaration(LayoutProperties.FLEX_BASIS, basis, important));
        return true;
    }

    private static boolean isKeyword(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("none") || t.equals("auto") || t.equals("initial");
    }

    private static boolean isBasisKeyword(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("auto") || t.equals("content") || t.endsWith("-content");
    }

    /** A unitless number. A bare 0 is a number here, as CSS reads {@code flex: 0}. */
    private static boolean isNumber(String token) {
        try {
            Float.parseFloat(token);
            return token.chars().allMatch(c -> Character.isDigit(c) || c == '.' || c == '-' || c == '+');
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
