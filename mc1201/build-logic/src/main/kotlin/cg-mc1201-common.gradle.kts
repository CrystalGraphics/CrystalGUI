plugins {
    id("cg-java17")
    id("net.neoforged.moddev.legacyforge") // provides MC 1.20.1 as compileOnly via legacyForge
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    // ADHOC: Mojang meta and MC libraries repos that net.neoforged.moddev.repositories (settings
    // plugin) should provide, but must be re-declared at project level in Gradle 9 due to
    // DependencyResolutionManagement ordering (same workaround used in mc1201/neoforge).
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

dependencies {
    // implementation — core is an internal dependency consumed by common.
    "implementation"(project(":core"))

    // :core declares Taffy and JOML compileOnly, so they reach nobody transitively -- and UIElement
    // holds a Taffy NodeId and a JOML Matrix4f as FIELDS, which resolve at class load. Without these
    // javac reports "cannot access UIDocument" rather than a missing dependency. plan/platform-mc1201.md 4.3.
    "compileOnly"(project(":taffy"))
    // Mixin compileOnly — both loaders bundle it at runtime; never shade it.
    "compileOnly"("org.spongepowered:mixin:${property("mc1201.mixin")}")
    // NOTE: mixin annotationProcessor is intentionally omitted here — legacyForge configures
    // the Mixin AP with the correct SRG file automatically. Adding a second AP without SRG
    // causes duplicate-AP obfuscation-mapping errors for all @Inject targets.
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")
    "annotationProcessor"("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")
}

// Export compiled JAR so loader subprojects can depend on it as a binary
configurations.create("commonOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonOutput", tasks.named("jar")) }

// LegacyForge mode: puts MC 1.20.1 + MinecraftForge (compileOnly) on the classpath.
// NeoForm 1.20.1 was never published to Maven, so neoFormRuntime{}/neoForge{neoFormVersion=...}
// cannot be used. Using legacyForge is the only ModDevGradle path that resolves MC 1.20.1.
// The Forge-specific classes are compileOnly; they never appear in the common JAR or runtime.
// Do NOT use neoForge{} here — there is no NeoForge artifact for MC 1.20.1.
legacyForge {
    // MinecraftForge artifact ID format: "<mcVersion>-<forgeVersion>"
    version = "1.20.1-${property("mc1201.forge")}"
}

// -- Import guard --------------------------------------------------------------------------------
// One platform implementation serves Forge, NeoForge and Fabric, so this module may name vanilla
// (net.minecraft.*, com.mojang.*, org.lwjgl.*) but nothing from a loader. Anything loader-specific
// goes behind LoaderBridge -- plan/platform-mc1201.md 3.8.
//
// The guard is needed because `legacyForge` above puts MinecraftForge on the compileOnly classpath:
// without it a net.minecraftforge import compiles here and throws NoClassDefFoundError on the other
// two loaders.
val loaderPackages = listOf("net.minecraftforge.", "net.neoforged.", "net.fabricmc.", "cpw.mods.fml.")

tasks.named<JavaCompile>("compileJava") {
    val srcRoot = layout.projectDirectory.dir("src/main/java").asFile
    doLast {
        if (!srcRoot.isDirectory) return@doLast
        val violations = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .mapNotNull { file ->
                val hit = file.readLines()
                    .map { it.trimStart() }
                    .firstOrNull { line ->
                        line.startsWith("import ") && loaderPackages.any { line.contains(it) }
                    }
                if (hit == null) null else file.relativeTo(srcRoot) to hit
            }
            .toList()
        if (violations.isNotEmpty()) {
            error(
                "Loader-specific imports found in mc1201/common -- this module is shared by Forge, " +
                "NeoForge and Fabric, so it may name net.minecraft.* and com.mojang.* but nothing " +
                "from a loader. Put it behind LoaderBridge instead (plan/platform-mc1201.md 3.8.3):\n" +
                violations.joinToString("\n") { (path, line) -> "  $path\n      $line" }
            )
        }
    }
}
