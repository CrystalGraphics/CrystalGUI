// The `neoforge` branch, through ModDevGradle. NeoForge runs Mojang's names, so the thin jar ships as
// compiled.
import cgbuildlogic.CheckThinJar
import cgbuildlogic.stubMode
import cgbuildlogic.useNeoForgeApi
import net.neoforged.moddevgradle.dsl.NeoForgeExtension

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

apply(from = rootDir.resolve("runtime/mc/modern/node.gradle.kts"))

// A node pinning neoform.version too (NeoForge 20.2, 20.3) is built from parts: ModDevGradle sets none up.
val fromParts = findProperty("neoform.version") != null
if (!stubMode) {
    pluginManager.apply("net.neoforged.moddev")
    if (fromParts) useNeoForgeApi()
    configure<NeoForgeExtension> {
        if (fromParts) neoFormVersion = property("neoform.version").toString()
        else version = property("neoforge.version").toString()
    }
}

apply(from = rootDir.resolve("runtime/mc/modern/loader.gradle.kts"))

val thinJar = tasks.named<AbstractArchiveTask>("thinShadowJar") { archiveClassifier.set("thin") }
tasks.named<CheckThinJar>("checkThinJar") { jar.set(thinJar.flatMap { it.archiveFile }) }
tasks.named("assemble") { dependsOn(thinJar) }
