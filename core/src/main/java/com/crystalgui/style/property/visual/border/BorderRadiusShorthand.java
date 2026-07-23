package com.crystalgui.style.property.visual.border;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

/**
 * Parse-time expansion for the {@code border-radius} shorthand into the 8 real longhands in
 * {@link BorderRadiusProperties} — mirrors {@code BoxEdgeShorthands}'s architecture (edge-based
 * there, corner-based here). Real CSS syntax: {@code border-radius: <h-list> [ / <v-list> ]},
 * each list a 1/2/3/4-value TL/TR/BR/BL corner shorthand. No {@code /}: both axes share the same
 * list (a plain {@code border-radius: 10px} means circular corners, rx == ry).
 */
public final class BorderRadiusShorthand {
    public static final String NAME = "border-radius";

    private BorderRadiusShorthand() {
    }

    public static boolean isBorderRadius(String declarationName) {
        return NAME.equals(declarationName);
    }

    public static void expand(List<StyleRule.Declaration> out, String rawValue, boolean important) {
        String[] sides = rawValue.split("/", 2);
        String[] horiz = resolveCorners(sides[0].trim(), "border-radius (horizontal)");
        String[] vert = sides.length == 2 ? resolveCorners(sides[1].trim(), "border-radius (vertical)") : horiz;
        if (horiz == null || vert == null) return;

        out.add(cornerDeclaration(BorderRadiusProperties.TOP_LEFT_X, horiz[0], important));
        out.add(cornerDeclaration(BorderRadiusProperties.TOP_LEFT_Y, vert[0], important));
        out.add(cornerDeclaration(BorderRadiusProperties.TOP_RIGHT_X, horiz[1], important));
        out.add(cornerDeclaration(BorderRadiusProperties.TOP_RIGHT_Y, vert[1], important));
        out.add(cornerDeclaration(BorderRadiusProperties.BOTTOM_RIGHT_X, horiz[2], important));
        out.add(cornerDeclaration(BorderRadiusProperties.BOTTOM_RIGHT_Y, vert[2], important));
        out.add(cornerDeclaration(BorderRadiusProperties.BOTTOM_LEFT_X, horiz[3], important));
        out.add(cornerDeclaration(BorderRadiusProperties.BOTTOM_LEFT_Y, vert[3], important));
    }

    /** @return [topLeft, topRight, bottomRight, bottomLeft] raw tokens, or {@code null} on a bad value count. */
    private static String[] resolveCorners(String rawList, String context) {
        String[] tokens = rawList.split("\\s+");
        String tl, tr, br, bl;
        switch (tokens.length) {
            case 1 -> { tl = tr = br = bl = tokens[0]; }
            case 2 -> { tl = br = tokens[0]; tr = bl = tokens[1]; }
            case 3 -> { tl = tokens[0]; tr = bl = tokens[1]; br = tokens[2]; }
            case 4 -> { tl = tokens[0]; tr = tokens[1]; br = tokens[2]; bl = tokens[3]; }
            default -> {
                CrystalGuiCore.LOGGER.warn("Invalid {}-value shorthand '{}' for {} — expected 1-4 values",
                        tokens.length, rawList, context);
                return null;
            }
        }
        return new String[]{tl, tr, br, bl};
    }

    private static StyleRule.Declaration cornerDeclaration(StyleProperty<LengthPercent> property,
                                                            String rawValue, boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(rawValue), important);
    }

    /** Whether {@code transition: border-radius ...} should animate {@code longhand}. */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        return NAME.equals(transitionPropertyName) && (
                longhand == BorderRadiusProperties.TOP_LEFT_X || longhand == BorderRadiusProperties.TOP_LEFT_Y
                || longhand == BorderRadiusProperties.TOP_RIGHT_X || longhand == BorderRadiusProperties.TOP_RIGHT_Y
                || longhand == BorderRadiusProperties.BOTTOM_RIGHT_X || longhand == BorderRadiusProperties.BOTTOM_RIGHT_Y
                || longhand == BorderRadiusProperties.BOTTOM_LEFT_X || longhand == BorderRadiusProperties.BOTTOM_LEFT_Y
        );
    }
}
