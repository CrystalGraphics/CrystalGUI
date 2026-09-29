package com.crystalgui.harness;

import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiThemeManager;

import java.util.logging.Logger;

/**
 * Installs the canonical sheet stack into a scene's style engine, with a theme on it.
 *
 * <p>Three steps that have to happen together, which is why they are one call: register the theme
 * file, activate it, and install {@code StyleSheet.DEFAULT} + the theme's own override sheet + the
 * scheme sheet into the engine. A scene that adds {@code StyleSheet.DEFAULT} by hand — which every
 * CrystalGUI scene did before this existed — gets a theme's <b>tokens</b> and none of its
 * <b>rules</b>, because the rules live in a sheet {@code UiThemeManager} owns and only
 * {@code installInto} adds. Tokens reach most of the look, so the result is a scene that is
 * three-quarters themed and reports nothing about the missing quarter.</p>
 *
 * <h3>Overriding from the command line</h3>
 *
 * <p>{@code -Dcrystalgui.theme=<ns:name>} chooses a different theme and {@code -Dcrystalgui.scheme}
 * an editor colour scheme; {@code none} for either means unthemed, which is how a scene is compared
 * against the bare user-agent sheet. {@code runHarness} forwards every {@code -Dcrystalgui.*} into
 * the forked JVM, so these need no Gradle plumbing.</p>
 *
 * <p>A theme outside the engine's own jar needs its resources reachable — see
 * {@code -Pharness.assetRoots} in {@code build.gradle.kts}.</p>
 */
public final class HarnessThemes {

    private static final Logger LOGGER = Logger.getLogger(HarnessThemes.class.getName());

    /** {@code -Dcrystalgui.theme=ns:name}, or {@code none}. */
    public static final String THEME_PROPERTY = "crystalgui.theme";
    /** {@code -Dcrystalgui.scheme=ns:name}, or {@code none}. */
    public static final String SCHEME_PROPERTY = "crystalgui.scheme";

    private HarnessThemes() {
    }

    /**
     * Installs the stack into {@code engine} and activates {@code defaultTheme}, unless the command
     * line named another.
     *
     * @param defaultTheme the theme this scene is for, as {@code "namespace:name"} — {@code null}
     *                     for a scene that wants the stack but no theme
     */
    public static void install(StyleEngine engine, String defaultTheme) {
        UiThemeManager themes = UiThemeManager.getInstance();
        themes.installInto(engine);

        // The engine's own, and not a convenience: `@extends crystalgui:crystal-dark` resolves through
        // the registry, so without this a consumer's theme finds no parent, `inheritanceChain` warns
        // and truncates, and the theme silently loses every role it left to its parent. Idempotent.
        ThemeRegistry.registerBuiltins();

        activate(System.getProperty(THEME_PROPERTY, defaultTheme), false);
        activate(System.getProperty(SCHEME_PROPERTY), true);
    }

    private static void activate(String id, boolean scheme) {
        String what = scheme ? "scheme" : "theme";
        UiThemeManager themes = UiThemeManager.getInstance();

        if (id == null) return;
        if ("none".equalsIgnoreCase(id)) {
            if (scheme) themes.setScheme(null); else themes.setTheme(null);
            LOGGER.info("[HarnessThemes] " + what + ": none (the bare user-agent sheet)");
            return;
        }

        // Registration is by PATH and refusal is a logged `false`, never a throw -- so a malformed
        // theme leaves the scene running on the user-agent sheet, and this line is the only thing that
        // says the file was the reason. Reported at every step for that reason: "registered but not
        // activated" and "never registered" produce the same unthemed screen.
        boolean registered = scheme ? ThemeRegistry.registerScheme(id) : ThemeRegistry.registerTheme(id);
        if (!registered) {
            LOGGER.warning("[HarnessThemes] " + what + " '" + id + "' was refused -- see the log above for "
                    + "the reason. Is -Pharness.assetRoots pointing at the mod's src/main/resources?");
            return;
        }
        boolean active = scheme ? themes.setScheme(id) : themes.setTheme(id);
        LOGGER.info("[HarnessThemes] " + what + " '" + id + "': registered, "
                + (active ? "active" : "NOT ACTIVE -- id mismatch between the @id tag and the file path?"));
    }
}
