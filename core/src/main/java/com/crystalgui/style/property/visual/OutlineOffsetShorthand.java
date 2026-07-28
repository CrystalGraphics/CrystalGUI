package com.crystalgui.style.property.visual;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

/**
 * Parse-time expansion for {@code outline-offset} into the four real per-edge longhands in
 * {@link StylePropertyRegistry} — same architecture as {@code BoxEdgeShorthands} (margin/padding)
 * and {@link com.crystalgui.style.property.visual.border.BorderRadiusShorthand} (border-radius):
 * the longhands are the only independently-cascading properties, and the composite name is pure
 * syntax recognised here.
 *
 * <h3>Why per-edge at all — CSS's is a single scalar</h3>
 * <p>A focus ring drawn from a 9-slice sprite has to hug whatever the widget's own sprite actually
 * shows, and sprites are not obliged to be symmetric. Ore's selected tab (<code>tab-on</code>) keeps
 * two fully transparent texel rows along its top edge — that is what makes a selected tab appear
 * raised without changing its height — so its border box starts two pixels above anything visible.
 * A single-scalar offset can only fix that by pulling all four edges in, which then leaves the other
 * three inset by two pixels. One value per edge is the smallest thing that expresses "tighten this
 * edge only".</p>
 *
 * <p>Value order follows CSS's clockwise-from-top convention, the same as {@code margin}:</p>
 * <pre>
 *   outline-offset: 2px                 -> all four
 *   outline-offset: 2px 4px             -> top/bottom, left/right
 *   outline-offset: 2px 4px 6px         -> top, left/right, bottom
 *   outline-offset: -2px 0 0 0          -> top, right, bottom, left
 * </pre>
 */
public final class OutlineOffsetShorthand {

    public static final String NAME = "outline-offset";

    private OutlineOffsetShorthand() {
    }

    public static boolean isOutlineOffset(String declarationName) {
        return NAME.equals(declarationName);
    }

    public static void expand(List<StyleRule.Declaration> out, String rawValue, boolean important) {
        String[] tokens = rawValue.trim().split("\\s+");
        String top, right, bottom, left;
        switch (tokens.length) {
            case 1 -> { top = right = bottom = left = tokens[0]; }
            case 2 -> { top = bottom = tokens[0]; right = left = tokens[1]; }
            case 3 -> { top = tokens[0]; right = left = tokens[1]; bottom = tokens[2]; }
            case 4 -> { top = tokens[0]; right = tokens[1]; bottom = tokens[2]; left = tokens[3]; }
            default -> {
                CrystalGuiCore.LOGGER.warn("Invalid {}-value shorthand '{}' for outline-offset — expected 1-4 values",
                        tokens.length, rawValue);
                return;
            }
        }
        out.add(edge(StylePropertyRegistry.OUTLINE_OFFSET_TOP, top, important));
        out.add(edge(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT, right, important));
        out.add(edge(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM, bottom, important));
        out.add(edge(StylePropertyRegistry.OUTLINE_OFFSET_LEFT, left, important));
    }

    private static StyleRule.Declaration edge(StyleProperty<LengthPercent> property,
                                              String rawValue, boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(rawValue), important);
    }

    /** Whether {@code transition: outline-offset ...} should animate {@code longhand}. */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        return NAME.equals(transitionPropertyName) && (
                longhand == StylePropertyRegistry.OUTLINE_OFFSET_TOP
                        || longhand == StylePropertyRegistry.OUTLINE_OFFSET_RIGHT
                        || longhand == StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM
                        || longhand == StylePropertyRegistry.OUTLINE_OFFSET_LEFT
        );
    }
}
