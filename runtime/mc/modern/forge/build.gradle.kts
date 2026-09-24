// The `forge` branch — MinecraftForge, one node per Minecraft version (`versions/<version>/`, whose
// gradle.properties pins the toolchain and Parchment). Three toolchains, chosen by those pins
// (cgbuildlogic.useModernMinecraft):
//
//   - below 1.17 the node pins `minecraft.unimined`: Unimined, compile only. See below.
//
//   - 1.20.1 pins `forge.version` alone: ModDevGradle's legacyForge, Forge's userdev, dev runs included.
//     legacyForge stops at 1.20.1.
//   - 1.20.2+ pins `neoform.version` too: vanilla Minecraft through NeoForm with Forge's own jars
//     compileOnly, and no dev run. @see cgbuildlogic.useForgeApi
//
// Previously used dev.architectury.loom:1.14.473, replaced because:
//   - Architectury-loom's Forge mode eagerly resolves a detachedConfiguration inside the
//     jvmArguments property getter, which is a Gradle 9 hard error.
//   - No fix exists upstream (1.14.473 is the last published build, March 2026).
//   - There is no Gradle 9 property to suppress the exclusive-lock requirement.

import cgbuildlogic.commonNode
import cgbuildlogic.forgeRunsSrg
import cgbuildlogic.registerSrgReobf
import cgbuildlogic.useForgeApi
import cgbuildlogic.useModernMinecraft
import cgbuildlogic.backportedMojmap
import cgbuildlogic.usesUniminedMinecraft
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension
import net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension
import xyz.wagyourtail.unimined.api.UniminedExtension

plugins {
    id("cg-modern-loader")
    id("com.gradleup.shadow")
    // Declared here and applied only on a node below 1.17, so it loads in this branch alone.
    id("xyz.wagyourtail.unimined") version "1.4.1" apply false
}

useModernMinecraft()
val legacyForge = extensions.findByType<LegacyForgeExtension>()

// Forge below 1.17 through Unimined, which neither ModDevGradle mode reaches: Forge's userdev at
// Mojang's names to compile against. No dev run -- Forge 1.15 needs Java 8, and a dev run would load
// classes built for 17 -- so prodSmoke is this node's runtime check, as for the NeoForm nodes.
if (usesUniminedMinecraft) {
    apply(plugin = "xyz.wagyourtail.unimined")
    the<UniminedExtension>().minecraft {
        version(property("mc.version").toString())
        mappings {
            searge()
            // Mojang named nothing before 1.14.4: the backported names stand in, under the same namespace.
            val backport = backportedMojmap()
            if (backport != null) mapping(backport, "mojmap") { requires("official"); provides("mojmap" to true) }
            else mojmap()
        }
        minecraftForge { loader(property("forge.version").toString()) }
        // The shipped jar is the thin shadow jar, renamed by SrgReobfJar like the NeoForm nodes'.
        defaultRemapJar = false
    }
    // Unimined attaches Minecraft to `main` alone; the language mod's source set needs it too.
    sourceSets.findByName("lang")?.let { lang ->
        the<UniminedExtension>().minecraft(lang) { combineWith(sourceSets.main.get()) }
    }
}

// Adds CrystalGraphics compile-time deps (core, platform, mc1201-common) via composite substitution.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())

if (legacyForge == null && !usesUniminedMinecraft) {
    useForgeApi()
    configure<NeoForgeExtension> {
        parchment {
            minecraftVersion = property("parchment.mc").toString()
            mappingsVersion = property("parchment.version").toString()
        }
    }
}

legacyForge?.apply {
    parchment {
        minecraftVersion = property("parchment.mc").toString()
        mappingsVersion = property("parchment.version").toString()
    }

    // Per NODE: `project.file` resolves under versions/<version>/, so two versions never share a world.
    runs {
        create("client") {
            client()
            gameDirectory = project.file("runs/client")
        }
        create("server") {
            server()
            gameDirectory = project.file("runs/server")
        }
    }

    mods {
        create("crystalgui") {
            sourceSet(sourceSets.main.get())
            // Dev-run classpath: core and the common node are compileOnly for production
            // (shadowJar bundles them via from(zipTree(...))), but ModDevGradle dev runs only see
            // what's declared in this mods{} block. Adding their source sets here puts their
            // compiled classes in the mod's virtual JAR, making them visible to ModuleClassLoader.
            sourceSet(project(":core").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project.commonNode.extensions.getByType<SourceSetContainer>()["main"])
        }
        // A SECOND MOD ON THE DEV RUN (J8), because that is what it is in production. `-PcgNoLanguage`
        // leaves it out, which is how the degraded configuration is exercised without building a jar.
        if (!providers.gradleProperty("cgNoLanguage").isPresent) {
            create("crystalgui_language") {
                sourceSet(sourceSets["lang"])
                sourceSet(project.commonNode.extensions.getByType<SourceSetContainer>()["lang"])
            }
        }
    }
}

// Puts CrystalGraphics on this run: its MC-free jars as libraries, its loader as a mod. BELOW the
// loader block on purpose -- ModDevGradle creates additionalRuntimeClasspath while that extension is
// configured, not when its plugin is applied, so an apply above it fails with "Configuration with
// name 'additionalRuntimeClasspath' not found".
if (legacyForge != null) apply(from = rootProject.file("gradle/module_integration/crystalgraphics-run.gradle.kts").toURI())

// Extracts this node's Minecraft + Forge sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts this node's Minecraft + Forge sources and resources into build/mc-src for local navigation."
    group = "crystalgui"

    // dependsOn (not mustRunAfter) — mustRunAfter does not cause this task to run on a clean checkout.
    dependsOn("createMinecraftArtifacts")

    // Lazy providers resolved at execution time — never at configuration time (Gradle 9 rule).
    val sourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        dir.asFileTree.matching { include("*-sources.jar") }.singleFile
    }
    val resourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        // `client-extra-<v>.jar` on 1.20.x, `<loader>-<v>-client-extra-aka-minecraft-resources.jar` on 1.21.
        dir.asFileTree.matching { include("*client-extra*.jar") }.singleFile
    }

    from(zipTree(sourcesJar)) { into("java") }
    from(zipTree(resourcesJar)) { into("resources") }
    into(layout.buildDirectory.dir("mc-src"))
}

// extractMcSources is cheap (unzips an already-present jar — createMinecraftArtifacts ran first).
// Wire it into classes so build/mc-src/ is always populated after a normal compile.
if (!usesUniminedMinecraft) tasks.named("classes") { dependsOn(extractMcSources) }

// The SHIPPED jars are reobfuscated where Forge runs SRG, and it is the SHADOW jar that ships.
//
// Forge 1.17–1.20.4 runs SRG member names; a mod is compiled against official ones. ModDevGradle
// reobfuscates `jar` by default, which here is the six-class loader stub -- so `assemble` produced a
// 10 KB jar that was correctly mapped and had no engine in it, beside a 54 MB one that had everything
// and called `Minecraft.getInstance()` under a name production does not have. Both are unusable, and a
// dev run cannot show it: dev is deobfuscated, so official names are the right ones there. From 1.20.6
// Forge runs official names too, and the jars ship as compiled. @see cgbuildlogic.forgeRunsSrg
val thinJar: TaskProvider<out AbstractArchiveTask> = if (!forgeRunsSrg(project.name)) {
    tasks.named<AbstractArchiveTask>("thinShadowJar")
} else if (legacyForge == null) {
    // main's classpath, as legacyForge's reobf uses: the renamer only needs what Minecraft members are
    // overridden, and the language libraries (ECJ, Rhino) trip its inheritance scan.
    registerSrgReobf("langThinShadowJar", "lang-thin", sourceSets.main.get().compileClasspath)
    registerSrgReobf("thinShadowJar", "thin", sourceSets.main.get().compileClasspath)
} else {
    val obfuscation = the<ObfuscationExtension>()
    // Downgrade, then SHADE, then remap. jvmdg rewrites bytecode and adds a dependency on its own
    // stubs; the remapper only rewrites names, so it runs last, on the class files that ship. Not on
    // `assemble` (J7): `./gradlew reobfShadeDowngradedShadowJar` still produces the fat jar.
    obfuscation.reobfuscate(tasks.named<AbstractArchiveTask>("shadeDowngradedShadowJar"), sourceSets.main.get()) {
        archiveClassifier.set("srg")
    }
    // `main` and not `lang` as the second argument: ModDevGradle looks for `<sourceSet>RuntimeElements`,
    // which only `main` has, and what it supplies is the REMAPPER's classpath rather than the jar's
    // contents. Passing `lang` fails with "langRuntimeElements not found".
    obfuscation.reobfuscate(tasks.named<AbstractArchiveTask>("langThinShadowJar"), sourceSets.main.get()) {
        archiveClassifier.set("lang-thin")
    }
    // The merge's input from this node: its own classes plus its relocated common node, at SRG names.
    obfuscation.reobfuscate(tasks.named<AbstractArchiveTask>("thinShadowJar"), sourceSets.main.get()) {
        archiveClassifier.set("thin")
    }
}

// Registered by cg-modern-loader with what a CrystalGUI thin jar may contain; only the jar is ours.
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(thinJar.flatMap { it.archiveFile })
}
tasks.named("assemble") { dependsOn(thinJar) }


// The per-loader `deployMods` is retired (J7). One artifact installs on every loader now, so the root
// `deploySingleJars` puts that pair into all four instances; a per-loader deploy could only ever
// install the fat jar this module no longer ships.
