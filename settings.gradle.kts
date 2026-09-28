pluginManagement {
    // Pins load plugin JARs into the settings classloader so all subprojects share the same
    // RetroFuturaGradle classes -- required by Gradle build services.
    //
    // gtnhconvention and gtnhsettingsconvention are PINNED, NOT APPLIED, so :runtime:mc:1710 can say
    // id("com.gtnewhorizons.gtnhconvention") with no version. Applying gtnhsettingsconvention here
    // would inject spotless onto every subproject's buildscript classpath -- including :core and
    // :language, which are ordinary Java modules and are not GTNH builds.
    // Supplies the 1.20.x convention plugins (cg-java, cg-modern-common, cg-modern-loader). Without
    // it every 1.20.x node fails at id("cg-modern-loader") with "plugin not found".
    includeBuild("runtime/mc/modern/build-logic")
    // The settings plugin below: the Minecraft nodes, from the versions targeted.
    includeBuild("CrystalGraphics/singlejar-logic")

    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")

        // The 1.20.x loader scripts request these with no version, so the pins live here. moddev
        // matches runtime/mc/modern/build-logic's net.neoforged:moddev-gradle:2.0.141 -- one version, one
        // spelling. No net.neoforged.moddev.repositories settings plugin pins them: nothing applies
        // that plugin in either repository.
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
        // For the 1.20.x loader plugins. No exclusiveContent: ModDevGradle and Loom add their own
        // buildscript.repositories at configuration time, which Gradle 9 forbids while it is active.
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "Unimined" }
    }
}

// The multi-version preprocessor the 1.20.x loaders are built with (J10 proved it, J11 ships from it).
// A SETTINGS plugin -- the pins above are for project plugins and cannot apply this one. Stonecutter
// requires Gradle 9.0+; this build is 9.5.1.
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("com.crystalgraphics.singlejar")
}

rootProject.name = "CrystalGUI"

// Included in another build means a consumer wants the engine and the loader it can actually run:
// :runtime:mc:1710 needs a Java 25 Gradle daemon and :gl-debug-harness pulls LWJGL3, so those stay home. The
// MC 1.20.1 Forge pair crosses the boundary -- see the :runtime:mc:modern block below.
val embedded = gradle.parent != null

// The layout engine, VENDORED as a fork rather than consumed as `dev.vfyjxf:taffy:1.1.4`.
//
// Its leaf-measure path is wrong under `flex-wrap: wrap` -- a measured leaf is sized against the
// CONTAINER's inner main size rather than its own flex-resolved one, so it reports a height for a
// width it was never given and the last line is clipped. One line inside the flexbox algorithm, so
// it is only fixable here. MIT, so forking is permitted; taffy/MODIFICATIONS.md is the statement of
// changes MIT requires, and plan/engine-rewrite.md D3 is the decision.
include("taffy")

include("core")
// A consumer authoring stylesheets wants the harness, and it pulls LWJGL3 -- so it is opt-in rather
// than on for every embedded build.
//
// A SYSTEM property, which is the only channel that crosses into an included build's SETTINGS script:
// a composite gives each build its own StartParameter, so neither -P nor the parent's gradle.properties
// reaches here. RPG-Core's settings.gradle sets it; -Dcrystalgui.harness=true works too.
val harnessRequested = System.getProperty("crystalgui.harness") == "true"
if (!embedded || harnessRequested) include("gl-debug-harness")

// The tree-sitter syntax backend. Its jars are checked in under lib/tree-sitter/, so this is an ordinary
// module rather than one conditional on a local checkout -- see that directory's README for why.
//
// It stays a SEPARATE module regardless: core/ must remain loadable on a dedicated server with no GL and
// no native libraries, so core/ owns the SyntaxTokenizer interface and nothing that needs a .dll/.so.
include("language")

// What every loader variant in the single jar shares: the mixin config plugin that decides whose
// mixins may apply, and the loader probe under it. Java 8, one dependency (Mixin, compileOnly), and
// no Minecraft type at all -- so it is included unconditionally, embedded or not, and needs none of
// the toolchains the loader modules below do.
include("runtime:mc:shared")

// The @Mod classes every Forge constructs -- modern and legacy FML scan for the same annotation --
// compiled once against CrystalGraphics' forge-stubs.
include("runtime:mc:forge-bootstrap")

// What the two LaunchWrapper hosts share -- 1.7.10 and Forge 1.8 to 1.12.2 -- merged once into the
// language jar. LaunchWrapper's API never changed, so one copy serves both.
include("runtime:mc:launchwrapper")

// Its modern counterpart: what every ModLauncher and Knot node shares and names no Minecraft, merged once
// into the language jar instead of once per node.
include("runtime:mc:modern-shared")

// NO TIER-1 MODULES HERE. They existed briefly and held one class between them, the cursor adapters,
// which are CrystalGraphics' now: a cursor is a toolkit's job and this engine only decides WHICH one.
// `core`'s CursorService turns a keyword into a picture and hands it to CgCursorService, so no host
// of ours names a cursor service, an adapter, or the LWJGL module either lives in.

// ── The Minecraft nodes ──────────────────────────────────────────────────────────────────────────
//
// The versions this build ships, resolved against singlejar-logic's pin catalog into the 1.7.10 host,
// the legacy tree (Forge 1.8-1.12.2) and the modern tree -- a Stonecutter tree, `common` plus a branch
// per loader, a node per Minecraft version, whose sources pass through comment directives
// (`//? if >=1.20.2 {`). Adding a version is a catalog entry the ranges below reach.
//
// Embedded, only what the including build can configure: the node its target needs. @see
// cgbuildlogic.SingleJarSettings
singlejar {
    targets {
        forge("1.7.10".."1.21.11")
        neoforge("1.20.2".."1.21.11")
        fabric("1.14.4".."1.21.11")
    }
}

// Read by composite.settings.gradle.kts, which substitutes CrystalGraphics' node of each version.
extra["cgModernNodes"] = singlejar.modernNodes

// CrystalGraphics, and the ONE place it is included from.
//
// This file used to carry its own includeBuild("CrystalGraphics") block with three substitutions.
// composite.settings.gradle.kts declares the same build with FIVE -- adding
// com.crystalgraphics:crystalgraphics -> :runtime:mc:1710, which is how the loader resolves the CrystalGraphics
// *mod* rather than its libraries. Two includeBuilds of one path is a configuration error, so the
// smaller block is gone and this is the survivor: its list is a strict superset, and it is also where
// integration.gradle.kts (applied by runtime/mc/1710/build.gradle.kts) reads its `submoduleMods` data from.
// Applied even when embedded: :core takes com.crystalgraphics:core and :platform as compileOnly.
apply(from = "gradle/module_integration/composite.settings.gradle.kts")

// The consumer plugins, `com.crystalgui` and `com.crystalgui.settings`: a build of their own, which
// publishes with this one. Never included into a consumer, whose own request for them it would answer.
if (!embedded) includeBuild("consumer-plugin")

//include(":CrystalGraphics")
//include(":CrystalGraphics:core")
//include(":CrystalGraphics:platform")
//include(":CrystalGraphics:freetype-msdfgen-harfbuzz-bindings")

