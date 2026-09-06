package com.crystalgui.style.sheet;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named stylesheet lookup by resource path — {@code StyleSheetRegistry.of("namespace:path")},
 * mirroring {@link com.crystalgui.render.texture.asset.CgUiSpriteRegistry}'s lazy-load shape. One
 * CSS file per path, at {@code assets/{namespace}/ui/styles/{path}.css}. A resource pack supplies a
 * theme simply by shipping a file at that path — no separate registration call needed.
 */
public final class StyleSheetRegistry {

    /** The key {@link StyleSheet#DEFAULT} is built from — named so the two cannot drift apart. */
    static final String DEFAULT_SHEET = "crystalgui:default";

    /**
     * The user-agent sheet's parts, in concatenation order — <b>the one manifest</b>, read by
     * {@link #fetchSource} and by {@code StyleGovernanceTest}, so the build fails if a part is
     * renamed without both following.
     *
     * <p>default.css was split at its own section boundaries once it passed 6,000 lines
     * (plan/style-overhaul.md step 8) — a pure move, contiguous by construction so rule order across the
     * parts is exactly the old file's order. <b>Order here is load-bearing</b> the same way order
     * within a file is: equal-specificity ties fall to source order, and the concatenation IS the
     * source ({@code :disabled} after {@code :hover} being the classic casualty of a reorder).</p>
     */
    public static final List<String> DEFAULT_SHEET_PARTS = List.of(
            "crystalgui:ua/core",
            "crystalgui:ua/widgets",
            "crystalgui:ua/editor",
            "crystalgui:ua/overlays",
            "crystalgui:ua/config-kit",
            "crystalgui:ua/inspector",
            "crystalgui:ua/workbench",
            "crystalgui:ua/panels",
            "crystalgui:ua/search",
            "crystalgui:ua/uibuilder",
            // LAST, and deliberately: CrystalOS's window chrome contains widgets from every part above
            // it, so an equal-specificity tie between a frame's own rule and something inside the frame
            // resolves in favour of the frame. Nothing depends on that today -- every rule in the part is
            // tag- or child-scoped -- but `dialog .__close__` records what an accidental tie costs.
            "crystalgui:ua/desktop"
    );
    
    private static final ConcurrentHashMap<String, StyleSheet> CACHE = new ConcurrentHashMap<>();

    /**
     * The external variable table every parse resolves against — the active theme's <b>resolved</b>
     * tokens (the theme manager runs {@code resolveTable} before binding). Empty when no theme is
     * bound. Volatile: written from {@code bindVariables}, read from any parse.
     */
    private static volatile Map<String, String> boundVariables = Map.of();

    private StyleSheetRegistry() {
    }

    /** The currently bound external variable table — what {@link StyleSheet#parse(String)} resolves
     * against. Never {@code null}; empty when no theme is bound. */
    public static Map<String, String> boundVariables() {
        return boundVariables;
    }

    /**
     * Binds {@code resolvedTable} as the external variable table and re-substitutes every cached
     * sheet in place — <b>the theme-swap primitive</b>.
     *
     * <p>No I/O: each sheet re-parses from its retained source, so a swap works with the resource
     * pack long gone and costs one parse per sheet. Identity-stable throughout ({@link
     * StyleSheet#rebind} goes through {@code replaceRules}), so no engine's sheet <em>list</em>
     * changes — which is what keeps registration order out of the picture entirely.</p>
     *
     * <p><b>This only re-substitutes the sheets. It does not restyle anything</b> — call
     * {@code StyleEngine.restyleAllWindows()} afterwards, the same pairing {@link #reloadAll}
     * documents. ({@code UiThemeManager} owns that sequencing; almost nothing else should call
     * this directly.)</p>
     */
    public static void bindVariables(Map<String, String> resolvedTable) {
        boundVariables = Map.copyOf(resolvedTable);
        for (var entry : CACHE.entrySet()) {
            entry.getValue().rebind(boundVariables);
        }
        // The user-agent sheet is a rule-copy of the cached one, refilled by mirroring — same
        // special case as reloadAll below, for the same reason.
        StyleSheet cachedDefault = CACHE.get(DEFAULT_SHEET);
        if (cachedDefault != null && !cachedDefault.getRules().isEmpty()) {
            StyleSheet.DEFAULT.refillFrom(cachedDefault);
            return;
        }
        // THE ONE FAILURE THAT LOOKS EXACTLY LIKE SUCCESS.
        //
        // Nearly every colour a theme changes is a `var(--token, #fallback)` in the USER-AGENT sheet --
        // a theme carries variables and, in the shipped pair, no override rules at all. So this refill
        // is not a special case, it IS how a theme reaches the screen. Skip it and every other step
        // still runs perfectly: the table binds, every registered sheet re-substitutes, every window
        // re-matches, and the screen does not change.
        //
        // It is skipped when the cache has no entry under `DEFAULT_SHEET`, and `of()` reaches that state
        // by design -- `computeIfAbsent` does not store a null, so a sheet that could not be READ is
        // retried next time rather than being cached empty. Which makes this reachable exactly where
        // resource resolution differs: a host that resolves from source directories caches it on the
        // first call, and one whose resource manager was not up yet when `StyleSheet.DEFAULT` class-
        // initialised does not -- and `DEFAULT` is `static final`, so it never asks again.
        CrystalGuiCore.LOGGER.warn("theme: the user-agent sheet was NOT re-substituted -- '{}' is {} in "
                        + "the sheet cache, so every var() in it keeps the value it was parsed with and "
                        + "a theme swap will change nothing visible",
                DEFAULT_SHEET, cachedDefault == null ? "absent" : "cached but empty");
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
        String css = fetchSource(namespacedPath);
        if (css == null) {
            CrystalGuiCore.LOGGER.warn("StyleSheetRegistry: no stylesheet found for '{}'", namespacedPath);
            return null;
        }
        try {
            return StyleSheet.parse(css);
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("StyleSheetRegistry: failed to parse '{}': {}", namespacedPath, e.getMessage());
            return null;
        }
    }

    /**
     * The source text behind a registry key — one file for everything except {@link #DEFAULT_SHEET},
     * which is the concatenation of {@link #DEFAULT_SHEET_PARTS} in manifest order.
     *
     * <p>A missing <em>part</em> warns and is skipped rather than blanking the whole sheet: every file
     * in the manifest is another chance for a packaging slip, and all-but-one of a user-agent sheet
     * keeps most of the UI functional while the log names what is gone — where an empty sheet lays
     * every widget out at 0x0 and points at nothing. (Counted "nine" until CrystalOS added a tenth,
     * which is the kind of number not worth restating in prose.)</p>
     */
    private static String fetchSource(String namespacedPath) {
        if (!DEFAULT_SHEET.equals(namespacedPath)) {
            return CgIO.loadSource(toResourcePath(namespacedPath));
        }
        StringBuilder joined = new StringBuilder();
        boolean anyPart = false;
        for (String part : DEFAULT_SHEET_PARTS) {
            String css = CgIO.loadSource(toResourcePath(part));
            if (css == null) {
                CrystalGuiCore.LOGGER.warn("StyleSheetRegistry: user-agent part '{}' is missing", part);
                continue;
            }
            joined.append(css).append('\n');
            anyPart = true;
        }
        return anyPart ? joined.toString() : null;
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
            String css = fetchSource(entry.getKey());
            if (css == null) {
                CrystalGuiCore.LOGGER.warn(
                        "StyleSheetRegistry.reloadAll: '{}' is unreadable; keeping the rules already loaded",
                        entry.getKey());
                continue;
            }
            try {
                // refill rather than replaceRules: the retained source must follow the file, or the
                // next theme swap would rebind against the PRE-reload text.
                entry.getValue().refill(css, boundVariables);
                reloaded++;
            } catch (Exception e) {
                CrystalGuiCore.LOGGER.warn(
                        "StyleSheetRegistry.reloadAll: '{}' failed to parse ({}); keeping the previous rules",
                        entry.getKey(), e.getMessage());
            }
        }
        // The user-agent sheet, which is a copy of the cached one rather than the cached one -- see above.
        StyleSheet cachedDefault = CACHE.get(DEFAULT_SHEET);
        if (cachedDefault != null && !cachedDefault.getRules().isEmpty()) {
            StyleSheet.DEFAULT.refillFrom(cachedDefault);
        }
        CrystalGuiCore.LOGGER.info("StyleSheetRegistry.reloadAll: re-read {} of {} stylesheet(s)",
                reloaded, CACHE.size());
        return reloaded;
    }

    /** {@code "namespace:path"} -> {@code "namespace:ui/styles/path.css"}. */
    private static String toResourcePath(String namespacedPath) {
        int colon = namespacedPath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : namespacedPath.substring(0, colon);
        String path = colon < 0 ? namespacedPath : namespacedPath.substring(colon + 1);
        return namespace + ":ui/styles/" + path + ".css";
    }
}
