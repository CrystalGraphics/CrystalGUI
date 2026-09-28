package com.crystalgui.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;

/**
 * CrystalGUI's GL harness on this build's own assets and classes, for authoring a UI without a dev
 * client: {@code ./gradlew runHarness}. Needs a checkout, since the harness is not published.
 *
 * <pre>{@code
 * crystalgui {
 *     minecraft("1.20.1", "forge")
 *     checkout("../CrystalGUI")
 *     harness { mode = "rpg-console" }
 * }
 * }</pre>
 *
 * <p>Both paths default to the root project's: {@code src/main/resources}, and
 * {@code build/classes/java/main}, which {@code runHarness} compiles first. {@code -Pharness.mode=<id>}
 * on the command line still wins over {@link #getMode()}.</p>
 */
public abstract class HarnessSpec {

    /** The scene the harness opens. */
    public abstract Property<String> getMode();

    /** Asset roots read ahead of the jars', so an edit shows on the next reload. */
    public abstract ConfigurableFileCollection getAssetRoots();

    /** Classes the scene may construct: this mod's own, so it previews the real screen. */
    public abstract ConfigurableFileCollection getClasses();
}
