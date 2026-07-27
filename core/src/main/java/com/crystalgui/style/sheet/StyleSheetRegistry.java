package com.crystalgui.style.sheet;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Named stylesheet lookup by resource path — {@code StyleSheetRegistry.of("namespace:path")},
 * mirroring {@link com.crystalgui.render.texture.asset.CgUiSpriteRegistry}'s lazy-load shape. One
 * CSS file per path, at {@code assets/{namespace}/ui/styles/{path}.css}. A resource pack supplies a
 * theme simply by shipping a file at that path — no separate registration call needed.
 */
public final class StyleSheetRegistry {

    private static final ConcurrentHashMap<String, StyleSheet> CACHE = new ConcurrentHashMap<>();

    private StyleSheetRegistry() {
    }

    /** Returns the parsed stylesheet for {@code namespacedPath}, or an empty (no-op) stylesheet if
     * missing/malformed — never {@code null}. A missing file is NOT cached (matches
     * {@code CgUiSpriteRegistry}'s computeIfAbsent-returns-null behavior), so it's retried on the
     * next call rather than staying permanently empty once the owning resource pack loads. */
    public static StyleSheet of(String namespacedPath) {
        StyleSheet sheet = CACHE.computeIfAbsent(namespacedPath, StyleSheetRegistry::load);
        return sheet != null ? sheet : StyleSheet.parse("");
    }

    private static StyleSheet load(String namespacedPath) {
        String resourcePath = toResourcePath(namespacedPath);
        String css = CgIO.loadSource(resourcePath);
        if (css == null) {
            CrystalGuiCore.LOGGER.warn("StyleSheetRegistry: no stylesheet found at '{}' (from '{}')", resourcePath, namespacedPath);
            return null;
        }
        try {
            return StyleSheet.parse(css);
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("StyleSheetRegistry: failed to parse '{}': {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /** {@code "namespace:path"} -> {@code "namespace:ui/styles/path.css"}. */
    private static String toResourcePath(String namespacedPath) {
        int colon = namespacedPath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : namespacedPath.substring(0, colon);
        String path = colon < 0 ? namespacedPath : namespacedPath.substring(colon + 1);
        return namespace + ":ui/styles/" + path + ".css";
    }
}
