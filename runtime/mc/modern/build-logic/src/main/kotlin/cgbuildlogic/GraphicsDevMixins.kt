package cgbuildlogic

import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The program arguments that apply CrystalGraphics' mixins in this Forge node's dev run.
 *
 * ```kotlin
 * runs { create("client") { client(); programArguments.addAll(graphicsDevMixinArgs()) } }   // legacyForge
 * tasks.named<JavaExec>("runClient") { args(graphicsDevMixinArgs()) }                       // Unimined
 * ```
 *
 * - Forge takes a mod's configs from its jar manifest's `MixinConfigs` alone; a dev run's mod is folders,
 *   so without these no CrystalGraphics mixin applies. NeoForge and Fabric read their descriptors and need none.
 * - Empty for a node whose CrystalGraphics node pins no `variant.mixinPlugin`.
 */
fun Project.graphicsDevMixinArgs(): List<String> {
    val node = sameVersionNodeDir(gradle.includedBuild("CrystalGraphics").projectDir, modernLoader)
    val pins = Properties()
    File(node, "gradle.properties").takeIf { it.isFile }?.reader()?.use(pins::load)
    if (pins.getProperty(MIXIN_PLUGIN) == null) return emptyList()
    return listOf("--mixin.config", nodeMixinConfig(nodePackage("com.crystalgraphics.mc.modern.$modernLoader", name)))
}
