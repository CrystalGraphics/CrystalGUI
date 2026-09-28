package cgbuildlogic

import org.gradle.api.Project
import java.util.Properties

/**
 * The CrystalGraphics version this build runs against: `modVersion` from the included build's own
 * `gradle.properties`, which its Release workflow sets.
 *
 * ```kotlin
 * Dependency("crystalgraphics", "[${crystalGraphicsVersion()},)", ordering = Ordering.AFTER)
 * consumerApi("com.crystalgraphics:core:${crystalGraphicsVersion()}")
 * ```
 *
 * Read by anything that WRITES the version somewhere another build or a loader reads it -- published
 * metadata, a mod descriptor's dependency range. Inside this build the composite substitutes
 * CrystalGraphics whatever version a coordinate names, so a wrong one there fails nothing until a player
 * or a consumer meets it.
 */
fun Project.crystalGraphicsVersion(): String {
    val file = gradle.includedBuild("CrystalGraphics").projectDir.resolve("gradle.properties")
    return Properties().apply { file.reader().use(::load) }.getProperty("modVersion")
        ?: error("no modVersion in $file")
}
