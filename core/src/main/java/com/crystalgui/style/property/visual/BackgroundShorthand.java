package com.crystalgui.style.property.visual;

import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.Shorthand;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;

import java.util.ArrayList;
import java.util.List;

/**
 * CSS {@code background}: image layers and/or a colour, every omitted part reset.
 *
 * <pre>
 *   background: #2E3540;                              colour, no image
 *   background: asset("crystalgui:ore", "button");    image, transparent colour
 *   background: linear-gradient(#000, #fff) #222;     both, in either order
 *   background: radial-gradient(...), linear-gradient(...) #222;   layers, first on top; the colour in the last
 *   background: none;                                 neither
 * </pre>
 *
 * <p>Also resets {@code background-tint}, which is ours, so a rule writing {@code background} states the
 * whole background. A colour paints under the image, as in CSS; to recolour the image itself, write
 * {@code background-tint}.</p>
 */
public final class BackgroundShorthand implements Shorthand {

    @Override
    public String name() {
        return "background";
    }

    @Override
    public List<StyleProperty<?>> longhands() {
        return List.of(StylePropertyRegistry.BACKGROUND_IMAGE, StylePropertyRegistry.BACKGROUND_COLOR,
                StylePropertyRegistry.BACKGROUND_TINT);
    }

    @Override
    public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
        List<String> layers = CssParsingUtil.splitTopLevelCommas(value);
        // Only the last layer may carry the colour, as CSS's grammar has it.
        List<String> last = CssParsingUtil.splitTopLevelSpaces(layers.get(layers.size() - 1));
        String color = null;
        List<String> lastImage = new ArrayList<>();
        for (String token : last) {
            if (color == null && ColorValue.parseCssColor(token) != null) color = token;
            else lastImage.add(token);
        }
        if (lastImage.size() > 1) return false;
        List<String> images = new ArrayList<>();
        for (int i = 0; i < layers.size() - 1; i++) images.add(layers.get(i).trim());
        if (!lastImage.isEmpty()) images.add(lastImage.get(0));
        if (images.isEmpty() && color == null) return false;
        if (images.stream().anyMatch(String::isEmpty)) return false;

        out.add(images.isEmpty() ? Shorthand.initial(StylePropertyRegistry.BACKGROUND_IMAGE, important)
                : Shorthand.declaration(StylePropertyRegistry.BACKGROUND_IMAGE, String.join(", ", images), important));
        out.add(color == null ? Shorthand.initial(StylePropertyRegistry.BACKGROUND_COLOR, important)
                : Shorthand.declaration(StylePropertyRegistry.BACKGROUND_COLOR, color, important));
        out.add(Shorthand.initial(StylePropertyRegistry.BACKGROUND_TINT, important));
        return true;
    }
}
