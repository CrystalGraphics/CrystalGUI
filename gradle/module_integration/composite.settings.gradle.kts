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
            // Must be added and removed in the same commit as :mc1201:common in CrystalGraphics'
            // settings.gradle.kts: a substitution naming a project that is not in the target build
            // fails configuration for every task, and the error names the module, not this file.
            mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-common",
                "projectPath" to ":mc1201:common"),
            // The 1.20.1 Forge MOD, for a consumer that wants CrystalGraphics in its own dev run's mod
            // list rather than merely on its compile classpath.
            mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-forge",
                "projectPath" to ":mc1201:forge")
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

fun isLoaderPath(projectPath: String): Boolean = projectPath == ":mc1710"

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
