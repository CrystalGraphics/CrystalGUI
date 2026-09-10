// `java` is the JavaPluginExtension in a build script, so the PACKAGE has to be imported to be named.
import java.util.Properties

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

    // What this mod says about itself, declared once and printed into every format the merged jar
    // needs. Applied to the ROOT because the merged descriptors describe every loader at once and
    // belong to no one of them.
    id("cg-descriptors")

    // The merge: four thin jars and one engine into the artifact every loader installs.
    id("cg-single-jar")
}

// Machine-local settings (`local.properties`, gitignored) onto every project's `extra`, before
// anything reads one. @see gradle/local-settings.gradle.kts
apply(from = rootProject.file("gradle/local-settings.gradle.kts").toURI())

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
// ── Every single jar into every real client (J5, J8) ─────────────────────────────────────────────
//
// One task rather than four, because there is now one artifact per mod rather than one per loader.
// CrystalGraphics goes with it: CrystalGUI does not run without it, and shipping one of a matched
// pair is how an afternoon disappears.
//
// `-PcgNoLanguage` leaves `crystalgui_lang` out, which is the degraded configuration J8 exists to
// make possible and therefore the one worth being able to run: the editor opens, colours from core's
// built-in lexers, and the log says the stack is absent.
//
// The instance directories live in `local.properties`, which is gitignored. UNSET IS NORMAL -- a
// fresh clone has none and must still build -- so a missing key skips that instance and says so.
val cgWithLanguage = !providers.gradleProperty("cgNoLanguage").isPresent

val deploySingleJars = tasks.register("deploySingleJars") {
    group = "crystalgui"
    description = "Puts every single jar into every Prism instance named in local.properties."

    val crystalGraphics = gradle.includedBuild("CrystalGraphics")
    dependsOn("checkSingleJar", crystalGraphics.task(":checkSingleJar"))
    if (cgWithLanguage) dependsOn("checkLanguageJar")

    val guiJar = layout.buildDirectory.file("libs/crystalgui-$version.jar")
    val langJar = layout.buildDirectory.file("libs/crystalgui_lang-$version.jar")
    val graphicsJar = File(crystalGraphics.projectDir, "build/libs/crystalgraphics-$version.jar")
    val withLanguage = cgWithLanguage
    val localProperties = rootProject.file("local.properties")
    val instanceKeys = listOf(
        "prismLauncher1710Dir", "prismLauncher1201ForgeDir",
        "prismLauncher1204NeoForgeDir", "prismLauncher1201FabricDir",
    )

    doLast {
        if (!localProperties.isFile) {
            logger.lifecycle("[cgui] no local.properties; nothing to deploy to")
            return@doLast
        }
        val settings = Properties().apply { localProperties.inputStream().use { load(it) } }
        val jars = buildList {
            add(guiJar.get().asFile)
            if (withLanguage) add(langJar.get().asFile)
            add(graphicsJar)
        }
        jars.filterNot { it.isFile }.forEach { throw GradleException("${it.name} was not built") }
        if (!withLanguage) logger.lifecycle("[cgui] -PcgNoLanguage: crystalgui_lang is NOT deployed")

        instanceKeys.forEach { key ->
            val dir = settings.getProperty(key)
            if (dir.isNullOrBlank()) {
                logger.lifecycle("[cgui] {} is not set; skipping", key)
                return@forEach
            }
            val mods = File(dir, ".minecraft/mods")
            mods.mkdirs()
            // ONLY OURS. UniMixins on 1.7.10 and fabric-api on Fabric live here too and are not ours
            // to delete -- and the names being deleted include the RETIRED per-loader ones, so an
            // instance that had `crystalgui-mc1201-forge-...-srg.jar` does not end up with both.
            //
            // `crystalgui_lang-` is listed in its own right: it is not a prefix of `crystalgui-`, so
            // without it `-PcgNoLanguage` would leave the previous run's language jar installed and
            // measure the wrong configuration.
            val oursPrefixes = listOf("crystalgui-", "crystalgui_lang-", "crystalgraphics-")
            mods.listFiles().orEmpty()
                .filter { file -> oursPrefixes.any { file.name.startsWith(it) } }
                .forEach { it.delete() }
            jars.forEach { source ->
                val target = File(mods, source.name)
                // A RUNNING CLIENT HOLDS ITS MOD JARS OPEN on Windows, and Kotlin's copyTo reports
                // that as "tried to overwrite the destination, but failed to delete it" -- which
                // names neither the instance nor the cause.
                if (target.exists() && !target.delete()) {
                    throw GradleException(
                        "${target.name} in $key is locked, so it cannot be replaced. A Minecraft "
                            + "client is still running from that instance; close it and run again.")
                }
                source.copyTo(target, overwrite = true)
            }
            logger.lifecycle("[cgui] {} -> {}", key, mods)
        }
    }
}

// ── Every installed client, driven (J6) ──────────────────────────────────────────────────────────
//
// The client half of the matrix. `checkSingleJar` asserts what the jar IS; this asserts that four
// real clients each draw from it. Sequential, because there is one GPU and one launcher.
//
//   ./gradlew prodSmoke
//   ./gradlew prodSmoke -PcgTargets=1710,1201forge
val prodSmoke = tasks.register<cgbuildlogic.ProdSmoke>("prodSmoke") {
    // -PcgNoDeploy drives whatever is ALREADY installed. Rebuilding and redeploying both single jars is
    // minutes and driving the clients is seconds, so paying for the first while iterating on the second
    // is most of the wall clock for no answer.
    if (!providers.gradleProperty("cgNoDeploy").isPresent) dependsOn(deploySingleJars)
    instances.set(listOf(
        "prismLauncher1710Dir=1710",
        "prismLauncher1201ForgeDir=1201forge",
        "prismLauncher1204NeoForgeDir=1204neoforge",
        "prismLauncher1201FabricDir=1201fabric",
    ))
    outputDir.set(layout.buildDirectory.dir("prodSmoke"))
    onlyTargets.set(
        (providers.gradleProperty("cgTargets").orNull ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() })
}

tasks.register("assembleConsumerRuntime") {
    group = "crystalgui"
    description = "Builds every jar a consuming mod's dev run puts on its classpath."

    dependsOn(":core:jar", ":taffy:jar", ":mc1201:common:jar", ":mc1201:forge:jar")

    listOf(":core:jar", ":platform:jar", ":freetype-msdfgen-harfbuzz-bindings:jar",
           ":mc1201:common:jar", ":mc1201:forge:jar")
        .forEach { dependsOn(gradle.includedBuild("CrystalGraphics").task(it)) }
}
