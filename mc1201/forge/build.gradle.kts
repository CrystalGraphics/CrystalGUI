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

// THE LIBRARIES A DEV RUN CANNOT SEE.
//
// integration.gradle.kts declares CrystalGraphics as compileOnly + runtimeOnly and says in its own
// comment that ModDevGradle dev runs ignore runtimeClasspath -- so none of it reached the run, and
// CgNetworkChannel's clinit died on NoClassDefFoundError: com/crystalgraphics/platform/CgService.
// build/moddev/clientLegacyClasspath.txt is where to check that: it listed neither CrystalGraphics
// nor taffy.
//
// Real dependencies on a real configuration, because resolving one BUILDS it -- unlike a bare file
// path, which serves whatever happens to sit on disk.
//
// Only MC-FREE modules belong here. crystalgraphics-mc1201-common imports Minecraft, so on this layer
// it cannot see the game at all; it belongs on the mod layer, which mods{} above cannot name because
// it lives in another build. Nothing may appear on both layers: the exploded directory and the jar
// export the same packages and BootstrapLauncher fails the launch over the split package.
dependencies {
    add("additionalRuntimeClasspath", project(":taffy"))
    add("additionalRuntimeClasspath", "com.crystalgraphics:core:1.0.0")
    add("additionalRuntimeClasspath", "com.crystalgraphics:platform:1.0.0")
    add("additionalRuntimeClasspath", "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
}

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
