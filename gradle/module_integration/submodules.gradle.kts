// Root-side source of truth for all composite mod submodule integration.
// Add new submodules here; settings/build integration scripts consume this data.

extra["submoduleMods"] = listOf(
    mapOf<String, Any>(
        "name" to "CrystalGraphics",
        "buildPath" to "CrystalGraphics",
        "devDependencies" to listOf("io.github.somehussar.crystalgraphics:crystalgraphics:1.0.0"),
        "substitutions" to listOf(
            mapOf("module" to "io.github.somehussar.crystalgraphics:crystalgraphics",
                "projectPath" to ":"),
            mapOf("module" to "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings",
                "projectPath" to ":freetype-msdfgen-harfbuzz-bindings")
        ),
        "coremods" to listOf("io.github.somehussar.crystalgraphics.mc.coremod.CrystalGraphicsCoremod"),
        "tweakClasses" to listOf("org.spongepowered.asm.launch.MixinTweaker"),
        "mixinConfigs" to listOf("mixins.crystalgraphics.json")
    )
)
