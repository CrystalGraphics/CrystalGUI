// Root project — pure build coordinator. No source lives here.
//
// MC version subprojects:
//   :mc1710         — Minecraft 1.7.10 + Forge (LWJGL 2, gtnhconvention)
//   :mc1201:common  — the 1.20.x platform seam, shared by all three loaders
//   :mc1201:{forge,neoforge,fabric} — registration only
//
// Platform-agnostic subprojects:
//   :core     — platform-agnostic UI engine

plugins {
    idea
    // Applied here so the whole build shares one copy. gtnhgradle and ModDevGradle both want idea-ext
    // but request it under different Maven coordinates, so Gradle loads both classes -- and moddev's
    // `hasPlugin(IdeaExtPlugin.class)` guard names its own, applies a second copy, and the `settings`
    // extension collides. The root buildscript scope is the parent of every subproject's, so
    // parent-first loading gives both plugins the same class.
    //
    // Only breaks an IDE sync; a CLI build constructs no IDEA model. plan/platform-mc1201.md L0.
    id("org.jetbrains.gradle.plugin.idea-ext")
}

// ── Everything a consuming mod's dev run reads, built ────────────────────────────────────────────
//
// A mod that consumes CrystalGUI as a composite puts these jars on its game classpath, and nothing
// else in its build asks for them: Gradle compiles against a project's CLASSES variant, so no jar task
// ever enters the graph, and ModDevGradle's additionalRuntimeClasspath yields jar PATHS without
// registering the tasks that produce them. The consumer therefore runs whatever is on disk -- which
// meant a months-old crystalgui-mc1201-common, and a platform.jar predating a fix to CgGL that crashed
// on a source line that no longer existed.
//
// One task rather than a list the consumer maintains, and it lives here because only this build can
// reach CrystalGraphics: gradle.includedBuild("CrystalGraphics") is not resolvable from a build that
// includes US. A jar added to the consumer's classpath later is added here, not in every consumer.
tasks.register("assembleConsumerRuntime") {
    group = "crystalgui"
    description = "Builds every jar a consuming mod's dev run puts on its classpath."

    dependsOn(":core:jar", ":taffy:jar", ":mc1201:common:jar", ":mc1201:forge:jar")

    listOf(":core:jar", ":platform:jar", ":freetype-msdfgen-harfbuzz-bindings:jar",
           ":mc1201:common:jar", ":mc1201:forge:jar")
        .forEach { dependsOn(gradle.includedBuild("CrystalGraphics").task(it)) }
}
