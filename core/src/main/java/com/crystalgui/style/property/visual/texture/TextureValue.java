package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRepeat;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.render.texture.asset.CgUiSpriteRegistry;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.color.ColorValue;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Parses a {@code background} value. Grammar (each form is distinct, not four ways to do the
 * same thing) — every form is an explicit function call, no implicit/bare-path form:
 * <ul>
 *   <li>{@code #RRGGBB} / {@code #RGB} / {@code #RRGGBBAA} / {@code rgb(...)} / {@code rgba(...)}
 *       — a flat {@link CgUiQuad}, via {@link ColorValue#parseColor}.</li>
 *   <li>{@code image("path")} — a single un-sliced full-texture {@link CgUiSprite}.</li>
 *   <li>{@code image("path", ...)} — accepts any mix of optional trailing args, type-sniffed
 *       (order-independent): a quoted {@code "x y w h"} crop sub-rect ({@link CgUiSprite#setSprite}),
 *       a quoted {@code "refW refH"} texture-size-reference override
 *       ({@link CgUiSprite#setTextureSizeReference}), or a color literal for a fixed multiplicative
 *       tint baked in ({@link CgUiSprite#setTint}) — distinct from {@code background-color}, which
 *       layers an independent fill rather than multiplying the image.</li>
 *   <li>{@code sprite("path", "sx sy sw sh", "bl bt br bb")} — a 9-slice sprite defined directly in
 *       CSS, no asset file needed. An optional 4th {@code "refW refH"} arg overrides the texture-size
 *       reference the same way as {@code image(...)}.</li>
 *   <li>{@code asset("namespace:path", "element")} — named 9-slice lookup via {@link CgUiSpriteRegistry}.</li>
 * </ul>
 *
 * <p>Rounding/border is a separate, universal wrapping layer ({@code border-radius}/
 * {@code border-width}/{@code border-color}), not a {@code background:} value type — it applies on
 * top of whatever {@code background:} resolves to (see {@code UIElement.paintSelf}).</p>
 */
public class TextureValue extends StyleValue<CgUiDrawable> {

    public TextureValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiDrawable doCompute(String rawValue) {
        return parseDrawable(rawValue);
    }

    private static @Nullable CgUiDrawable parseDrawable(String rawValue) {
        String value = rawValue.trim();
        if (value.isEmpty()) return null;

        String lower = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("#") || lower.startsWith("rgb(") || lower.startsWith("rgba(")) {
            Integer color = ColorValue.parseColor(value);
            return color == null ? null : new CgUiQuad(color);
        }
        if (lower.startsWith("image(") && value.endsWith(")")) {
            return parseImage(value.substring("image(".length(), value.length() - 1));
        }
        if (lower.startsWith("sprite(") && value.endsWith(")")) {
            return parseSprite(value.substring("sprite(".length(), value.length() - 1));
        }
        if (lower.startsWith("asset(") && value.endsWith(")")) {
            return parseAsset(value.substring("asset(".length(), value.length() - 1));
        }
        return null;
    }

    private static @Nullable CgUiDrawable parseImage(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        String path = unquote(parts.get(0).trim());
        CgUiSprite sprite = new CgUiSprite().setTexture(path);

        for (int i = 1; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());

            int[] cropRect = parseIntQuad(arg);
            if (cropRect != null) {
                sprite.setSprite(cropRect[0], cropRect[1], cropRect[2], cropRect[3]);
                continue;
            }
            int[] refSize = parseIntPair(arg);
            if (refSize != null) {
                sprite.setTextureSizeReference(refSize[0], refSize[1]);
                continue;
            }
            Integer tint = ColorValue.parseColor(arg);
            if (tint != null) {
                sprite.setTint(tint);
                continue;
            }
            return null; // unrecognized trailing arg
        }
        return sprite;
    }

    private static @Nullable CgUiDrawable parseSprite(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() < 3) return null;
        String path = unquote(parts.get(0).trim());
        int[] spriteRect = parseIntQuad(unquote(parts.get(1).trim()));
        int[] borderRect = parseIntQuad(unquote(parts.get(2).trim()));
        if (spriteRect == null || borderRect == null) return null;

        CgUiSprite sprite = new CgUiSprite().setTexture(path);
        // Trailing args are order-independent and type-sniffed, matching image()'s existing style:
        // an int pair is the texture-size reference, a keyword (or keyword pair) is the tiling mode.
        for (int i = 3; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());

            int[] refSize = parseIntPair(arg);
            if (refSize != null) {
                sprite.setTextureSizeReference(refSize[0], refSize[1]);
                continue;
            }
            CgUiRepeat[] repeat = parseRepeatPair(arg);
            if (repeat != null) {
                sprite.setRepeat(repeat[0], repeat[1]);
                continue;
            }
            return null; // unrecognized trailing arg
        }
        return sprite
                .setSprite(spriteRect[0], spriteRect[1], spriteRect[2], spriteRect[3])
                .setBorder(borderRect[0], borderRect[1], borderRect[2], borderRect[3]);
    }

    /** {@code "repeat"} or {@code "repeat round"} — CSS {@code border-image-repeat}'s 1-or-2 value
     * form, second axis defaulting to the first. {@code null} when the token isn't a repeat keyword
     * at all, which is what lets the caller sniff argument types. */
    static @Nullable CgUiRepeat[] parseRepeatPair(String raw) {
        if (raw == null) return null;
        String[] words = raw.trim().split("\\s+");
        if (words.length == 0 || words.length > 2) return null;
        CgUiRepeat x = CgUiRepeat.parse(words[0]);
        if (x == null) return null;
        CgUiRepeat y = words.length == 2 ? CgUiRepeat.parse(words[1]) : x;
        if (y == null) return null;
        return new CgUiRepeat[]{x, y};
    }

    private static @Nullable CgUiDrawable parseAsset(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 2) return null;
        String packPath = unquote(parts.get(0).trim());
        String elementName = unquote(parts.get(1).trim());
        if (packPath.isEmpty() || elementName.isEmpty()) return null;
        return CgUiSpriteRegistry.get(packPath, elementName);
    }

    private static @Nullable int[] parseIntQuad(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length != 4) return null;
        try {
            return new int[]{
                    Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]),
                    Integer.parseInt(tokens[2]), Integer.parseInt(tokens[3])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable int[] parseIntPair(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
