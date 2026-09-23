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

    plugins {
        id("com.gtnewhorizons.gtnhconvention") version("2.0.20")
        id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")

        // The 1.20.x loader scripts request these with no version, so the pins live here. moddev
        // matches runtime/mc/modern/build-logic's net.neoforged:moddev-gradle:2.0.141 -- one version, one
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
        // For the 1.20.x loader plugins. No exclusiveContent: ModDevGradle and Loom add their own
        // buildscript.repositories at configuration time, which Gradle 9 forbids while it is active.
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

// The multi-version preprocessor the 1.20.x loaders are built with (J10 proved it, J11 ships from it).
// A SETTINGS plugin -- the pins above are for project plugins and cannot apply this one. Stonecutter
// requires Gradle 9.0+; this build is 9.5.1.
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
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

// NO TIER-1 MODULES HERE. They existed briefly and held one class between them, the cursor adapters,
// which are CrystalGraphics' now: a cursor is a toolkit's job and this engine only decides WHICH one.
// `core`'s CursorService turns a keyword into a picture and hands it to CgCursorService, so no host
// of ours names a cursor service, an adapter, or the LWJGL module either lives in.

// The MC 1.7.10 loader.
//
// `include`, NOT `includeBuild` -- this line read `//includeBuild("mc1710")` for months and could never
// have worked: includeBuild needs the directory to be a standalone Gradle build with its own settings
// file, and runtime/mc/1710/settings.gradle does not exist and never has (`git log --all` over that path is
// empty, and it is not gitignored either). `git log -L` finds the configuration that actually launched
// a client, in 2a10724, and it is a plain subproject. See plan/platform-mc1710.md 25.2.
if (!embedded) include("runtime:mc:1710")

// ── The MC 1.20.x loaders: one source tree, a node per Minecraft version (J11) ───────────────────
//
// Stonecutter, BRANCHED. Each loader is a branch -- `runtime/mc/modern/<loader>/` holds the shared
// `src/` and one build script -- and each Minecraft version it targets is a NODE,
// `:runtime:mc:modern:<loader>:<version>`, whose project directory is `<loader>/versions/<version>/`
// and whose sources are the branch's, passed through comment directives (`//? if >=1.20.2 {`).
//
// `common` is versioned too, and not optionally: every loader node compiles against the common node of
// ITS OWN version, so NeoForge (1.20.4) has a 1.20.4 common under it rather than borrowing 1.20.1's.
//
// A node's pins are its own `versions/<version>/gradle.properties`. ADDING A TARGET is a version below
// plus that file; nothing else in the build names a node -- cgbuildlogic.ModernTree finds them.
//
// Embedded, only what a 1.20.1 Forge consumer takes: common and forge at 1.20.1. fabric pulls
// fabric-loom, which refuses a Gradle daemon below Java 21, and 1.20.4 is no 1.20.1 consumer's business.
val modernNodes: Map<String, List<String>> =
    if (embedded) linkedMapOf("common" to listOf("1.20.1"), "forge" to listOf("1.20.1"))
    else linkedMapOf(
        "common" to listOf("1.20.1", "1.20.4", "1.21.1"),
        "forge" to listOf("1.20.1"),
        "neoforge" to listOf("1.20.4", "1.21.1"),
        "fabric" to listOf("1.20.1", "1.20.4", "1.21.1"),
    )

// Read by composite.settings.gradle.kts, which substitutes CrystalGraphics' node of each version --
// so this table is declared before that script is applied.
extra["cgModernNodes"] = modernNodes

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

//include(":CrystalGraphics")
//include(":CrystalGraphics:core")
//include(":CrystalGraphics:platform")
//include(":CrystalGraphics:freetype-msdfgen-harfbuzz-bindings")

stonecutter {
    create("runtime:mc:modern") {
        // EVERY branch declares its own versions and the tree declares none: a tree-level `versions`
        // is inherited by a branch that names none, and also creates a node on the tree itself, with
        // no build script, that builds nothing and still costs configuration.
        modernNodes.forEach { (branchName, nodeVersions) ->
            branch(branchName) { versions(*nodeVersions.toTypedArray()) }
        }
    }
}
