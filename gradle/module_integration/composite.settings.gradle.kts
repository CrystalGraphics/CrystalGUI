// Single source of truth for all composite mod submodule integration.
// Data is stored on gradle.ext so project-time scripts (integration.gradle.kts) can read it.

val submoduleData = listOf(
    mapOf<String, Any>(
        "name" to "CrystalGraphics",
        "buildPath" to "CrystalGraphics",

        // mc1710 dev dependency — added via integration.gradle.kts to devOnlyNonPublishable.
        "devDependencies" to listOf("com.crystalgraphics:crystalgraphics:1.0.0"),

        // CrystalGraphics' libraries every 1.20.x node imports. Added as compileOnly + runtimeOnly by
        // integration.gradle.kts; the substitutions below resolve them to projects. Its COMMON is not
        // here: that is per Minecraft version, so each node names the one of its own version
        // (cgbuildlogic.sameVersionNodeCoordinate) and `modernNodeSubstitutions` below maps it.
        "modernCompileDeps" to listOf(
            "com.crystalgraphics:core:1.0.0",
            "com.crystalgraphics:platform:1.0.0",
            "com.crystalgraphics:mc-shared:1.0.0",
            "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0"
        ),

        // Composite build substitution rules. All are resolved within the CrystalGraphics
        // includeBuild — projectPath values are relative to that build root.
        "substitutions" to listOf(
            mapOf("module" to "com.crystalgraphics:crystalgraphics",
                "projectPath" to ":runtime:mc:1710"),
            mapOf("module" to "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings",
                "projectPath" to ":freetype-msdfgen-harfbuzz-bindings"),
            mapOf("module" to "com.crystalgraphics:core",
                "projectPath" to ":core"),
            mapOf("module" to "com.crystalgraphics:platform",
                "projectPath" to ":platform"),
            // Tier 1 for LWJGL2 (plan/crystalgui/platform-single-jar.md §12). The harness takes it for
            // the cursor adapter, which is toolkit code and not Minecraft's -- so the harness stops
            // carrying a copy of one.
            mapOf("module" to "com.crystalgraphics:lwjgl2",
                "projectPath" to ":runtime:lwjgl:2"),
            // Tier 1 for LWJGL3, and it was missing: J9 extracted Lwjgl3GLBackend OUT of
            // runtime/mc/modern/common into its own module, and nothing here followed it, so a dev run
            // resolved every other CrystalGraphics module and not this one.
            mapOf("module" to "com.crystalgraphics:lwjgl3",
                "projectPath" to ":runtime:lwjgl:3"),
            // LoaderProbe and CrashVariant. CrystalGUI's hosts use CrystalGraphics' copies rather than
            // carrying their own; not a loader path, so it survives an embedded build.
            mapOf("module" to "com.crystalgraphics:mc-shared",
                "projectPath" to ":runtime:mc:shared"),
            // The 1.20.1 Forge MOD, for a consumer that wants CrystalGraphics in its own dev run's mod
            // list rather than merely on its compile classpath -- RPG-Core names this coordinate.
            mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-forge",
                "projectPath" to ":runtime:mc:modern:forge:1.20.1")
        ),

        // mc1710-specific bootstrap args injected into RunMinecraftTask by integration.gradle.kts.
        //
        // NO COREMOD. CrystalGraphicsCoremod and the whole ASM redirect layer were deleted on
        // 2026-07-31 -- see CrystalGraphics/AGENTS.md "GL state" for why the GL mirror could never be
        // made reliable and what replaced it. `coreModClass` is correspondingly empty in
        // CrystalGraphics/runtime/mc/1710/gradle.properties.
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
// configuration for every task -- "Project with path ':runtime:mc:modern:common' not found in build
// ':CrystalGUI:CrystalGraphics'". Matched on the path so a loader added later is covered.
val embeddedHere = gradle.parent != null

fun isLoaderPath(projectPath: String): Boolean = projectPath == ":runtime:mc:1710"

// CrystalGraphics' COMMON NODE of every version this build has a common node for -- which it must
// have, since CrystalGraphics goes first (D1). The coordinate is its node coordinate:
// `<modGroup>.mc.modern.<branch>:<version>`, @see cgbuildlogic.useNodeCoordinates. A settings script
// cannot reach build-logic's classes, so the format is spelled here as well; the build breaks at
// resolution, naming the coordinate, if the two ever disagree.
@Suppress("UNCHECKED_CAST")
val modernNodes = extra["cgModernNodes"] as Map<String, List<String>>
val modernNodeSubstitutions: List<Map<String, String>> = modernNodes.getValue("common").map { version ->
    mapOf("module" to "com.crystalgraphics.mc.modern.common:$version",
        "projectPath" to ":runtime:mc:modern:common:$version")
}

submoduleData.forEach { mod ->
    includeBuild(mod.string("buildPath")) {
        dependencySubstitution {
            (mod.mapList("substitutions") + modernNodeSubstitutions)
                .filterNot { embeddedHere && isLoaderPath(it.getValue("projectPath")) }
                .forEach { substitution ->
                    substitute(module(substitution.getValue("module"))).using(project(substitution.getValue("projectPath")))
                }
        }
    }
}
