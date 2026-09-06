package com.crystalgui.render.texture.asset;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiRepeat;
import com.crystalgui.render.texture.CgUiSprite;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named 9-slice asset lookup — {@code background: asset("namespace:path/to/pack", "element");} in
 * a stylesheet. A QoL layer on top of {@code background: sprite(...)} (inline pixel coordinates):
 * reuse one definition across many stylesheet rules instead of repeating sprite/border rects
 * everywhere.
 *
 * <p>One JSON <em>pack</em> per file, at {@code assets/{namespace}/ui/sprites/{path}.json},
 * holding multiple named elements; each element may override the pack's own texture/textureSize:
 * <pre>{@code
 * { "texture": "crystalgui:textures/gui/theme.png", "textureSize": [256, 256],
 *   "elements": {
 *     "button": { "sprite": [29, 1, 13, 13], "border": [1, 1, 11, 11] },
 *     "panel":  { "texture": "crystalgui:textures/gui/other.png", "textureSize": [128, 128],
 *                 "sprite": [0, 0, 32, 32], "border": [4, 4, 4, 4] }
 *   }
 * }
 * }</pre>
 *
 * <p>Loads and caches one parsed pack (all its elements) per path; callers get their own
 * {@link CgUiSprite} instance per lookup (or a visible fallback drawable on a missing pack/element —
 * see {@link #get}). Safe to hold the underlying {@link CgTexture2D} reference
 * indefinitely across resource-pack reloads — {@code CgTextureManager} mutates the same texture
 * object in place on reload rather than replacing it — but each element's sprite still reads
 * {@code texture.getWidth()/getHeight()} fresh on every {@link #get} call (not cached at load time),
 * since a reload can legitimately swap in a differently-sized image even without an atlas repack.</p>
 */
public final class CgUiSpriteRegistry {

    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, ParsedPack> CACHE = new ConcurrentHashMap<>();

    private CgUiSpriteRegistry() {
    }

    /**
     * Drops every parsed pack, so the next {@link #get} re-reads its JSON — the sprite half of a
     * resource reload. @see com.crystalgui.lifecycle.CgUiLifecycle
     *
     * <p><b>Only the JSON.</b> The textures a pack names are reloaded by {@code CgTextureManager},
     * which mutates the same {@code CgTexture2D} objects in place, so edited artwork already reaches
     * sprites that were handed out before the reload. What does not is an edited pack file — new
     * coordinates, a new border, a new element — because those were read once and cached here.</p>
     *
     * <p>Sprites already handed out keep the coordinates they were built with; they are replaced when
     * whatever asked for them asks again, which for a stylesheet's {@code asset(...)} is the restyle
     * that follows this in the same reload.</p>
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /** Returns a fresh, independent {@link CgUiSprite} instance for the named element within the
     * given pack, or a visible fallback drawable if the pack file is missing/malformed or the
     * element doesn't exist — never {@code null}, so a broken reference degrades visibly rather
     * than silently rendering nothing. */
    public static CgUiDrawable get(String packPath, String elementName) {
        ParsedPack pack = CACHE.computeIfAbsent(packPath, CgUiSpriteRegistry::load);
        if (pack == null) return fallback();
        ParsedElement element = pack.elements.get(elementName);
        if (element == null) {
            CrystalGuiCore.LOGGER.warn("CgUiSpriteRegistry: no element '{}' in asset pack '{}'", elementName, packPath);
            return fallback();
        }
        return element.toSprite(pack);
    }

    /** Same fallback texture the rest of the engine already uses for a broken texture load
     * ({@code CgUiRenderer}/{@code CgUiSprite} both reference {@code CgTextureManager.get().getFallback()}
     * directly) — wrapped in a fresh {@link CgUiSprite} since {@link CgTextureManager#getFallback()}
     * returns a raw {@link CgTexture2D}, not a {@link CgUiDrawable}. Deliberately a method, not a
     * cached static field: {@code getFallback()} lazily creates a real GPU texture on first use,
     * which needs a live GL context — evaluating it eagerly at class-load time would risk the same
     * "forces GL work before it's safe" bug already fixed for {@code CgUiPaintContext}'s font
     * loading. {@code CgTextureManager.getFallback()} is already cached internally, so repeated
     * calls here are cheap; failures should be rare/dev-time-only anyway.
     *
     * <p>Must call {@link CgUiSprite#setTextureSizeReference} explicitly — {@link CgUiSprite#setTexture}
     * deliberately skips auto-deriving it specifically when the texture passed in is the fallback
     * texture (see its own {@code texture != CgTextureManager.get().getFallback()} guard), which
     * otherwise leaves the sprite's texture size at its zero default and makes {@code draw()}
     * silently no-op — confirmed live via the harness before adding this line, the fallback sprite
     * rendered nothing at all rather than the visible checkerboard.</p> */
    private static CgUiDrawable fallback() {
        CgTexture2D tex = CgTextureManager.get().getFallback();
        return new CgUiSprite().setTexture(tex).setTextureSizeReference(tex.getWidth(), tex.getHeight());
    }

    private static ParsedPack load(String namespacedPath) {
        String resourcePath = toResourcePath(namespacedPath);
        String json = CgIO.loadSource(resourcePath);
        if (json == null) {
            CrystalGuiCore.LOGGER.warn("CgUiSpriteRegistry: no asset pack found at '{}' (from '{}')", resourcePath, namespacedPath);
            return null;
        }
        try {
            return parsePack(json);
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("CgUiSpriteRegistry: failed to parse '{}': {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /** {@code "namespace:path"} -> {@code "namespace:ui/sprites/path.json"}. */
    private static String toResourcePath(String namespacedPath) {
        int colon = namespacedPath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : namespacedPath.substring(0, colon);
        String path = colon < 0 ? namespacedPath : namespacedPath.substring(colon + 1);
        return namespace + ":ui/sprites/" + path + ".json";
    }

    private static ParsedPack parsePack(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        String packTexture = optString(root, "texture");
        int[] packTextureSize = optIntArray(root, "textureSize");

        Map<String, ParsedElement> elements = new HashMap<>();
        JsonObject elementsObj = root.has("elements") ? root.getAsJsonObject("elements") : null;
        if (elementsObj != null) {
            for (var entry : elementsObj.entrySet()) {
                JsonObject el = entry.getValue().getAsJsonObject();
                String texture = optString(el, "texture");
                int[] textureSize = optIntArray(el, "textureSize");
                int[] sprite = optIntArray(el, "sprite");
                int[] border = optIntArray(el, "border");
                String repeat = optString(el, "repeat");
                Boolean fillCenter = optBoolean(el, "fillCenter");
                Float borderScale = optFloat(el, "borderScale");
                elements.put(entry.getKey(),
                        new ParsedElement(texture, textureSize, sprite, border, repeat, fillCenter, borderScale));
            }
        }
        if (packTexture == null && elements.values().stream().allMatch(e -> e.texture == null)) {
            throw new IllegalArgumentException("pack has no top-level 'texture' and no element overrides one");
        }
        return new ParsedPack(packTexture, packTextureSize, elements);
    }

    private record ParsedPack(String texture, int[] textureSize, Map<String, ParsedElement> elements) {
    }

    private record ParsedElement(String texture, int[] textureSize, int[] sprite, int[] border,
                                 String repeat, Boolean fillCenter, Float borderScale) {
        CgUiSprite toSprite(ParsedPack pack) {
            String resolvedTexture = texture != null ? texture : pack.texture;
            int[] resolvedTextureSize = textureSize != null ? textureSize : pack.textureSize;
            if (resolvedTexture == null) {
                throw new IllegalArgumentException("element has no 'texture' and pack has none either");
            }

            CgTexture2D tex = CgTextureManager.get().getOrCreate(resolvedTexture, CgTextureSpec.RGBA8_NEAREST);
            CgUiSprite sprite2 = new CgUiSprite().setTexture(tex);
            if (resolvedTextureSize != null && resolvedTextureSize.length == 2) {
                sprite2.setTextureSizeReference(resolvedTextureSize[0], resolvedTextureSize[1]);
            } else {
                sprite2.setTextureSizeReference(tex.getWidth(), tex.getHeight());
            }
            if (sprite != null && sprite.length == 4) sprite2.setSprite(sprite[0], sprite[1], sprite[2], sprite[3]);
            if (border != null && border.length == 4) sprite2.setBorder(border[0], border[1], border[2], border[3]);

            if (fillCenter != null) sprite2.setFillCenter(fillCenter);
            if (borderScale != null) sprite2.setBorderScale(borderScale);
            if (repeat != null) {
                CgUiRepeat[] modes = parseRepeat(repeat);
                if (modes != null) {
                    sprite2.setRepeat(modes[0], modes[1]);
                } else {
                    // Warn and stretch rather than throw: parsePack's exceptions are swallowed by
                    // load(), which would discard the entire pack over one bad keyword.
                    CrystalGuiCore.LOGGER.warn("CgUiSpriteRegistry: unknown repeat mode '{}' — using stretch. "
                            + "Expected one or two of: stretch, repeat, round, space", repeat);
                }
            }
            return sprite2;
        }
    }

    /** {@code "repeat"} or {@code "repeat round"} — second axis defaults to the first, matching CSS
     * {@code border-image-repeat}. {@code null} when unrecognised. */
    private static CgUiRepeat[] parseRepeat(String raw) {
        String[] words = raw.trim().split("\\s+");
        if (words.length == 0 || words.length > 2) return null;
        CgUiRepeat x = CgUiRepeat.parse(words[0]);
        if (x == null) return null;
        CgUiRepeat y = words.length == 2 ? CgUiRepeat.parse(words[1]) : x;
        if (y == null) return null;
        return new CgUiRepeat[]{x, y};
    }

    private static Boolean optBoolean(JsonObject obj, String field) {
        return obj.has(field) ? obj.get(field).getAsBoolean() : null;
    }

    private static Float optFloat(JsonObject obj, String field) {
        return obj.has(field) ? obj.get(field).getAsFloat() : null;
    }

    private static String optString(JsonObject obj, String field) {
        return obj.has(field) ? obj.get(field).getAsString() : null;
    }

    private static int[] optIntArray(JsonObject obj, String field) {
        if (!obj.has(field)) return null;
        JsonArray arr = obj.getAsJsonArray(field);
        int[] values = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) values[i] = arr.get(i).getAsInt();
        return values;
    }
}
