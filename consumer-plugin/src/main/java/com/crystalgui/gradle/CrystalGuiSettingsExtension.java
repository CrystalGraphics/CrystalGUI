package com.crystalgui.gradle;

import java.io.File;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.initialization.Settings;

/**
 * The settings-side {@code crystalgui} block: build CrystalGUI from a checkout instead of Maven, for a
 * mod written against an unreleased CrystalGUI.
 *
 * <pre>{@code
 * // settings.gradle.kts
 * plugins { id("com.crystalgui.settings") version "1.0.0" }
 * crystalgui {
 *     minecraft("1.20.1", "forge")   // every project with a toolchain takes this target
 *     checkout("../CrystalGUI")
 * }
 * }</pre>
 *
 * <p>The projects keep the same coordinates and the same {@code com.crystalgui} plugin; only where the
 * artifacts come from changes. The engine compiles from the checkout's sources, and the dev run gets
 * single jars the checkout builds — carrying only this target's variant — so an edit to the checkout
 * reaches the next run.</p>
 *
 * <ul>
 *   <li>{@code checkout} needs {@code minecraft}: the checkout includes only the loader nodes the target
 *       runs on, which are the only ones this build's Gradle can configure.</li>
 *   <li>The path is relative to the settings directory, and the checkout must be cloned with
 *       {@code --recursive}.</li>
 *   <li>Modern targets only: 1.7.10 and Forge 1.8–1.12.2 build from Maven.</li>
 * </ul>
 */
public abstract class CrystalGuiSettingsExtension {

    private final Settings settings;
    private Target target;
    private File checkout;

    @Inject
    public CrystalGuiSettingsExtension(Settings settings) {
        this.settings = settings;
    }

    /** The target every project with a toolchain builds for; its own {@code minecraft} may repeat it. */
    public void minecraft(String version, String loader) {
        target = Target.of(version, loader);
        settings.getGradle().getExtensions().getExtraProperties()
            .set(CrystalGuiPlugin.SETTINGS_TARGET, target.encode());
    }

    /** Builds CrystalGUI and CrystalGraphics from the checkout at {@code path}. */
    public void checkout(Object path) {
        File dir = new File(path.toString());
        checkout = (dir.isAbsolute() ? dir : new File(settings.getSettingsDir(), path.toString())).toPath()
            .normalize().toFile();
    }

    /** Includes the checkout, once settings are evaluated so the declarations can come in any order. */
    void include() {
        if (checkout == null) {
            return;
        }
        if (target == null) {
            throw new InvalidUserDataException("crystalgui: checkout(\"" + checkout + "\") needs"
                + " minecraft(version, loader) in the same block: the checkout builds only that target");
        }
        if (target.below("1.13")) {
            throw new InvalidUserDataException("crystalgui: a checkout builds modern targets only; " + target
                + " takes CrystalGUI from Maven");
        }
        if (!new File(checkout, "settings.gradle.kts").isFile()) {
            throw new GradleException("crystalgui: no CrystalGUI checkout at " + checkout
                + ". Clone it there: git clone --recursive https://github.com/CrystalGraphics/CrystalGUI");
        }
        if (!new File(checkout, "taffy/build.gradle.kts").isFile()) {
            throw new GradleException("crystalgui: the checkout at " + checkout + " is missing its submodules:"
                + " git -C " + checkout + " submodule update --init --recursive");
        }
        int required = Checkout.daemonJava(checkout);
        if (Integer.parseInt(JavaVersion.current().getMajorVersion()) < required) {
            throw new GradleException("crystalgui: a checkout runs CrystalGUI's own build logic, which needs"
                + " Gradle on Java " + required + "; this build runs on " + JavaVersion.current() + ". Pin the"
                + " daemon: ./gradlew updateDaemonJvm --jvm-version=" + required);
        }
        Checkout.include(settings, checkout, target);
    }
}
