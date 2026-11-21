package com.crystalgui.style.property.visual.transform;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleRule;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

import static com.crystalgui.style.property.StylePropertyRegistry.TRANSFORM_ORIGIN_X;
import static com.crystalgui.style.property.StylePropertyRegistry.TRANSFORM_ORIGIN_Y;

/**
 * CSS {@code transform-origin: x [y]}: lengths, percentages or keywords, the omitted axis centred.
 *
 * <pre>
 *   transform-origin: 50% 50%       the default, the element's centre
 *   transform-origin: 0 0           its top-left corner
 *   transform-origin: left          left centre
 *   transform-origin: bottom left   keywords in either order
 *   transform-origin: center bottom
 * </pre>
 *
 * <p>No third (Z) value: transforms here are 2D.</p>
 */
public final class TransformOriginShorthand implements Shorthand {

    /** Both axes' initial value, the centre, so a scale or rotation stays put. */
    public static final LengthPercent CENTER = LengthPercent.percent(0.5f);

    @Override
    public String name() {
        return "transform-origin";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(TRANSFORM_ORIGIN_X, TRANSFORM_ORIGIN_Y);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> t = CssParsingUtil.splitTopLevelSpaces(value);
        if (t.isEmpty() || t.size() > 2) return false;
        // `top left` is `left top`: only a keyword can arrive reversed, and only a vertical one leads it.
        boolean reversed = isVertical(t.get(0)) || (t.size() == 2 && isHorizontal(t.get(1)));
        String rawX = reversed ? (t.size() == 2 ? t.get(1) : "center") : t.get(0);
        String rawY = reversed ? t.get(0) : (t.size() == 2 ? t.get(1) : "center");
        LengthPercent x = resolve(rawX, false);
        LengthPercent y = resolve(rawY, true);
        if (x == null || y == null) return false;
        out.add(new StyleRule.Declaration(TRANSFORM_ORIGIN_X, StyleValue.of(x.toString(), x), important));
        out.add(new StyleRule.Declaration(TRANSFORM_ORIGIN_Y, StyleValue.of(y.toString(), y), important));
        return true;
    }

    private static boolean isVertical(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("top") || t.equals("bottom");
    }

    private static boolean isHorizontal(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("left") || t.equals("right");
    }

    private static @Nullable LengthPercent resolve(String token, boolean vertical) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "center" -> CENTER;
            case "left" -> vertical ? null : LengthPercent.percent(0f);
            case "right" -> vertical ? null : LengthPercent.percent(1f);
            case "top" -> vertical ? LengthPercent.percent(0f) : null;
            case "bottom" -> vertical ? LengthPercent.percent(1f) : null;
            default -> LengthPercent.parse(token);
        };
    }
}
