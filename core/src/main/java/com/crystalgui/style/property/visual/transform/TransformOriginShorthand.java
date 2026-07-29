package com.crystalgui.style.property.visual.transform;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleRule;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Parse-time expansion for {@code transform-origin} into the two real longhands
 * ({@code transform-origin-x}, {@code transform-origin-y}) — the same architecture as
 * {@code BoxEdgeShorthands} and {@code OutlineOffsetShorthand}: the longhands are the only
 * independently-cascading properties, and the composite name is pure syntax recognised here.
 *
 * <pre>
 *   transform-origin: 50% 50%     (the default)
 *   transform-origin: 0 0         -> the element's own top-left corner
 *   transform-origin: 10px        -> x only; y stays at its cascaded value's default, 50%
 *   transform-origin: left top    -> keywords
 *   transform-origin: top left    -> the reversed keyword form, which CSS also allows
 *   transform-origin: center      -> 50% 50%
 * </pre>
 *
 * <p>CSS's third (Z) value is not accepted: this engine's transforms are 2D.</p>
 */
public final class TransformOriginShorthand {

    public static final String NAME = "transform-origin";

    /** CSS's default for both axes — the element's centre, so a scale or rotation stays put. */
    public static final LengthPercent CENTER = LengthPercent.percent(0.5f);

    private TransformOriginShorthand() {
    }

    public static boolean isTransformOrigin(String declarationName) {
        return NAME.equals(declarationName);
    }

    public static void expand(List<StyleRule.Declaration> out, String rawValue, boolean important) {
        String[] tokens = rawValue.trim().split("\\s+");
        if (tokens.length == 0 || tokens.length > 2 || tokens[0].isEmpty()) {
            CrystalGuiCore.LOGGER.warn("Invalid {}-value shorthand '{}' for transform-origin — expected 1 or 2 values",
                    tokens.length, rawValue);
            return;
        }

        // `top left` is as valid as `left top` in CSS, so a leading vertical keyword means the pair
        // arrived reversed. Only keywords can do this — two lengths are always x-then-y.
        boolean reversed = tokens.length == 2 && isVerticalKeyword(tokens[0]);
        String rawX = reversed ? tokens[1] : tokens[0];
        String rawY = tokens.length == 2 ? (reversed ? tokens[0] : tokens[1]) : null;

        LengthPercent x = resolve(rawX, false);
        LengthPercent y = rawY == null ? null : resolve(rawY, true);
        if (x == null || (rawY != null && y == null)) {
            CrystalGuiCore.LOGGER.warn("Unparseable transform-origin value '{}' — expected a length, a "
                    + "percentage, or one of left/center/right/top/bottom", rawValue);
            return;
        }

        out.add(declaration(StylePropertyRegistry.TRANSFORM_ORIGIN_X, x, important));
        // A one-value form leaves the other axis alone rather than forcing it back to 50%, so
        // `transform-origin-y: 0` in an earlier rule survives a later `transform-origin: 10px`.
        if (y != null) {
            out.add(declaration(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, y, important));
        }
    }

    private static boolean isVerticalKeyword(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("top") || lower.equals("bottom");
    }

    /** @param vertical which axis's keyword set to accept; {@code center} is valid on both. */
    private static @Nullable LengthPercent resolve(String token, boolean vertical) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "center":
                return CENTER;
            case "left":
                return vertical ? null : LengthPercent.percent(0f);
            case "right":
                return vertical ? null : LengthPercent.percent(1f);
            case "top":
                return vertical ? LengthPercent.percent(0f) : null;
            case "bottom":
                return vertical ? LengthPercent.percent(1f) : null;
            default:
                return LengthPercent.parse(token);
        }
    }

    private static StyleRule.Declaration declaration(StyleProperty<LengthPercent> property,
                                                     LengthPercent value, boolean important) {
        // The value is already resolved (keywords have no textual LengthPercent form), so re-parse its
        // canonical toString rather than the author's token.
        return new StyleRule.Declaration(property, property.valueParser.parse(value.toString()), important);
    }

    /** Whether {@code transition: transform-origin ...} should animate {@code longhand}. */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        return NAME.equals(transitionPropertyName) && (
                longhand == StylePropertyRegistry.TRANSFORM_ORIGIN_X
                        || longhand == StylePropertyRegistry.TRANSFORM_ORIGIN_Y
        );
    }
}
