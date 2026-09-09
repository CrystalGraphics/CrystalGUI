package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.render.texture.CgUiGrid;
import com.crystalgui.render.texture.CgUiLayers;
import com.crystalgui.render.texture.CgUiRect;
import com.crystalgui.render.texture.CgUiRepeat;
import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.CgUiSpriteRegistry;
import com.crystalgui.style.CssAngle;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.color.ColorValue;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Parses a {@code background} value. Grammar (each form is distinct, not four ways to do the
 * same thing) — every form is an explicit function call, no implicit/bare-path form:
 * <ul>
 *   <li>{@code #RRGGBB} / {@code #RGB} / {@code #RRGGBBAA} / {@code rgb(...)} / {@code rgba(...)}
 *       — a flat {@link CgUiRect}, via {@link ColorValue#parseColor}.</li>
 *   <li>{@code image("path")} — a rect filled with a single un-sliced full-texture {@link CgUiSprite}.</li>
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
        CgUiDrawable drawable = parseDrawable(rawValue);
        if (drawable != null) remember(drawable, rawValue.trim());
        return drawable;
    }

    /**
     * What CSS produced a drawable, so {@code TextureProperty} can write it back.
     *
     * <p><b>Beside the drawable rather than on it</b>, and the reason is ownership: a drawable is a
     * painter, and which stylesheet text happened to make one is not a fact about painting. Several of
     * them are shared besides — {@code CgUiSpriteRegistry} hands the same sprite to every rule that asks
     * for it — so a field would have to be written by whoever parsed last and would not mean anything
     * for a sprite built in Java.</p>
     *
     * <p>That sharing is also why this is safe. Two spellings can reach one drawable ({@code asset(...)}
     * and {@code sprite(...)} of the same pack), and the map then answers with whichever was parsed
     * last — which parses back to the same drawable, so the value survives even though the text may not
     * be the one originally typed. Values are what has to round-trip; spelling is not.</p>
     *
     * <p>Weak keys: an entry lives exactly as long as the drawable does, so a stylesheet reloaded a
     * hundred times leaves nothing behind.</p>
     */
    private static final Map<CgUiDrawable, String> SOURCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void remember(CgUiDrawable drawable, String source) {
        // NEVER the shared EMPTY, which every `none` in every sheet resolves to: one entry would be
        // overwritten by the next and it has a spelling of its own anyway. @see #sourceOf
        if (drawable == CgUiDrawable.EMPTY) return;
        // RE-KEYED, not updated in place. `put` on an equal key keeps the EXISTING key, so a drawable
        // whose equality is value-based -- a record -- leaves the map holding whichever equal instance
        // was seen first, which is often a temporary, and the entry dies with that one while the live
        // drawable is still on screen. Removing first makes the live instance the key.
        //
        // It NARROWS the window rather than closing it, and cannot close it: two equal drawables can
        // only ever have one entry between them. The rule that actually holds is on the other side --
        // a drawable with value equality must be able to write itself. @see TextureProperty#write
        SOURCES.remove(drawable);
        SOURCES.put(drawable, source);
    }

    /** The CSS that produced {@code drawable}, or null when nothing parsed it. @see #SOURCES */
    @Nullable
    public static String sourceOf(CgUiDrawable drawable) {
        return SOURCES.get(drawable);
    }

    /** Package-private so the keyword handling can be tested without a GL context, as
     * {@link #parseRepeatPair} already is. */
    static @Nullable CgUiDrawable parseDrawable(String rawValue) {
        String value = rawValue.trim();
        if (value.isEmpty()) return null;

        // LAYERS, comma-separated, as CSS writes a background. The split is paren-aware, so a lone
        // `linear-gradient(to bottom, a, b)` is one layer and not three.
        List<String> parts = CssParsingUtil.splitTopLevelCommas(value);
        if (parts.size() > 1) {
            List<CgUiDrawable> layers = new ArrayList<>(parts.size());
            for (String part : parts) {
                String text = part.trim();
                CgUiDrawable layer = parseLayer(text);
                // ONE BAD LAYER FAILS THE DECLARATION rather than being dropped: a stack missing a layer
                // still renders, and renders something the author did not write.
                if (layer == null) return null;
                // EACH LAYER REMEMBERS ITS OWN TEXT, not just the stack's. Only the whole declaration is
                // remembered by doCompute, and a layer nobody can write is a layer Copy Attributes
                // cannot offer on its own. @see #sourceOf
                remember(layer, text);
                layers.add(layer);
            }
            return new CgUiLayers(layers);
        }
        return parseLayer(value);
    }

    /** One layer: a keyword, or a registered function. */
    @Nullable
    private static CgUiDrawable parseLayer(String value) {
        // `none` is CSS's own spelling for "no layer here"; `empty` is accepted because LDLib2's LSS
        // uses that word and the two dialects otherwise read the same. Both resolve to the shared
        // EMPTY drawable rather than to null — null is how this method reports a PARSE FAILURE, so
        // returning it for a deliberate "nothing" would be indistinguishable from a typo. A fully
        // transparent colour (`#00000000`) also works but still allocates and draws a quad.
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("empty")) {
            return CgUiDrawable.EMPTY;
        }
        // EVERY OTHER FORM IS A REGISTERED KIND. This was a chain of nine `startsWith` branches, and the
        // same nine were written out again wherever a copied drawable had to be filed by which function
        // made it — so a tenth added here and not there rendered correctly and quietly stopped being
        // recognised. @see DrawableKinds
        return DrawableKinds.parse(value);
    }

    /**
     * {@code grid(<cell>, <colour>[, <line-width>])} — a ruled grid, drawn analytically.
     *
     * <pre>
     *   grid(16, #6EDCD024)          16px cells, 1px lines
     *   grid(16, #6EDCD024, 2)       2px lines
     *   grid(16 24, #6EDCD024)       non-square cells: x then y
     * </pre>
     *
     * <p>Every length is in logical pixels and {@code px} is accepted on any of them. The cell is one
     * number for a square grid or two separated by a space, which is the only place this grammar
     * departs from a comma-separated CSS function — a comma there would be ambiguous against the
     * colour that follows.</p>
     *
     * <p>A missing or unparseable cell, colour or width is a parse failure (null), exactly as a
     * malformed gradient stop is. There is deliberately no default colour: a grid nobody can see is
     * indistinguishable from one that failed to parse.</p>
     */
    static @Nullable CgUiDrawable parseGrid(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() < 2 || parts.size() > 3) return null;

        String[] cells = parts.get(0).trim().split("\s+");
        if (cells.length < 1 || cells.length > 2) return null;
        Float cellX = parseFloatOrNull(cells[0]);
        Float cellY = cells.length == 2 ? parseFloatOrNull(cells[1]) : cellX;
        if (cellX == null || cellY == null || cellX <= 0f || cellY <= 0f) return null;

        Integer color = ColorValue.parseColor(parts.get(1).trim());
        if (color == null) return null;

        float lineWidth = 1f;
        if (parts.size() == 3) {
            Float parsed = parseFloatOrNull(parts.get(2).trim());
            if (parsed == null || parsed < 0f) return null;
            lineWidth = parsed;
        }
        return new CgUiGrid(cellX, cellY, lineWidth, color);
    }

    /**
     * {@code linear-gradient(...)} — CSS's, at any angle:
     *
     * <pre>
     *   linear-gradient(#000, #FFF)                            to bottom, evenly spread
     *   linear-gradient(90deg, transparent, #3574F033 50%, transparent)
     *   linear-gradient(0.25turn, red 20%, blue 80%)
     *   linear-gradient(to right, red, blue)
     *   linear-gradient(to bottom right, red, blue)            resolved per box, @see CgUiGradient
     * </pre>
     *
     * <p>A leading angle ({@code deg}/{@code grad}/{@code rad}/{@code turn}), {@code to <side>} or
     * {@code to <corner>} is the direction; everything after it is a stop —
     * a colour with an optional {@code <n>%}. Fewer than two stops, or a colour that does not parse, is a
     * parse failure (null), exactly as a malformed gradient stop is. The position is read
     * off the END of the stop rather than by splitting on whitespace, because an {@code rgba(...)} colour
     * may carry spaces of its own.</p>
     */
    static @Nullable CgUiDrawable parseLinearGradient(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        float angle = 180f;   // CSS's default: to bottom
        CgUiGradient.Corner corner = null;
        int first = 0;
        String head = parts.get(0).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("to ")) {
            // `to <side>`, or `to <corner>` as two words in either order. A corner is kept as one
            // rather than resolved to an angle here, because its angle depends on the box it is drawn on.
            String[] words = head.substring(3).trim().split("\\s+");
            boolean top = false, bottom = false, left = false, right = false;
            for (String word : words) {
                switch (word) {
                    case "top" -> top = true;
                    case "bottom" -> bottom = true;
                    case "left" -> left = true;
                    case "right" -> right = true;
                    default -> { return null; }
                }
            }
            if (words.length > 2 || (top && bottom) || (left && right)) return null;
            if (words.length == 2) {
                if (!(top || bottom) || !(left || right)) return null;
                corner = top ? (left ? CgUiGradient.Corner.TOP_LEFT : CgUiGradient.Corner.TOP_RIGHT)
                             : (left ? CgUiGradient.Corner.BOTTOM_LEFT : CgUiGradient.Corner.BOTTOM_RIGHT);
            } else {
                angle = top ? 0f : right ? 90f : bottom ? 180f : 270f;
            }
            first = 1;
        } else {
            // deg, grad, rad, turn -- the same parser `rotate()` uses. A colour is not an angle and
            // falls through as the first stop.
            Float radians = CssAngle.parse(head);
            if (radians != null) {
                angle = (float) Math.toDegrees(radians);
                first = 1;
            }
        }
        List<CgUiGradient.Stop> stops = new ArrayList<>();
        for (int i = first; i < parts.size(); i++) {
            String stop = parts.get(i).trim();
            float position = Float.NaN;
            int lastSpace = stop.lastIndexOf(' ');
            if (lastSpace > 0 && stop.endsWith("%")) {
                Float pct = parseFloatOrNull(stop.substring(lastSpace + 1, stop.length() - 1).trim());
                if (pct == null) return null;
                position = pct / 100f;
                stop = stop.substring(0, lastSpace).trim();
            }
            Integer color = ColorValue.parseColor(stop);
            if (color == null) return null;
            stops.add(new CgUiGradient.Stop(position, color));
        }
        if (stops.size() < 2) return null;
        return corner != null ? new CgUiGradient(corner, stops) : new CgUiGradient(angle, stops);
    }

    private static @Nullable Float parseFloatOrNull(String raw) {
        try {
            return Float.parseFloat(raw.endsWith("px") ? raw.substring(0, raw.length() - 2) : raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code icon("ns:name")}, plus {@code image(...)}-style type-sniffed trailing args.
     *
     * <p>Returns null — a parse failure — when the file is missing, rather than an empty drawable. A
     * typo'd icon name and a deliberately absent one are different statements, and {@code none} already
     * spells the second.</p>
     */
    static @Nullable CgUiDrawable parseIcon(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.isEmpty()) return null;
        String name = unquote(parts.get(0).trim());
        if (name.isEmpty()) return null;

        // ofIcon, NOT of(toResourcePath(...)). The two differ by the light/dark variant, and this took the
        // path that skips it -- so every `icon()` in every stylesheet drew the LIGHT file forever, and a
        // theme swap changed nothing. It went unnoticed because the icons that existed when this was
        // written are `currentColor` chrome marks with no dark drawing at all, where withVariant falls back
        // to the base file and the two spellings agree. The JetBrains node icons are the opposite case:
        // they carry baked palettes and ship as genuinely different drawings per background.
        //
        // ofIcon also binds the variant LATE, at draw time, so a theme swap needs nothing re-parsed --
        // which matters here because a parsed stylesheet is cached and would otherwise hold the old one.
        CgUiSvg icon = CgUiSvg.ofIcon(name);
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

    static @Nullable CgUiDrawable parseShape(String args) {
        List<String> parts = CssParsingUtil.splitTopLevelCommas(args);
        if (parts.size() != 1) return null;
        String name = unquote(parts.get(0).trim());
        CgUiShape.Kind kind = CgUiShape.parseKind(name);
        return kind == null ? null : new CgUiShape(kind);
    }

    static @Nullable CgUiDrawable parseImage(String args) {
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
        return new CgUiRect().withFillSprite(sprite);
    }

    static @Nullable CgUiDrawable parseSprite(String args) {
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
        return new CgUiRect().withFillSprite(sprite
                .setSprite(spriteRect[0], spriteRect[1], spriteRect[2], spriteRect[3])
                .setBorder(borderRect[0], borderRect[1], borderRect[2], borderRect[3]));
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

    static @Nullable CgUiDrawable parseAsset(String args) {
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
