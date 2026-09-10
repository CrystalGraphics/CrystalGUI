// ⚠ TOOLCHAIN NOTE: NeoForge did not publish artifacts for Minecraft 1.20.1 (version 20.1.x).
// The NeoForge Maven shows the earliest available series as 20.2.x (MC 1.20.2). Therefore,
// ModDevGradle NeoForm mode for 1.20.1 cannot be used — there is no
// net.neoforged:neoform:1.20.1-* artifact. This subproject compiles as a plain Java-17
// library against the shared :platform and :core modules. When we upgrade to 1.20.2+,
// replace 'cg-mc1201-common' with the full ModDevGradle NeoForm setup.

plugins {
    id("cg-mc1201-common")
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc1201-common") }

// Adds CrystalGraphics compile-time deps (core, platform, mc1201-common, freetype) via
// composite substitution — same as the three loader subprojects. mc1201:common needs to
// see CrystalGraphics' platform types to compile its platform service adapter code.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())
