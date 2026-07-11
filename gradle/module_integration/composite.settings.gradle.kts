// Single source of truth for all composite mod submodule integration.
// Data is stored on gradle.ext so project-time scripts (integration.gradle.kts) can read it.

val submoduleData = listOf(
    mapOf<String, Any>(
        "name" to "CrystalGraphics",
        "buildPath" to "CrystalGraphics",

        // mc1710 dev dependency — added via integration.gradle.kts to devOnlyNonPublishable.
        "devDependencies" to listOf("com.crystalgraphics:crystalgraphics:1.0.0"),

        // mc1201 compile-time dependencies — CrystalGraphics subprojects that mc1201 loader
        // sources import directly. Added as compileOnly + runtimeOnly by integration.gradle.kts.
        // Substitution rules below resolve these to CrystalGraphics' composite build projects.
        "mc1201CompileDeps" to listOf(
            "com.crystalgraphics:core:1.0.0",
            "com.crystalgraphics:platform:1.0.0",
            "com.crystalgraphics:crystalgraphics-mc1201-common:1.0.0",
            "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0"
        ),

        // Composite build substitution rules. All are resolved within the CrystalGraphics
        // includeBuild — projectPath values are relative to that build root.
        "substitutions" to listOf(
            mapOf("module" to "com.crystalgraphics:crystalgraphics",
                "projectPath" to ":mc1710"),
            mapOf("module" to "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings",
                "projectPath" to ":freetype-msdfgen-harfbuzz-bindings"),
            mapOf("module" to "com.crystalgraphics:core",
                "projectPath" to ":core"),
            mapOf("module" to "com.crystalgraphics:platform",
                "projectPath" to ":platform"),
            mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-common",
                "projectPath" to ":mc1201:common")
        ),

        // mc1710-specific bootstrap args injected into RunMinecraftTask by integration.gradle.kts.
        "coremods" to listOf("com.crystalgraphics.mc.coremod.CrystalGraphicsCoremod"),
        "tweakClasses" to listOf("org.spongepowered.asm.launch.MixinTweaker"),
        "mixinConfigs" to listOf("mixins.crystalgraphics.json")
    )
)

// Store on root project extra so project-time scripts (integration.gradle.kts) can read it.
// Uses projectsLoaded callback because Settings scripts can't access rootProject directly.
gradle.projectsLoaded {
    rootProject.extra["submoduleMods"] = submoduleData
}

fun Map<String, *>.string(key: String): String = this[key] as String

@Suppress("UNCHECKED_CAST")
fun Map<String, *>.mapList(key: String): List<Map<String, String>> =
    this[key] as? List<Map<String, String>> ?: emptyList()

submoduleData.forEach { mod ->
    includeBuild(mod.string("buildPath")) {
        dependencySubstitution {
            mod.mapList("substitutions").forEach { substitution ->
                substitute(module(substitution.getValue("module"))).using(project(substitution.getValue("projectPath")))
            }
        }
    }
}
