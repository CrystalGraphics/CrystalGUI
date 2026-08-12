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
 * <p>Registration is <b>validation or refusal, never degradation</b> ({@code plan_styling.md}
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
        return register(css, expected, resourcePath);
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
