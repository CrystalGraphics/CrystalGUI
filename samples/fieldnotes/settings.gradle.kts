// Field Notes: a CrystalGUI application shipped as ONE jar for several loaders and Minecraft versions.
// The shape of a platform-abstract project -- a Minecraft-free core, a Stonecutter tree of loader
// nodes -- on singlejar-logic's `targets {}`. README.md walks through it.
pluginManagement {
    // The build logic: the settings plugin below, the merge, the descriptors, the stub database.
    includeBuild("../../CrystalGraphics/singlejar-logic")
    // This sample builds against the clone it sits in, so it takes that clone's version. A project of
    // its own writes the release it wants: id("com.crystalgui") version "<version>".
    // Read by hand: this block compiles before the script's imports exist.
    val crystalgui = file("../../gradle.properties").readLines()
        .first { it.startsWith("modVersion") }.substringAfter('=').trim()
    plugins { id("com.crystalgui") version crystalgui }
    repositories {
        mavenLocal()            // com.crystalgui, after `./gradlew publishToMavenLocal` in CrystalGUI
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("com.crystalgraphics.singlejar")
}

rootProject.name = "fieldnotes"

include("core")

// The versions this mod ships, and nothing else about them: each becomes a node with its toolchain
// pinned by the catalog, and a node that compiles lands in the jar.
singlejar {
    targets {
        forge("1.20.1")
        neoforge("1.21.1")
        fabric("1.20.1".."1.20.4")
    }
}
