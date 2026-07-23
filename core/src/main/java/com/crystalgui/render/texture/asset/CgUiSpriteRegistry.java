package com.crystalgui.render.texture.asset;

import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.CgUiSprite;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Named 9-slice asset lookup — {@code background: asset("namespace:path");} in a stylesheet.
 * A QoL layer on top of {@code background: sprite(...)} (inline pixel coordinates): reuse one
 * definition across many stylesheet rules instead of repeating sprite/border rects everywhere.
 *
 * <p>One JSON file per named asset, at {@code assets/{namespace}/ui/sprites/{path}.json}:
 * <pre>{@code
 * { "texture": "crystalgui:textures/gui/gdp_styles.png", "textureSize": [256, 256],
 *   "sprite": [29, 1, 13, 13], "border": [1, 1, 11, 11] }
 * }</pre>
 *
 * <p>Loads and caches one template {@link CgUiSprite} per path; callers get their own instance via
 * {@link CgUiSprite#copy()}. Safe to hold the underlying {@link CgTexture2D} reference indefinitely
 * across resource-pack reloads — {@code CgTextureManager} mutates the same texture object in place
 * on reload rather than replacing it — but the template still reads {@code texture.getWidth()/getHeight()}
 * fresh on every {@link #get} call (not cached at load time), since a reload can legitimately swap in
 * a differently-sized image even without an atlas repack.</p>
 */
public final class CgUiSpriteRegistry {

    private static final ConcurrentHashMap<String, ParsedAsset> CACHE = new ConcurrentHashMap<>();

    private CgUiSpriteRegistry() {
    }

    /** Returns a fresh, independent {@link CgUiSprite} instance for the named asset, or {@code null}
     * if the asset file is missing or malformed. */
    public static CgUiSprite get(String namespacedPath) {
        ParsedAsset asset = CACHE.computeIfAbsent(namespacedPath, CgUiSpriteRegistry::load);
        if (asset == null) return null;
        return asset.toSprite();
    }

    private static ParsedAsset load(String namespacedPath) {
        String resourcePath = toResourcePath(namespacedPath);
        String json = CgIO.loadSource(resourcePath);
        if (json == null) {
            CrystalGuiCore.LOGGER.warn("CgUiSpriteRegistry: no asset file found at '{}' (from '{}')", resourcePath, namespacedPath);
            return null;
        }
        try {
            return MiniJson.parseSpriteAsset(json);
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

    private record ParsedAsset(String texture, int[] sprite, int[] border) {
        CgUiSprite toSprite() {
            CgTexture2D tex = CgTextureManager.get().getOrCreate(texture, com.crystalgraphics.api.texture.CgTextureSpec.RGBA8_NEAREST);
            CgUiSprite sprite2 = new CgUiSprite()
                    .setTexture(tex)
                    .setTextureSizeReference(tex.getWidth(), tex.getHeight());
            if (sprite != null && sprite.length == 4) sprite2.setSprite(sprite[0], sprite[1], sprite[2], sprite[3]);
            if (border != null && border.length == 4) sprite2.setBorder(border[0], border[1], border[2], border[3]);
            return sprite2;
        }
    }

    /** Minimal hand-rolled parser for this one fixed 4-field schema — no general JSON library is on
     * {@code core}'s classpath, and pulling one in for this alone isn't warranted. */
    private static final class MiniJson {
        private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
        private static final Pattern ARRAY_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\[([^\\]]*)]");

        static ParsedAsset parseSpriteAsset(String json) {
            String texture = null;
            int[] sprite = null;
            int[] border = null;

            Matcher sm = STRING_FIELD.matcher(json);
            while (sm.find()) {
                if (sm.group(1).equalsIgnoreCase("texture")) texture = sm.group(2);
            }
            Matcher am = ARRAY_FIELD.matcher(json);
            while (am.find()) {
                String name = am.group(1).toLowerCase(Locale.ROOT);
                int[] values = parseIntArray(am.group(2));
                if (name.equals("sprite")) sprite = values;
                else if (name.equals("border")) border = values;
            }

            if (texture == null) throw new IllegalArgumentException("missing 'texture' field");
            return new ParsedAsset(texture, sprite, border);
        }

        private static int[] parseIntArray(String raw) {
            String[] tokens = raw.split(",");
            int[] values = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                values[i] = (int) Double.parseDouble(tokens[i].trim());
            }
            return values;
        }
    }
}
