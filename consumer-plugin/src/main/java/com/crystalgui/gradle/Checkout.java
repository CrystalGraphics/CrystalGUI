package com.crystalgui.gradle;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Properties;

import org.gradle.api.GradleException;
import org.gradle.api.initialization.Settings;

/**
 * A CrystalGUI checkout in place of the published artifacts, under the same coordinates.
 *
 * <p>The checkout includes only the nodes the target runs on — the node of the target's loader whose
 * claimed range holds its version, and the common node beside it — which are the only ones the
 * consumer's Gradle can configure: Loom wants a Java 21 daemon, the 1.7.10 host a Java 25 one. Both
 * checkouts read that list from {@link #NODES_PROPERTY}, a system property, since nothing else
 * reaches an included build's settings; CrystalGraphics, included last, clears it.</p>
 */
final class Checkout {

    static final String NODES_PROPERTY = "crystalgui.checkout.nodes";

    private Checkout() {
    }

    static void include(Settings settings, File dir, Target target) {
        String node = claimingNode(dir, target);
        System.setProperty(NODES_PROPERTY, "common:" + node + "," + target.loader() + ":" + node);
        settings.includeBuild(dir, build -> build.dependencySubstitution(s -> {
            s.substitute(s.module(Artifacts.CORE)).using(s.project(":core"));
            s.substitute(s.module(Artifacts.TAFFY)).using(s.project(":taffy"));
            // Both jars come from the root project; the dependency's own capability picks one.
            for (String mod : new String[] {Artifacts.GUI_MOD, Artifacts.LANGUAGE_MOD}) {
                s.substitute(s.module(mod)).using(s.project(":"));
            }
        }));
    }

    /** The Java the checkout's own Gradle runs on, from its {@code gradle-daemon-jvm.properties}; 17 when unpinned. */
    static int daemonJava(File checkout) {
        Properties p = load(new File(checkout, "gradle/gradle-daemon-jvm.properties"));
        return p == null ? 17 : Integer.parseInt(p.getProperty("toolchainVersion", "17").trim());
    }

    /** The node whose {@code variant.minecraft} holds the target's version, read off the checkout. */
    private static String claimingNode(File checkout, Target target) {
        File versions = new File(checkout, "runtime/mc/modern/" + target.loader() + "/versions");
        File[] nodes = versions.listFiles(File::isDirectory);
        if (nodes != null) {
            for (File node : nodes) {
                String range = claimedRange(new File(node, "gradle.properties"));
                if (range != null && VersionRange.parse(range).contains(target.minecraft())) {
                    return node.getName();
                }
            }
        }
        throw new GradleException("crystalgui: the checkout at " + checkout + " has no " + target.loader()
            + " node claiming Minecraft " + target.minecraft());
    }

    private static String claimedRange(File properties) {
        Properties p = load(properties);
        return p == null ? null : p.getProperty("variant.minecraft");
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
