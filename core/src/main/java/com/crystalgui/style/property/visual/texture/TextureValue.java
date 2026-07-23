package com.crystalgui.style.property.visual.texture;

import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRoundedRect;
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
 *   <li>{@code asset("namespace:path")} — named 9-slice lookup via {@link CgUiSpriteRegistry}.</li>
 *   <li>{@code roundedrect(radius, borderWidth, borderColor, fill)} — an SDF rounded rect
 *       ({@link CgUiRoundedRect}). {@code radius} is either a bare number (uniform, all corners) or
 *       a quoted 4-number {@code "topLeft topRight bottomRight bottomLeft"} list — CSS
 *       {@code border-radius} order — for independent per-corner rounding (e.g. {@code 0} on two
 *       corners for a square edge). {@code fill} reuses this same grammar recursively (any form
 *       above producing a {@link CgUiQuad} or {@link CgUiSprite} is accepted) — so a rounded rect can
 *       be filled with e.g. a cropped/tinted {@code image(...)}, not just a bare color or path.</li>
 * </ul>
 */
public class TextureValue extends StyleValue<CgUiDrawable> {

    public TextureValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiDrawable doCompute(String rawValue) {
        return parseDrawable(rawValue);
    }

    /** The full {@code background:} grammar dispatch, exposed so {@link #parseRoundedRect} can reuse
     * it for its {@code fill} argument instead of duplicating a narrower subset. */
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
        if (lower.startsWith("roundedrect(") && value.endsWith(")")) {
            return parseRoundedRect(value.substring("roundedrect(".length(), value.length() - 1));
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

    private static @Nullable CgUiDrawable parseRoundedRect(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 4) return null;

        String radiusRaw = unquote(parts.get(0).trim());
        Float borderWidth = parseFloat(parts.get(1).trim());
        if (borderWidth == null) return null;

        CgUiRoundedRect rect = new CgUiRoundedRect();
        Float uniformRadius = parseFloat(radiusRaw);
        if (uniformRadius != null) {
            rect.setCornerRadius(uniformRadius);
        } else {
            float[] corners = parseFloatQuad(radiusRaw);
            if (corners == null) return null;
            rect.setCornerRadius(corners[0], corners[1], corners[2], corners[3]);
        }

        if (borderWidth > 0f) {
            Integer borderColor = ColorValue.parseColor(parts.get(2).trim());
            if (borderColor == null) return null;
            rect.setBorder(borderWidth, borderColor);
        }

        CgUiDrawable fillDrawable = parseDrawable(parts.get(3).trim());
        if (fillDrawable instanceof CgUiQuad quad) {
            rect.setFillColor(quad.getColorArgb());
        } else if (fillDrawable instanceof CgUiSprite sprite) {
            CgTexture2D texture = sprite.getTexture();
            if (texture == null) return null;
            rect.setFillTexture(texture);
        } else {
            return null; // e.g. a nested roundedrect() as fill, or a failed asset() lookup — no mapping
        }
        return rect;
    }

    private static @Nullable Float parseFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable CgUiDrawable parseSprite(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 3 && parts.size() != 4) return null;
        String path = unquote(parts.get(0).trim());
        int[] spriteRect = parseIntQuad(unquote(parts.get(1).trim()));
        int[] borderRect = parseIntQuad(unquote(parts.get(2).trim()));
        if (spriteRect == null || borderRect == null) return null;

        CgUiSprite sprite = new CgUiSprite().setTexture(path);
        if (parts.size() == 4) {
            int[] refSize = parseIntPair(unquote(parts.get(3).trim()));
            if (refSize == null) return null;
            sprite.setTextureSizeReference(refSize[0], refSize[1]);
        }
        return sprite
                .setSprite(spriteRect[0], spriteRect[1], spriteRect[2], spriteRect[3])
                .setBorder(borderRect[0], borderRect[1], borderRect[2], borderRect[3]);
    }

    private static @Nullable CgUiDrawable parseAsset(String args) {
        String path = unquote(args.trim());
        if (path.isEmpty()) return null;
        return CgUiSpriteRegistry.get(path);
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

    private static @Nullable float[] parseFloatQuad(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length != 4) return null;
        try {
            return new float[]{
                    Float.parseFloat(tokens[0]), Float.parseFloat(tokens[1]),
                    Float.parseFloat(tokens[2]), Float.parseFloat(tokens[3])
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
