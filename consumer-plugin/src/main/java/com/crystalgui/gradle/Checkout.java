package com.crystalgui.gradle;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.gradle.api.file.FileCollection;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtraPropertiesExtension;

/**
 * A CrystalGUI checkout in place of the published artifacts, under the same coordinates.
 *
 * <p>The checkout builds only the node its target runs on — the only one the consumer's Gradle can be
 * relied on to configure: Loom wants a Java 21 daemon, the 1.7.10 host a Java 25 one. Both checkouts
 * resolve it themselves, against singlejar-logic's catalog, from the target in {@link #TARGET_PROPERTY}:
 * a system property, since nothing else reaches an included build's settings. It is cleared once
 * every build's settings have read it.</p>
 */
final class Checkout {

    /** singlejar-logic's {@code CHECKOUT_TARGET}: {@code loader:minecraft}. */
    static final String TARGET_PROPERTY = "singlejar.checkout.target";

    /** Where the project plugin finds the checkout, and whether the harness was asked for. */
    static final String DIR = "com.crystalgui.checkout";
    static final String HARNESS = "com.crystalgui.harness";

    private Checkout() {
    }

    static void include(Settings settings, File dir, Target target, HarnessSpec harness) {
        System.setProperty(TARGET_PROPERTY, target.encode());
        settings.getGradle().projectsLoaded(gradle -> System.clearProperty(TARGET_PROPERTY));
        // The checkout's settings include the harness on this; its build reads the rest as project
        // properties, which an included build inherits from this build's start parameter.
        System.setProperty("crystalgui.harness", String.valueOf(harness != null));
        if (harness != null) {
            Map<String, String> properties = new LinkedHashMap<>(settings.getStartParameter().getProjectProperties());
            if (harness.getMode().isPresent()) {
                properties.putIfAbsent("harness.mode", harness.getMode().get());
            }
            properties.putIfAbsent("harness.assetRoots", paths(harness.getAssetRoots()));
            properties.putIfAbsent("harness.extraClasspath", paths(harness.getClasses()));
            settings.getStartParameter().setProjectProperties(properties);
        }
        ExtraPropertiesExtension extra = settings.getGradle().getExtensions().getExtraProperties();
        extra.set(DIR, dir.getAbsolutePath());
        extra.set(HARNESS, harness != null);
        settings.includeBuild(dir, build -> build.dependencySubstitution(s -> {
            s.substitute(s.module(Artifacts.CORE)).using(s.project(":core"));
            s.substitute(s.module(Artifacts.TAFFY)).using(s.project(":taffy"));
            // Both jars come from the root project; the dependency's own capability picks one.
            for (String mod : new String[] {Artifacts.GUI_MOD, Artifacts.LANGUAGE_MOD}) {
                s.substitute(s.module(mod)).using(s.project(":"));
            }
        }));
    }

    private static String paths(FileCollection files) {
        return files.getFiles().stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }

    /** The Java the checkout's own Gradle runs on, from its {@code gradle-daemon-jvm.properties}; 17 when unpinned. */
    static int daemonJava(File checkout) {
        Properties p = load(new File(checkout, "gradle/gradle-daemon-jvm.properties"));
        return p == null ? 17 : Integer.parseInt(p.getProperty("toolchainVersion", "17").trim());
    }

    private static Properties load(File properties) {
        if (!properties.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(properties.toPath())) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
