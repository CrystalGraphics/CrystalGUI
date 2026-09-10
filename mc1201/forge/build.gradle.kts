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
        // A SECOND MOD ON THE DEV RUN (J8), because that is what it is in production. `-PcgNoLanguage`
        // leaves it out, which is how the degraded configuration is exercised without building a jar.
        if (!providers.gradleProperty("cgNoLanguage").isPresent) {
            create("crystalgui_lang") {
                sourceSet(sourceSets["lang"])
                sourceSet(project(":mc1201:common").extensions.getByType<SourceSetContainer>()["lang"])
            }
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

// Not on `assemble` (J7): the single jar is the shipping artifact, and reobfuscating a fat jar nothing
// installs was pure cost. `./gradlew reobfShadowJar` still produces one.

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

// Registered by cg-mc1201-loader with what a CrystalGUI thin jar may contain; only the jar is ours.
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(reobfThinJar.flatMap { it.archiveFile })
}
tasks.named("assemble") { dependsOn(reobfThinJar) }

// -- The language thin jar, reobfuscated (J8) -----------------------------------------------------
//
// `main` and not `lang` as the second argument: ModDevGradle looks for `<sourceSet>RuntimeElements`,
// which only `main` has, and what that argument supplies is the REMAPPER's classpath rather than the
// jar's contents. Passing `lang` fails with "langRuntimeElements not found".
val reobfLangThinJar = the<net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension>()
    .reobfuscate(
        tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("langThinShadowJar"),
        sourceSets.main.get()) {
        archiveClassifier.set("lang-thin")
    }


// The per-loader `deployMods` is retired (J7). One artifact installs on every loader now, so the root
// `deploySingleJars` puts that pair into all four instances; a per-loader deploy could only ever
// install the fat jar this module no longer ships.
