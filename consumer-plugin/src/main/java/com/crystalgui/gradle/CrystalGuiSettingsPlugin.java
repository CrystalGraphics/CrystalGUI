package com.crystalgui.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

/**
 * {@code com.crystalgui.settings}: a target shared by every project, and CrystalGUI from a checkout. The
 * DSL is {@link CrystalGuiSettingsExtension}'s.
 *
 * <pre>{@code
 * plugins { id("com.crystalgui.settings") version "1.0.0" }
 * crystalgui {
 *     minecraft("1.20.1", "forge")
 *     checkout("../CrystalGUI")
 * }
 * }</pre>
 */
public class CrystalGuiSettingsPlugin implements Plugin<Settings> {

    @Override
    public void apply(Settings settings) {
        CrystalGuiSettingsExtension extension =
            settings.getExtensions().create("crystalgui", CrystalGuiSettingsExtension.class, settings);
        settings.getGradle().settingsEvaluated(evaluated -> extension.include());
    }
}
