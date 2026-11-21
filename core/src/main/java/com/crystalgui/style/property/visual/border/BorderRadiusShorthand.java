package com.crystalgui.style.property.visual.border;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.StyleRule;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.crystalgui.style.property.visual.border.BorderRadiusProperties.*;

/**
 * CSS {@code border-radius: <horizontal> [ / <vertical> ]}, each a 1–4 value list from the top-left
 * corner clockwise. With no {@code /} both radii of a corner are the same, a circular corner.
 *
 * <pre>
 *   border-radius: 4px;              every corner
 *   border-radius: 8px 0;            top-left and bottom-right, top-right and bottom-left
 *   border-radius: 50% / 10px;       elliptical: 50% across, 10px down
 * </pre>
 *
 * <p>One corner alone is {@code border-top-left-radius: h [v]}, in {@code Shorthands}.</p>
 */
public final class BorderRadiusShorthand implements Shorthand {

    @Override
    public String name() {
        return "border-radius";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(TOP_LEFT_X, TOP_LEFT_Y, TOP_RIGHT_X, TOP_RIGHT_Y,
                BOTTOM_RIGHT_X, BOTTOM_RIGHT_Y, BOTTOM_LEFT_X, BOTTOM_LEFT_Y);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        String[] sides = value.split("/", 2);
        String[] h = corners(sides[0]);
        String[] v = sides.length == 2 ? corners(sides[1]) : h;
        if (h == null || v == null) return false;
        out.add(Shorthand.declaration(TOP_LEFT_X, h[0], important));
        out.add(Shorthand.declaration(TOP_LEFT_Y, v[0], important));
        out.add(Shorthand.declaration(TOP_RIGHT_X, h[1], important));
        out.add(Shorthand.declaration(TOP_RIGHT_Y, v[1], important));
        out.add(Shorthand.declaration(BOTTOM_RIGHT_X, h[2], important));
        out.add(Shorthand.declaration(BOTTOM_RIGHT_Y, v[2], important));
        out.add(Shorthand.declaration(BOTTOM_LEFT_X, h[3], important));
        out.add(Shorthand.declaration(BOTTOM_LEFT_Y, v[3], important));
        return true;
    }

    /** [top-left, top-right, bottom-right, bottom-left], or null on a bad value count. */
    @Nullable
    private static String[] corners(String list) {
        List<String> t = CssParsingUtil.splitTopLevelSpaces(list);
        return switch (t.size()) {
            case 1 -> new String[]{t.get(0), t.get(0), t.get(0), t.get(0)};
            case 2 -> new String[]{t.get(0), t.get(1), t.get(0), t.get(1)};
            case 3 -> new String[]{t.get(0), t.get(1), t.get(2), t.get(1)};
            case 4 -> new String[]{t.get(0), t.get(1), t.get(2), t.get(3)};
            default -> null;
        };
    }
}
