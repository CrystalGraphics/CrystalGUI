pluginManagement {
    // Pins load plugin JARs into the settings classloader so all subprojects share the same
    // RetroFuturaGradle classes -- required by Gradle build services.
    //
    // gtnhconvention and gtnhsettingsconvention are PINNED, NOT APPLIED, so :mc1710 can say
    // id("com.gtnewhorizons.gtnhconvention") with no version. Applying gtnhsettingsconvention here
    // would inject spotless onto every subproject's buildscript classpath -- including :core and
    // :language, which are ordinary Java modules and are not GTNH builds.
    // Supplies the mc1201 convention plugins (cg-java17, cg-mc1201-common, cg-mc1201-loader). Without
    // it every mc1201 subproject fails at id("cg-mc1201-loader") with "plugin not found".
    includeBuild("mc1201/build-logic")

    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")

        // The mc1201 loader scripts request these with no version, so the pins live here. moddev
        // matches mc1201/build-logic's net.neoforged:moddev-gradle:2.0.141 -- one version, one
        // spelling. (BUILD_SETUP.md claims a net.neoforged.moddev.repositories settings plugin pins
        // them; nothing applies that plugin in either repository. Corrected at L8.)
        id("com.gradleup.shadow") version("9.2.2")
        id("net.neoforged.moddev") version("2.0.141")
        id("net.neoforged.moddev.legacyforge") version("2.0.141")

        // Applied by the root build.gradle.kts; see there. 1.3 is what gtnhgradle resolves, and
        // ModDevGradle asks for 1.2 but uses only API 1.3 still carries.
        id("org.jetbrains.gradle.plugin.idea-ext") version("1.3")
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
        // For the mc1201 loader plugins. No exclusiveContent: ModDevGradle and Loom add their own
        // buildscript.repositories at configuration time, which Gradle 9 forbids while it is active.
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "CrystalGUI"

// Included in another build means a consumer wants the engine, not the loaders: :mc1710 needs a Java
// 25 daemon and :gl-debug-harness pulls LWJGL3.
val embedded = gradle.parent != null

// The layout engine, VENDORED as a fork rather than consumed as `dev.vfyjxf:taffy:1.1.4`.
//
// Its leaf-measure path is wrong under `flex-wrap: wrap` -- a measured leaf is sized against the
// CONTAINER's inner main size rather than its own flex-resolved one, so it reports a height for a
// width it was never given and the last line is clipped. One line inside the flexbox algorithm, so
// it is only fixable here. MIT, so forking is permitted; taffy/MODIFICATIONS.md is the statement of
// changes MIT requires, and plan_ui_rewrite.md D3 is the decision.
include("taffy")

include("core")
if (!embedded) include("gl-debug-harness")

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
if (!embedded) include("mc1710")

// CrystalGraphics, and the ONE place it is included from.
//
// This file used to carry its own includeBuild("CrystalGraphics") block with three substitutions.
// composite.settings.gradle.kts declares the same build with FIVE -- adding
// com.crystalgraphics:crystalgraphics -> :mc1710, which is how the loader resolves the CrystalGraphics
// *mod* rather than its libraries. Two includeBuilds of one path is a configuration error, so the
// smaller block is gone and this is the survivor: its list is a strict superset, and it is also where
// integration.gradle.kts (applied by mc1710/build.gradle.kts) reads its `submoduleMods` data from.
// Applied even when embedded: :core takes com.crystalgraphics:core and :platform as compileOnly.
apply(from = "gradle/module_integration/composite.settings.gradle.kts")

//include(":CrystalGraphics")
//include(":CrystalGraphics:core")
//include(":CrystalGraphics:platform")
//include(":CrystalGraphics:freetype-msdfgen-harfbuzz-bindings")

// The MC 1.20.x loaders. `include`, not `includeBuild` -- there is no mc1201/settings.gradle.kts, and
// the loader scripts already say project(":mc1201:common"). Same trap as mc1710; see plan_m12.md 25.2.
//
// :mc1201:common holds everything vanilla and the three loaders are registration only, reaching it
// through LoaderBridge. :mc1201:neoforge targets MC 1.20.4 -- NeoForge published no 20.1.x series
// (plan_mc1201.md 3.8).
//
// Gated like :mc1710: these download and decompile a Minecraft toolchain, which an embedding consumer
// has no use for.
if (!embedded) {
    include(":mc1201:common")
    include(":mc1201:forge")
    include(":mc1201:neoforge")
    include(":mc1201:fabric")
}
