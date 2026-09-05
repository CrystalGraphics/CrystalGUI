// NOTE: Despite living under mc1201/, this subproject targets MC 1.20.4 / NeoForge 20.4.x.
// NeoForge 20.1.x (MC 1.20.1) was never published to the NeoForge Maven; the earliest
// available stable series is 20.4.x (MC 1.20.4). Version pins live in gradle.properties
// under mc1204.* keys. The directory name mc1201/neoforge/ is retained for continuity.

plugins {
    id("cg-mc1201-loader")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc1201-neoforge") }

// Adds CrystalGraphics compile-time deps (core, platform, mc1201-common) via composite substitution.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())

// ADHOC: Re-declare two Maven repos that net.neoforged.moddev.repositories (settings plugin)
// should provide at project level. The cg-mc1201-loader convention plugin's repositories block
// runs at project configuration time and takes precedence over settings-level repos in Gradle 9,
// causing moddev's NeoForge/Mojang repos to go missing during dependency resolution.
//
// Removal condition: if ModDevGradle changes its settings plugin to use
// DependencyResolutionManagement (exclusive, settings-owned) instead of per-project repos,
// these declarations can be removed and the dependency resolution failure will confirm it.
repositories {
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

neoForge {
    version = property("mc1204.neoforge").toString()

    parchment {
        minecraftVersion = property("mc1204.parchment.mc").toString()
        mappingsVersion = property("mc1204.parchment").toString()
    }

    runs {
        create("client") {
            client()
            gameDirectory = project.file("runs/client")
        }
        // A server run, so serverSmoke has something to boot here too. Without it this loader is the
        // one that cannot be checked for the server-side class-loading contract.
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

// Extracts NeoForge + MC 1.20.4 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when the source jar changes between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts NeoForge + MC 1.20.4 sources and resources into build/mc-src for local navigation."
    group = "crystalgui"

    // dependsOn (not mustRunAfter) — mustRunAfter only orders tasks already scheduled; it does not
    // cause createMinecraftArtifacts to run, so the jar would be absent on a clean checkout.
    dependsOn("createMinecraftArtifacts")

    // Lazy providers resolved at execution time — never at configuration time (Gradle 9 rule).
    // fileTree scan is the fallback because ModDevGradle does not expose a public typed output
    // property for the sources or client-extra jars.
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
