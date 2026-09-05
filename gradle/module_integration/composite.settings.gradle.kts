// Single source of truth for all composite mod submodule integration.
// Data is stored on gradle.ext so project-time scripts (integration.gradle.kts) can read it.

val submoduleData = listOf(
    mapOf<String, Any>(
        "name" to "CrystalGraphics",
        "buildPath" to "CrystalGraphics",

        // mc1710 dev dependency â€” added via integration.gradle.kts to devOnlyNonPublishable.
        "devDependencies" to listOf("com.crystalgraphics:crystalgraphics:1.0.0"),

        // mc1201 compile-time dependencies â€” CrystalGraphics subprojects that mc1201 loader
        // sources import directly. Added as compileOnly + runtimeOnly by integration.gradle.kts.
        // Substitution rules below resolve these to CrystalGraphics' composite build projects.
        "mc1201CompileDeps" to listOf(
            "com.crystalgraphics:core:1.0.0",
            "com.crystalgraphics:platform:1.0.0",
            "com.crystalgraphics:crystalgraphics-mc1201-common:1.0.0",
            "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0"
        ),

        // Composite build substitution rules. All are resolved within the CrystalGraphics
        // includeBuild â€” projectPath values are relative to that build root.
        "substitutions" to listOf(
            mapOf("module" to "com.crystalgraphics:crystalgraphics",
                "projectPath" to ":mc1710"),
            mapOf("module" to "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings",
                "projectPath" to ":freetype-msdfgen-harfbuzz-bindings"),
            mapOf("module" to "com.crystalgraphics:core",
                "projectPath" to ":core"),
            mapOf("module" to "com.crystalgraphics:platform",
                "projectPath" to ":platform")
            // NO mc1201-common substitution. `:mc1201:common` is commented out of CrystalGraphics'
            // own settings.gradle.kts, and a dependencySubstitution naming a project that does not
            // exist in the target build fails CONFIGURATION outright -- "Project with path
            // ':mc1201:common' not found in build ':CrystalGraphics'" -- for every task, including
            // ones that have nothing to do with Minecraft. Restore it in the same edit that
            // uncomments mc1201 there.
        ),

        // mc1710-specific bootstrap args injected into RunMinecraftTask by integration.gradle.kts.
        //
        // NO COREMOD. CrystalGraphicsCoremod and the whole ASM redirect layer were deleted on
        // 2026-07-31 -- see CrystalGraphics/AGENTS.md "GL state" for why the GL mirror could never be
        // made reliable and what replaced it. `coreModClass` is correspondingly empty in
        // CrystalGraphics/mc1710/gradle.properties.
        //
        // The entry outlived the class by three months and would have been a hard launch failure the
        // next time anyone ran the client: FML reports it as a coremod class-load problem, which reads
        // as a CrystalGraphics bug rather than as stale build config. Left as an empty list rather than
        // deleted so the shape stays visible if a coremod is ever needed again.
        "coremods" to listOf<String>(),
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

// Loader substitutions go with the loaders. CrystalGraphics drops its own loaders when neither it nor
// CrystalGUI is the build being invoked, and a substitution naming a missing project then fails
// configuration for every task -- "Project with path ':mc1201:common' not found in build
// ':CrystalGUI:CrystalGraphics'". Matched on the path so a loader added later is covered.
val embeddedHere = gradle.parent != null

fun isLoaderPath(projectPath: String): Boolean =
    projectPath == ":mc1710" || projectPath.startsWith(":mc1201")

submoduleData.forEach { mod ->
    includeBuild(mod.string("buildPath")) {
        dependencySubstitution {
            mod.mapList("substitutions")
                .filterNot { embeddedHere && isLoaderPath(it.getValue("projectPath")) }
                .forEach { substitution ->
                    substitute(module(substitution.getValue("module"))).using(project(substitution.getValue("projectPath")))
                }
        }
    }
}
