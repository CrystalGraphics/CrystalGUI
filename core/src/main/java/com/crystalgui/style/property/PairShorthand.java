package com.crystalgui.style.property;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.sheet.StyleRule;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A two-part shorthand: {@code first [second]}.
 *
 * <pre>
 *   padding-inline: 4px 8px;           left, right
 *   overflow: hidden auto;             overflow-x, overflow-y
 *   border-top-left-radius: 4px 8px;   horizontal, vertical radius
 *   text-offset: 0 1px;                x, y
 * </pre>
 *
 * @param oneValue what a single value gives {@code second}: null repeats the first value
 *                 ({@code overflow: hidden} is hidden on both axes), otherwise this raw value
 *                 ({@code text-offset: 2px} is {@code 2px 0})
 */
public record PairShorthand(String name, StyleProperty<?> first, StyleProperty<?> second,
                            @Nullable String oneValue) implements Shorthand {

    /** A pair where one value sets both. */
    public PairShorthand(String name, StyleProperty<?> first, StyleProperty<?> second) {
        this(name, first, second, null);
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(first, second);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> v = CssParsingUtil.splitTopLevelSpaces(value);
        if (v.isEmpty() || v.size() > 2) return false;
        String b = v.size() == 2 ? v.get(1) : oneValue != null ? oneValue : v.get(0);
        out.add(Shorthand.declaration(first, v.get(0), important));
        out.add(Shorthand.declaration(second, b, important));
        return true;
    }
}
