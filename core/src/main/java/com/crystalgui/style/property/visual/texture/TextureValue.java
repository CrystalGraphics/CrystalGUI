package com.crystalgui.style.property.visual.texture;

import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.api.texture.CgTextureSpec;
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
 *   <li>{@code image("path", tint)} — same, with a fixed multiplicative tint baked in
 *       ({@link CgUiSprite#setTint}) — distinct from {@code background-color}, which layers an
 *       independent fill rather than multiplying the image.</li>
 *   <li>{@code sprite("path", "sx sy sw sh", "bl bt br bb")} — a 9-slice sprite defined directly in
 *       CSS, no asset file needed.</li>
 *   <li>{@code asset("namespace:path")} — named 9-slice lookup via {@link CgUiSpriteRegistry}.</li>
 *   <li>{@code roundedrect(radius, borderWidth, borderColor, fill)} — an SDF rounded rect
 *       ({@link CgUiRoundedRect}); {@code fill} is a color literal or a quoted texture path.</li>
 * </ul>
 */
public class TextureValue extends StyleValue<CgUiDrawable> {

    public TextureValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiDrawable doCompute(String rawValue) {
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
        if (parts.size() != 1 && parts.size() != 2) return null;
        String path = unquote(parts.get(0).trim());
        CgUiSprite sprite = new CgUiSprite().setTexture(path);
        if (parts.size() == 2) {
            Integer tint = ColorValue.parseColor(parts.get(1).trim());
            if (tint == null) return null;
            sprite.setTint(tint);
        }
        return sprite;
    }

    private static @Nullable CgUiDrawable parseRoundedRect(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 4) return null;

        Float radius = parseFloat(parts.get(0).trim());
        Float borderWidth = parseFloat(parts.get(1).trim());
        if (radius == null || borderWidth == null) return null;

        CgUiRoundedRect rect = new CgUiRoundedRect().setCornerRadius(radius);
        if (borderWidth > 0f) {
            Integer borderColor = ColorValue.parseColor(parts.get(2).trim());
            if (borderColor == null) return null;
            rect.setBorder(borderWidth, borderColor);
        }

        String fillRaw = parts.get(3).trim();
        Integer fillColor = ColorValue.parseColor(fillRaw);
        if (fillColor != null) {
            rect.setFillColor(fillColor);
        } else {
            String path = unquote(fillRaw);
            CgTexture2D texture = CgTextureManager.get().getOrCreate(path, CgTextureSpec.RGBA8_NEAREST);
            rect.setFillTexture(texture);
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
        if (parts.size() != 3) return null;
        String path = unquote(parts.get(0).trim());
        int[] spriteRect = parseIntQuad(unquote(parts.get(1).trim()));
        int[] borderRect = parseIntQuad(unquote(parts.get(2).trim()));
        if (spriteRect == null || borderRect == null) return null;
        return new CgUiSprite().setTexture(path)
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

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
