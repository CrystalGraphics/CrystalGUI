// The `fabric` branch, through Loom. Fabric runs intermediary names, so the thin jar is remapped.
import cgbuildlogic.CheckThinJar
import cgbuildlogic.registerThinRename
import cgbuildlogic.stubMode
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask

plugins {
    `java-library`
    id("fabric-loom") version "1.16.2" apply false     // applied on a real node only
    id("com.gradleup.shadow")
}

apply(from = rootDir.resolve("runtime/mc/modern/node.gradle.kts"))

if (!stubMode) {
    apply(plugin = "fabric-loom")
    val loom = the<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:${property("mc.version")}")
        "mappings"(loom.officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:${property("fabric.loader")}")
    }
}

apply(from = rootDir.resolve("runtime/mc/modern/loader.gradle.kts"))

val thinJar = registerThinRename("thinShadowJar", "thin", { LoomGradleExtension.get(project).mappingConfiguration.tinyMappings.toFile() }) {
    tasks.register<RemapJarTask>("remapThinJar") {
        inputFile.set(tasks.named<AbstractArchiveTask>("thinShadowJar").flatMap { it.archiveFile })
        archiveClassifier.set("thin")
    }
}
tasks.named<CheckThinJar>("checkThinJar") { jar.set(thinJar.flatMap { it.archiveFile }) }
tasks.named("assemble") { dependsOn(thinJar) }
