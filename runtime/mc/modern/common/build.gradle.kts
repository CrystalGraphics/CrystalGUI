// The `common` branch: everything the 1.20.x loaders share, and nothing any one of them owns. Built
// once per Minecraft version the tree targets -- `:runtime:mc:modern:common:<version>` -- and every
// loader node compiles against the common node of its own version.
//
// The toolchain and every pin come from the node (`versions/<version>/gradle.properties`, read by
// cg-modern-common), so this script carries nothing version-specific.

import cgbuildlogic.backportedMojmap
import cgbuildlogic.usesLoomMinecraft
import cgbuildlogic.usesUniminedMinecraft
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("cg-modern-common")
    // Below 1.17 no ModDevGradle mode reaches Minecraft, and Loom supplies it vanilla with Mojang's
    // names. Declared here, and only applied on such a node, so it loads in this branch alone.
    id("fabric-loom") version "1.16.2" apply false
}

if (usesLoomMinecraft) {
    // A loader bundles common at Mojang's names and remaps the two together, as on every later node:
    // keep the unremapped jar in the outgoing variants and drop Loom's intermediary one.
    extra["fabric.loom.disableRemappedVariants"] = "true"
    apply(plugin = "fabric-loom")
    afterEvaluate {
        val remapped = tasks.named<AbstractArchiveTask>("remapJar").get().archiveFile.get().asFile
        for (variant in listOf("apiElements", "runtimeElements")) {
            configurations.getByName(variant).artifacts.removeIf { it.file == remapped }
        }
    }
    val loom = the<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:${property("mc.version")}")
        "mappings"(loom.officialMojangMappings())
        // @Nullable: ModDevGradle's Minecraft brings jsr305 with its libraries, and Loom's does not.
        "compileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    }
}

if (usesUniminedMinecraft) {
    extra["cg.backportedMojmap"] = backportedMojmap() ?: error("${project.path} pins minecraft.unimined with no backported names")
    apply(from = buildscript.sourceFile!!.resolveSibling("unimined-vanilla.gradle.kts"))
}

base { archivesName.set("crystalgui-common-${project.name}") }

// Adds CrystalGraphics' compile-time deps (core, platform, its modern common, freetype) via composite
// substitution -- the same as the three loader branches. Common names CrystalGraphics' platform types
// to compile its platform service adapter code.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())
