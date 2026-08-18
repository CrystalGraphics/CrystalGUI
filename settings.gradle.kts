pluginManagement {
    // Pins load plugin JARs into the settings classloader so all subprojects share the same
    // RetroFuturaGradle classes -- required by Gradle build services.
    //
    // gtnhconvention and gtnhsettingsconvention are PINNED, NOT APPLIED, so :mc1710 can say
    // id("com.gtnewhorizons.gtnhconvention") with no version. Applying gtnhsettingsconvention here
    // would inject spotless onto every subproject's buildscript classpath -- including :core and
    // :language, which are ordinary Java modules and are not GTNH builds.
    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
    }

    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
    }
}

rootProject.name = "CrystalGUI"

include("core")
include("gl-debug-harness")

// The tree-sitter syntax backend. Its jars are checked in under lib/tree-sitter/, so this is an ordinary
// module rather than one conditional on a local checkout -- see that directory's README for why.
//
// It stays a SEPARATE module regardless: core/ must remain loadable on a dedicated server with no GL and
// no native libraries, so core/ owns the SyntaxTokenizer interface and nothing that needs a .dll/.so.
include("language")

// The MC 1.7.10 loader.
//
// `include`, NOT `includeBuild` -- this line read `//includeBuild("mc1710")` for months and could never
// have worked: includeBuild needs the directory to be a standalone Gradle build with its own settings
// file, and mc1710/settings.gradle does not exist and never has (`git log --all` over that path is
// empty, and it is not gitignored either). `git log -L` finds the configuration that actually launched
// a client, in 2a10724, and it is a plain subproject. See plan_m12.md 25.2.
include("mc1710")

// CrystalGraphics, and the ONE place it is included from.
//
// This file used to carry its own includeBuild("CrystalGraphics") block with three substitutions.
// composite.settings.gradle.kts declares the same build with FIVE -- adding
// com.crystalgraphics:crystalgraphics -> :mc1710, which is how the loader resolves the CrystalGraphics
// *mod* rather than its libraries. Two includeBuilds of one path is a configuration error, so the
// smaller block is gone and this is the survivor: its list is a strict superset, and it is also where
// integration.gradle.kts (applied by mc1710/build.gradle.kts) reads its `submoduleMods` data from.
apply(from = "gradle/module_integration/composite.settings.gradle.kts")

//include(":CrystalGraphics")
//include(":CrystalGraphics:core")
//include(":CrystalGraphics:platform")
//include(":CrystalGraphics:freetype-msdfgen-harfbuzz-bindings")
//includeBuild("mc1201")
