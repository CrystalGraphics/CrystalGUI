package com.crystalgui.style.theme;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every theme and editor colour scheme the process knows about, keyed by declared {@code @id}.
 *
 * <p>Registration is <b>validation or refusal, never degradation</b> ({@code plan/style-overhaul.md}
 * §4.4): a file whose header is malformed or whose CSS would not survive parsing (the
 * {@code :focus-within} poison) is refused with a log and simply not offered — a broken downloaded
 * theme must never become a blank window. {@link UiTheme#parse} does the judging; this class only
 * turns its throw into a {@code false}.</p>
 *
 * <p>Discovery is by explicit path: built-ins are pre-registered at startup (step 3 of the plan),
 * mods call {@link #registerTheme}, and a zero-Java resource pack lists its files in an index the
 * host walks. There is deliberately no classpath scanning — it is not portable across loaders.</p>
 *
 * <p>Re-registering an id replaces the entry (the theme-author dev loop: edit, re-register, look).
 * Listing order is registration order, which is what the Preferences dropdowns show.</p>
 */
public final class ThemeRegistry {

    private static final Map<String, UiTheme> REGISTRY =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Every {@code registerTheme}/{@code registerScheme} call that named a FILE, so {@link #reloadAll}
     * can replay it — keyed by the namespaced path, valued by the role it was registered as.
     *
     * <p>Keyed by the PATH rather than by the theme's id, which is what lets a reload be a replay: an
     * id is declared inside the file and may disagree with the path it was loaded from, so keying by
     * id would leave the reload unable to say which file to re-read. Replaying the original call also
     * means a re-register goes through the ordinary refusal path, so a file that stops parsing
     * mid-save keeps the entry it already had.</p>
     *
     * <p>{@link #registerSource} is deliberately absent from this: it has no file, so there is nothing
     * to re-read and pretending otherwise would drop the entry on the first reload.</p>
     */
    private static final Map<String, UiTheme.Role> ORIGINS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private ThemeRegistry() {
    }

    /** Loads and registers a theme from {@code assets/{ns}/ui/themes/{path}.css}. False = refused, with the reason logged. */
    public static boolean registerTheme(String namespacedPath) {
        return registerFromPath(namespacedPath, "ui/themes/", UiTheme.Role.THEME);
    }

    /** Loads and registers a scheme from {@code assets/{ns}/ui/schemes/{path}.css}. False = refused, with the reason logged. */
    public static boolean registerScheme(String namespacedPath) {
        return registerFromPath(namespacedPath, "ui/schemes/", UiTheme.Role.SCHEME);
    }

    /** Registers from CSS text directly — the dev/test entry point. Role comes from the header. */
    public static boolean registerSource(String css) {
        return register(css, null, "<inline>");
    }

    /**
     * Registers the engine's shipped themes and schemes — idempotent, call from any host that
     * offers a theme picker. Not automatic: a consumer embedding the engine with its own themes
     * should not find ours in the dropdown uninvited.
     */
    public static void registerBuiltins() {
        registerTheme("crystalgui:crystal-dark");
        registerTheme("crystalgui:crystal-light");
        // Islands first, because registration order is what a picker lists in and these are the pair the
        // shipped themes suggest. Dark+/Light+ stay registered rather than being replaced: they are what a
        // VS Code user recognises, and the whole point of the second axis is that the choice is theirs.
        registerScheme("crystalgui:islands-dark");
        registerScheme("crystalgui:islands-light");
        registerScheme("crystalgui:dark-plus");
        registerScheme("crystalgui:light-plus");
        // Last because it is the outlier: Islands and Dark+ both keep a page calm and colour by
        // category, Eclipse Dark colours nearly every identifier by KIND. Registered rather than
        // omitted for the same reason Dark+ is — the choice is the user's.
        registerScheme("crystalgui:eclipse-dark");
    }

    @Nullable
    public static UiTheme get(String id) {
        return REGISTRY.get(id);
    }

    /** Every registered UI theme, in registration order. */
    public static List<UiTheme> themes() {
        return byRole(UiTheme.Role.THEME);
    }

    /** Every registered editor colour scheme, in registration order. */
    public static List<UiTheme> schemes() {
        return byRole(UiTheme.Role.SCHEME);
    }

    public static void resetForTesting() {
        REGISTRY.clear();
        ORIGINS.clear();
    }

    /**
     * Re-reads every theme and scheme that was registered from a file — <b>theme hot reload</b>.
     *
     * <p>The counterpart to {@code StyleSheetRegistry.reloadAll()}, and it is a separate call because
     * a theme is not in that cache: a {@code UiTheme} captures its source text and its variable table
     * at registration, so re-reading every stylesheet re-substitutes them all against the table the
     * theme had <em>when it was registered</em>. Editing a token and reloading the sheets therefore
     * reports "re-read N stylesheets" and changes no colour — the failure this project keeps writing
     * down, where the mechanism is live and the input is stale.</p>
     *
     * <p><b>This only re-registers. It does not re-apply</b> — the active theme is a field on
     * {@link UiThemeManager} pointing at the object this replaces, so nothing on screen moves until
     * that field is re-resolved and the table rebound. {@link UiThemeManager#reloadFromDisk()} is the
     * pair, and is what a host should call.</p>
     *
     * @return how many files were re-read and accepted
     */
    public static int reloadAll() {
        Map<String, UiTheme.Role> origins;
        synchronized (ORIGINS) {
            origins = new LinkedHashMap<>(ORIGINS);
        }
        int reloaded = 0;
        for (var entry : origins.entrySet()) {
            if (registerFromPath(entry.getKey(), dirFor(entry.getValue()), entry.getValue())) reloaded++;
        }
        CrystalGuiCore.LOGGER.info("ThemeRegistry.reloadAll: re-read {} of {} theme file(s)",
                reloaded, origins.size());
        return reloaded;
    }

    private static String dirFor(UiTheme.Role role) {
        return role == UiTheme.Role.SCHEME ? "ui/schemes/" : "ui/themes/";
    }

    private static List<UiTheme> byRole(UiTheme.Role role) {
        List<UiTheme> matching = new ArrayList<>();
        synchronized (REGISTRY) {
            for (UiTheme theme : REGISTRY.values()) {
                if (theme.role() == role) matching.add(theme);
            }
        }
        return matching;
    }

    private static boolean registerFromPath(String namespacedPath, String dir, UiTheme.Role expected) {
        String resourcePath = toResourcePath(namespacedPath, dir);
        String css = CgIO.loadSource(resourcePath);
        if (css == null) {
            CrystalGuiCore.LOGGER.warn("ThemeRegistry: no file at '{}' (from '{}')", resourcePath, namespacedPath);
            return false;
        }
        if (!register(css, expected, resourcePath)) return false;
        // Recorded only on SUCCESS, so a path that has never once produced a usable theme is not
        // re-read on every reload for the rest of the process.
        ORIGINS.put(namespacedPath, expected);
        return true;
    }

    private static boolean register(String css, @Nullable UiTheme.Role expected, String origin) {
        UiTheme theme;
        try {
            theme = UiTheme.parse(css);
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("ThemeRegistry: refusing '{}' — {}", origin, e.getMessage());
            return false;
        }
        if (expected != null && theme.role() != expected) {
            CrystalGuiCore.LOGGER.warn("ThemeRegistry: refusing '{}' — declared a {} where a {} was expected",
                    origin, theme.role(), expected);
            return false;
        }
        REGISTRY.put(theme.id(), theme);
        return true;
    }

    /** {@code "namespace:path"} → {@code "namespace:ui/themes/path.css"} (or {@code ui/schemes/}). */
    private static String toResourcePath(String namespacedPath, String dir) {
        int colon = namespacedPath.indexOf(':');
        String namespace = colon < 0 ? "crystalgui" : namespacedPath.substring(0, colon);
        String path = colon < 0 ? namespacedPath : namespacedPath.substring(colon + 1);
        return namespace + ":" + dir + path + ".css";
    }
}
