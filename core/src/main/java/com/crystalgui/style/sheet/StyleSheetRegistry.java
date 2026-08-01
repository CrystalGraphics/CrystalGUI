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

    /**
     * Re-reads every stylesheet loaded so far, <b>in place</b> — the hot-reload entry point.
     *
     * <p>Every sheet keeps its identity, so this reaches every window that has already registered one
     * without any of them re-registering.</p>
     *
     * <p><b>{@link StyleSheet#DEFAULT} needs refilling separately, and that is easy to get wrong.</b> It
     * looks like the cached {@code crystalgui:default} instance — {@code loadUserAgentSheet} does fetch it
     * from here — but it returns a <em>copy</em> of those rules at {@link com.crystalgui.style.StyleOrigin
     * #USER_AGENT}, because the cache holds sheets at the ordinary author origin. So refilling the cache
     * alone leaves the user-agent sheet on its original rules, and a reload would appear to work for every
     * theme while silently ignoring edits to {@code default.css} — the one file this is most often pointed
     * at. Caught by {@code StyleSheetHotReloadTest}, which asserted the identity that does not hold.</p>
     *
     * <p><b>This only re-reads the files. It does not restyle anything</b> — a sheet does not know which
     * windows use it, and walking every window from here would make a global cache reach into per-window
     * state. Call {@code StyleEngine.invalidateAllMatches()} on each live window afterwards, which is the
     * same pairing {@code addStylesheet} does inline.</p>
     *
     * <p><b>A file that has gone missing or stopped parsing keeps its previous rules</b>, with a warning.
     * The alternative is emptying the sheet, and an empty user-agent sheet lays every widget out at 0x0 —
     * so a stray keystroke mid-save would blank the window and read as the reload being broken rather than
     * as the file being momentarily unreadable. Editors write files non-atomically; this will be pointed
     * at a file that is being saved.</p>
     *
     * @return how many sheets were re-read successfully
     */
    public static int reloadAll() {
        int reloaded = 0;
        for (var entry : CACHE.entrySet()) {
            String resourcePath = toResourcePath(entry.getKey());
            String css = CgIO.loadSource(resourcePath);
            if (css == null) {
                CrystalGuiCore.LOGGER.warn(
                        "StyleSheetRegistry.reloadAll: '{}' is unreadable; keeping the rules already loaded",
                        resourcePath);
                continue;
            }
            try {
                entry.getValue().replaceRules(StyleSheet.parse(css).getRules());
                reloaded++;
            } catch (Exception e) {
                CrystalGuiCore.LOGGER.warn(
                        "StyleSheetRegistry.reloadAll: '{}' failed to parse ({}); keeping the previous rules",
                        resourcePath, e.getMessage());
            }
        }
        // The user-agent sheet, which is a copy of the cached one rather than the cached one -- see above.
        StyleSheet cachedDefault = CACHE.get(DEFAULT_SHEET);
        if (cachedDefault != null && !cachedDefault.getRules().isEmpty()) {
            StyleSheet.DEFAULT.replaceRules(cachedDefault.getRules());
        }
        CrystalGuiCore.LOGGER.info("StyleSheetRegistry.reloadAll: re-read {} of {} stylesheet(s)",
                reloaded, CACHE.size());
        return reloaded;
    }

    /** The path {@link StyleSheet#DEFAULT} is built from — named so the two cannot drift apart. */
    static final String DEFAULT_SHEET = "crystalgui:default";

    /** {@code "namespace:path"} -> {@code "namespace:ui/styles/path.css"}. */
    private static String toResourcePath(String namespacedPath) {
        int colon = namespacedPath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : namespacedPath.substring(0, colon);
        String path = colon < 0 ? namespacedPath : namespacedPath.substring(colon + 1);
        return namespace + ":ui/styles/" + path + ".css";
    }
}
