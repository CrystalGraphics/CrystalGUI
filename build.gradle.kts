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
    // Only breaks an IDE sync; a CLI build constructs no IDEA model. plan_mc1201.md L0.
    id("org.jetbrains.gradle.plugin.idea-ext")
}
