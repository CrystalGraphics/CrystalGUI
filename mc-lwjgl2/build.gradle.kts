import java.io.File as JFile

// mc-lwjgl2 — §12 tier 1 for LWJGL2: what CrystalGUI needs from a window and a mouse on the LWJGL2
// targets, written against LWJGL 2.9.4 and `core` and nothing else.
//
// Today that is the cursor. `Lwjgl2CursorService` already imported no Minecraft class at all — the
// keyword→picture table is `core`'s `CursorBitmaps.artFor` and this side only turns a `CursorArt`
// into a native — so it is tier 1 as it stands, and moving it here is what stops the next platform
// copying it again. Shared by every LWJGL2 target (1.7.10 and 1.12.2).
//
// JAVA 21, which is what `core` is, and NOT Java 8 as `mc-shared` is. Gradle matches a JVM-version
// attribute at resolution, so a consumer cannot see a producer built for a newer JVM at all and the
// build fails on the classpath rather than on a class — which means this tier's level is decided by
// the highest thing it compiles against, never by the jar's eventual ceiling. `mc-shared` gets away
// with 8 because it depends on nothing of ours. The ceiling itself — major 52, because FML 1.7.10
// reads every entry with asm-debug-all-5.0.3 — is met by `downgradeSingleJar` rewriting the whole
// merged jar on the way in.

plugins {
    `java-library`
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc-lwjgl2") }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    // LWJGL 2.9.4-nightly is Mojang's own build of it and lives only in their library repo --
    // Central has no such version. Same source the harness and the GTNH toolchain resolve it from.
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    mavenCentral()
}

dependencies {
    // compileOnly throughout: the merge adds `core` and CrystalGraphics' `platform` exactly once, and
    // the game supplies LWJGL. Anything bundled here would be a second copy of something already on
    // the classpath the loader booted with.
    compileOnly(project(":core"))
    compileOnly("com.crystalgraphics:platform:1.0.0")
    compileOnly("org.lwjgl.lwjgl:lwjgl:${property("dep.lwjgl")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// The tier boundary, enforced rather than described (F4).
tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = JFile(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { f ->
                f.readLines().any { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") && (
                        trimmed.contains("net.minecraft") ||
                        trimmed.contains("com.mojang") ||
                        trimmed.contains("cpw.mods.fml") ||
                        trimmed.contains("net.minecraftforge") ||
                        trimmed.contains("net.neoforged") ||
                        trimmed.contains("net.fabricmc")
                    )
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            error("Minecraft or loader imports found in mc-lwjgl2/ - tier 1 is LWJGL, core and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
