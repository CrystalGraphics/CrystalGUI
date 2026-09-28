// The `forge` branch. To 1.20.1 through ModDevGradle's legacyForge; from 1.20.2 through NeoForm with
// Forge's jars on compileOnly, since no Gradle 9 toolchain sets those versions up.
import cgbuildlogic.CheckThinJar
import cgbuildlogic.registerSrgReobf
import cgbuildlogic.registerThinRename
import cgbuildlogic.stubMode
import cgbuildlogic.useForgeApi
import cgbuildlogic.useModernMinecraft
import cgbuildlogic.usesLegacyForge
import net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

apply(from = rootDir.resolve("runtime/mc/modern/node.gradle.kts"))

useModernMinecraft()
if (!stubMode && !usesLegacyForge) useForgeApi()

apply(from = rootDir.resolve("runtime/mc/modern/loader.gradle.kts"))

// Forge to 1.20.4 runs SRG member names, so the thin jar is renamed; from 1.20.6 it ships as compiled.
val main = sourceSets.main.get()
val thinJar = registerThinRename("thinShadowJar", "thin") {
    if (usesLegacyForge) the<ObfuscationExtension>().reobfuscate(tasks.named<AbstractArchiveTask>("thinShadowJar"), main) {
        archiveClassifier.set("thin")
    }
    else registerSrgReobf("thinShadowJar", "thin", main.compileClasspath)
}
tasks.named<CheckThinJar>("checkThinJar") { jar.set(thinJar.flatMap { it.archiveFile }) }
tasks.named("assemble") { dependsOn(thinJar) }
