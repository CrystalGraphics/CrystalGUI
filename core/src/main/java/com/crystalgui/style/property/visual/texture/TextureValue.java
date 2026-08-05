package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRepeat;
import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.CgUiSpriteRegistry;
import com.crystalgui.render.texture.asset.FileIconTheme;
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
 *   <li>{@code shape("name")} — a vector mark drawn directly ({@link CgUiShape}), no texture: e.g.
 *       {@code "chevron-down"}, {@code "checkmark"}, {@code "triangle-right"}. See {@link
 *       CgUiShape#parseKind} for the full catalog.</li>
 *   <li>{@code icon("namespace:name")} — an {@code .svg} drawn as vectors ({@link CgUiSvg}), from
 *       {@code assets/{namespace}/ui/icons/{name}.svg}. Optional trailing args are type-sniffed the same
 *       way {@code image(...)}'s are: a colour literal is what {@code currentColor} resolves to, and the
 *       keyword {@code monochrome} forces the file's own palette to that colour as well.</li>
 * </ul>
 *
 * <p><b>{@code icon()} and {@code image()} are not two spellings of one thing.</b> An icon is resolution-
 * independent geometry with no texture, no atlas and no bake — the whole point is that it is crisp at any
 * {@code uiScale}, where {@code image()} is a bitmap authored at one size. Reach for {@code image()} when
 * the artwork is genuinely raster.</p>
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

    /** Package-private so the keyword handling can be tested without a GL context, as
     * {@link #parseRepeatPair} already is. */
    static @Nullable CgUiDrawable parseDrawable(String rawValue) {
        String value = rawValue.trim();
        if (value.isEmpty()) return null;

        String lower = value.toLowerCase(Locale.ROOT);
        // `none` is CSS's own spelling for "no layer here"; `empty` is accepted because LDLib2's LSS
        // uses that word and the two dialects otherwise read the same. Both resolve to the shared
        // EMPTY drawable rather than to null — null is how this method reports a PARSE FAILURE, so
        // returning it for a deliberate "nothing" would be indistinguishable from a typo. A fully
        // transparent colour (`#00000000`) also works but still allocates and draws a quad.
        if (lower.equals("none") || lower.equals("empty")) {
            return CgUiDrawable.EMPTY;
        }
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
        if (lower.startsWith("shape(") && value.endsWith(")")) {
            return parseShape(value.substring("shape(".length(), value.length() - 1));
        }
        if (lower.startsWith("icon(") && value.endsWith(")")) {
            return parseIcon(value.substring("icon(".length(), value.length() - 1));
        }
        return null;
    }

    /**
     * {@code icon("ns:name")}, plus {@code image(...)}-style type-sniffed trailing args.
     *
     * <p>Returns null — a parse failure — when the file is missing, rather than an empty drawable. A
     * typo'd icon name and a deliberately absent one are different statements, and {@code none} already
     * spells the second.</p>
     */
    private static @Nullable CgUiDrawable parseIcon(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        String name = unquote(parts.get(0).trim());
        if (name.isEmpty()) return null;

        CgUiSvg icon = CgUiSvg.of(FileIconTheme.toResourcePath(name));
        if (icon == null) return null;

        for (int i = 1; i < parts.size(); i++) {
            String arg = unquote(parts.get(i).trim());
            if (arg.equalsIgnoreCase("monochrome")) {
                icon.setMonochrome(true);
                continue;
            }
            Integer tint = ColorValue.parseColor(arg);
            if (tint != null) {
                icon.setTint(tint);
                continue;
            }
            return null; // unrecognized trailing arg
        }
        return icon;
    }

    private static @Nullable CgUiDrawable parseShape(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 1) return null;
        String name = unquote(parts.get(0).trim());
        CgUiShape.Kind kind = CgUiShape.parseKind(name);
        return kind == null ? null : new CgUiShape(kind);
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
