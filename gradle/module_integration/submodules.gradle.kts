// Root-side source of truth for all composite mod submodule integration.
// Add new submodules here; settings/build integration scripts consume this data.

extra["submoduleMods"] = listOf(
    mapOf<String, Any>(
        "name" to "CrystalGraphics",
        "buildPath" to "CrystalGraphics",

        // mc1710 dev dependency — added via integration.gradle to devOnlyNonPublishable.
        "devDependencies" to listOf("com.crystalgraphics:crystalgraphics:1.0.0"),

        // mc1201 compile-time dependencies — CrystalGraphics subprojects that mc1201 loader
        // sources import directly. Added as compileOnly + runtimeOnly by mc1201-integration.gradle.
        // Substitution rules below resolve these to CrystalGraphics' composite build projects.
        //
        "mc1201CompileDeps" to listOf(
            "com.crystalgraphics:core:1.0.0",
            "com.crystalgraphics:platform:1.0.0",
            "com.crystalgraphics:crystalgraphics-mc1201-common:1.0.0",
            "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0"
        ),

        // Composite build substitution rules. All are resolved within the CrystalGraphics
        // includeBuild — projectPath values are relative to that build root.
        "substitutions" to listOf(
            // mc1710 — the main crystalgraphics artifact
            mapOf("module" to "com.crystalgraphics:crystalgraphics",
                "projectPath" to ":mc1710"),
            // Shared JNI bindings (freetype / MSDFgen / HarfBuzz)
            mapOf("module" to "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings",
                "projectPath" to ":freetype-msdfgen-harfbuzz-bindings"),
            // mc1201 compile-time subprojects
            mapOf("module" to "com.crystalgraphics:core",
                "projectPath" to ":core"),
            mapOf("module" to "com.crystalgraphics:platform",
                "projectPath" to ":platform"),
            mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-common",
                "projectPath" to ":mc1201:common")
        ),

        // mc1710-specific bootstrap args injected into RunMinecraftTask by integration.gradle.
        "coremods" to listOf("com.crystalgraphics.mc.coremod.CrystalGraphicsCoremod"),
        "tweakClasses" to listOf("org.spongepowered.asm.launch.MixinTweaker"),
        "mixinConfigs" to listOf("mixins.crystalgraphics.json")
    )
)
