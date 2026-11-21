package com.crystalgui.style.property.visual;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.border.BorderShorthand;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

import static com.crystalgui.style.property.StylePropertyRegistry.*;

/**
 * CSS {@code outline: <width> || <style> || <color>}, and ours: an image drawn as the outline.
 *
 * <pre>
 *   outline: 2px solid #4488ff;                        a stroke ring that follows border-radius
 *   outline: 1px #4488ff;                              the same; the style's initial is solid here
 *   outline: none;                                     no outline
 *   outline: asset("crystalgui:ore", "focus-ring");    a 9-slice ring image instead of a stroke
 * </pre>
 *
 * <p>Every form resets the parts it omits: a stroke clears the image, an image resets the stroke. An
 * omitted width is {@code medium}, 3px, and an omitted colour {@code currentcolor}, as in CSS.</p>
 */
public final class OutlineShorthand implements Shorthand {

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(OUTLINE, OUTLINE_WIDTH, OUTLINE_STYLE, OUTLINE_COLOR);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> tokens = CssParsingUtil.splitTopLevelSpaces(value);
        if (tokens.size() == 1 && tokens.get(0).contains("(") && !ColorValue.isColor(tokens.get(0))) {
            out.add(Shorthand.declaration(OUTLINE, tokens.get(0), important));
            out.add(Shorthand.initial(OUTLINE_WIDTH, important));
            out.add(Shorthand.initial(OUTLINE_STYLE, important));
            out.add(Shorthand.initial(OUTLINE_COLOR, important));
            return true;
        }
        BorderShorthand.Line line = BorderShorthand.Line.parse(value, OutlineStyle.class);
        if (line == null) return false;
        out.add(Shorthand.initial(OUTLINE, important));
        out.add(Shorthand.declaration(OUTLINE_WIDTH, line.width() != null ? line.width() : "3px", important));
        out.add(line.style() != null ? Shorthand.declaration(OUTLINE_STYLE, line.style(), important)
                : Shorthand.initial(OUTLINE_STYLE, important));
        out.add(line.color() != null ? Shorthand.declaration(OUTLINE_COLOR, line.color(), important)
                : Shorthand.initial(OUTLINE_COLOR, important));
        return true;
    }
}
