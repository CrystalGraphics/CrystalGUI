// The `common` branch: everything the 1.20.x loaders share, and nothing any one of them owns. Built
// once per Minecraft version the tree targets -- `:runtime:mc:modern:common:<version>` -- and every
// loader node compiles against the common node of its own version.
//
// The toolchain and every pin come from the node (`versions/<version>/gradle.properties`, read by
// cg-modern-common), so this script carries nothing version-specific.

plugins {
    id("cg-modern-common")
}

base { archivesName.set("crystalgui-common-${project.name}") }

// Adds CrystalGraphics' compile-time deps (core, platform, its modern common, freetype) via composite
// substitution -- the same as the three loader branches. Common names CrystalGraphics' platform types
// to compile its platform service adapter code.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())
