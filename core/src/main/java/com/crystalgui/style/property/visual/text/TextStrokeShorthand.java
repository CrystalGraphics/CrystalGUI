package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

import static com.crystalgui.style.property.StylePropertyRegistry.TEXT_STROKE_COLOR;
import static com.crystalgui.style.property.StylePropertyRegistry.TEXT_STROKE_WIDTH;

/**
 * {@code text-stroke: <width> || <color>}, the web's {@code -webkit-text-stroke} without the prefix.
 *
 * <pre>
 *   text-stroke: 2px #0B5D8F;              width and colour
 *   text-stroke: 0.04em rgb(11, 93, 143);  em is a fraction of the font size
 *   text-stroke: 2px;                      colour back to currentcolor
 *   text-stroke: none;                     no stroke
 * </pre>
 *
 * <p>Like every shorthand it resets what it omits; to change only the width, write
 * {@code text-stroke-width}. {@code stroke-align} and {@code paint-order} are separate properties.</p>
 */
public final class TextStrokeShorthand implements Shorthand {

    @Override
    public String name() {
        return "text-stroke";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(TEXT_STROKE_WIDTH, TEXT_STROKE_COLOR);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        String width = null, color = null;
        List<String> tokens = CssParsingUtil.splitTopLevelSpaces(value);
        if (tokens.size() == 1 && tokens.get(0).equalsIgnoreCase("none")) {
            width = "0";
        } else {
            for (String token : tokens) {
                if (color == null && ColorValue.isColor(token)) color = token;
                else if (width == null && CssLength.parse(token) != null) width = token;
                else return false;
            }
        }
        out.add(width != null ? Shorthand.declaration(TEXT_STROKE_WIDTH, width, important)
                : Shorthand.initial(TEXT_STROKE_WIDTH, important));
        out.add(color != null ? Shorthand.declaration(TEXT_STROKE_COLOR, color, important)
                : Shorthand.initial(TEXT_STROKE_COLOR, important));
        return true;
    }
}
