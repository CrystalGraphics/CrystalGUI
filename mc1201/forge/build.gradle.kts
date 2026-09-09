// mc1201/forge — MinecraftForge 1.20.1 loader subproject.
// Uses ModDevGradle legacyForge plugin (net.neoforged.moddev.legacyforge), which explicitly
// supports MinecraftForge 1.17–1.20.1 and is Gradle 9 + JDK 25 compatible.
//
// Previously used dev.architectury.loom:1.14.473, replaced because:
//   - Architectury-loom's Forge mode eagerly resolves a detachedConfiguration inside the
//     jvmArguments property getter, which is a Gradle 9 hard error.
//   - No fix exists upstream (1.14.473 is the last published build, March 2026).
//   - There is no Gradle 9 property to suppress the exclusive-lock requirement.
//
// The legacyForge plugin version is inherited from settings.gradle.kts where
// net.neoforged.moddev.repositories:2.0.141 is applied — that settings plugin pins
// all three net.neoforged.moddev.* plugins to the same version automatically.

plugins {
    id("cg-mc1201-loader")
    id("net.neoforged.moddev.legacyforge")
    id("com.gradleup.shadow")
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc1201-forge") }

// Adds CrystalGraphics compile-time deps (core, platform, mc1201-common) via composite substitution.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())

legacyForge {
    // MinecraftForge artifact ID format: "<mcVersion>-<forgeVersion>"
    version = "1.20.1-${property("mc1201.forge")}"

    parchment {
        minecraftVersion = property("mc1201.parchment.mc").toString()
        mappingsVersion = property("mc1201.parchment").toString()
    }

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
            // Dev-run classpath: core and mc1201:common are compileOnly for production
            // (shadowJar bundles them via from(zipTree(...))), but ModDevGradle dev runs only see
            // what's declared in this mods{} block. Adding their source sets here puts their
            // compiled classes in the mod's virtual JAR, making them visible to ModuleClassLoader.
            sourceSet(project(":core").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":mc1201:common").extensions.getByType<SourceSetContainer>()["main"])
        }
    }
}

// Puts CrystalGraphics on this run: its MC-free jars as libraries, its loader as a mod. BELOW the
// loader block on purpose -- ModDevGradle creates additionalRuntimeClasspath while that extension is
// configured, not when its plugin is applied, so an apply above it fails with "Configuration with
// name 'additionalRuntimeClasspath' not found".
apply(from = rootProject.file("gradle/module_integration/crystalgraphics-run.gradle.kts").toURI())

// Extracts MinecraftForge 1.20.1 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts MinecraftForge 1.20.1 sources and resources into build/mc-src for local navigation."
    group = "crystalgui"

    // dependsOn (not mustRunAfter) — mustRunAfter does not cause this task to run on a clean checkout.
    dependsOn("createMinecraftArtifacts")

    // Lazy providers resolved at execution time — never at configuration time (Gradle 9 rule).
    val sourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        dir.asFileTree.matching { include("*-sources.jar") }.singleFile
    }
    val resourcesJar = layout.buildDirectory.dir("moddev/artifacts").map { dir ->
        dir.asFileTree.matching { include("client-extra-*.jar") }.singleFile
    }

    from(zipTree(sourcesJar)) { into("java") }
    from(zipTree(resourcesJar)) { into("resources") }
    into(layout.buildDirectory.dir("mc-src"))
}

// extractMcSources is cheap (unzips an already-present jar — createMinecraftArtifacts ran first).
// Wire it into classes so build/mc-src/ is always populated after a normal compile.
tasks.named("classes") { dependsOn(extractMcSources) }

// The SHIPPED jar has to be reobfuscated, and it is the SHADOW jar that ships.
//
// Forge 1.20.1 runs SRG member names; a mod is compiled against official ones. ModDevGradle
// reobfuscates `jar` by default, which here is the six-class loader stub -- so `assemble` produced a
// 10 KB jar that was correctly mapped and had no engine in it, beside a 54 MB one that had everything
// and called `Minecraft.getInstance()` under a name production does not have. Both are unusable, and a
// dev run cannot show it: dev is deobfuscated, so official names are the right ones there.
//
// Downgrade, then SHADE, then remap. jvmdg rewrites bytecode and adds a dependency on its own stubs;
// the remapper only rewrites names, so it has to run last, on the class files that will actually ship.
val reobfShadowJar = the<net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension>()
    .reobfuscate(
        tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("shadeDowngradedShadowJar"),
        sourceSets.main.get()) {
        archiveClassifier.set("srg")
    }

tasks.named("assemble") { dependsOn(reobfShadowJar) }

// -- The thin jar, reobfuscated (J1) --------------------------------------------------------------
//
// The merge's input from this loader: its own classes plus the relocated :mc1201:common, at SRG
// names. Reobfuscated for the same reason the shadow jar is -- production runs SRG members and a jar
// built against official ones calls methods this Minecraft does not have.
val reobfThinJar = the<net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension>()
    .reobfuscate(
        tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("thinShadowJar"),
        sourceSets.main.get()) {
        archiveClassifier.set("thin")
    }

tasks.register<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(reobfThinJar.flatMap { it.archiveFile })
    allowedPrefixes.set(listOf("com/crystalgui/mc/"))
}

tasks.named("check") { dependsOn("checkThinJar") }
tasks.named("assemble") { dependsOn(reobfThinJar) }


// -- Dropping a build into a real client ---------------------------------------------------------
//
// CrystalGraphics goes too: CrystalGUI does not run without it, and shipping one of a matched pair is
// how an afternoon disappears. Its reobfuscated jar is `reobfShadowJar` -- no downgrade step there,
// being Java 17 throughout, where this project shadows core/ and language/ and must downgrade first.
val crystalGraphicsBuild = gradle.includedBuild("CrystalGraphics")

// ONLY the -srg pair. `assemble` also leaves a `-java17` jar carrying every class under official
// names, and a tiny plain one correctly mapped and nearly empty. Both install; neither runs.
extra["cgDeployKey"] = "prismLauncher1201ForgeDir"
extra["cgDeployJars"] = listOf(
        layout.buildDirectory.file("libs/crystalgui-mc1201-forge-$version-srg.jar"),
        File(crystalGraphicsBuild.projectDir,
                "mc1201/forge/build/libs/crystalgraphics-mc1201-forge-1.0.0-srg.jar"))
extra["cgDeployDependsOn"] = listOf(
        reobfShadowJar, crystalGraphicsBuild.task(":mc1201:forge:reobfShadowJar"))
apply(from = rootProject.file("gradle/module_integration/deploy-mods.gradle.kts").toURI())
