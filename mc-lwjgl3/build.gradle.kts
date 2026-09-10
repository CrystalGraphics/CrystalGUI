import java.io.File as JFile

// mc-lwjgl3 — §12 tier 1 for LWJGL3: what CrystalGUI needs from a window and a mouse on the 1.13+
// targets, written against LWJGL 3 and `core` and nothing else.
//
// Today that is the cursor and the GL half of the UI host. `GlfwCursorService` reached Minecraft for
// exactly one value, the GLFW window handle, which arrives here as a `LongSupplier` the tier above
// hands in — §12.1's rule, and the reason this is a move rather than a rewrite.
//
// PINNED TO 3.2.2 (F1), which is what MC 1.13–1.16 ship: compiling against the OLDEST LWJGL3 in the
// supported range is what stops a symbol added in 3.3 reaching a client that has no such method.
//
// JAVA 21, which is what `core` is. Gradle matches a JVM-version attribute before any class is read,
// so a consumer cannot resolve a producer built for a newer JVM — this tier's level is decided by the
// highest thing it compiles against, never by the jar's eventual ceiling. That ceiling (major 52) is
// met by `downgradeSingleJar` rewriting the whole merged jar on the way in.

plugins {
    `java-library`
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc-lwjgl3") }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val lwjgl3Version = "3.2.2"

dependencies {
    compileOnly(project(":core"))
    compileOnly("com.crystalgraphics:platform:1.0.0")
    compileOnly("org.lwjgl:lwjgl:$lwjgl3Version")
    compileOnly("org.lwjgl:lwjgl-glfw:$lwjgl3Version")
    compileOnly("org.lwjgl:lwjgl-opengl:$lwjgl3Version")
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
            error("Minecraft or loader imports found in mc-lwjgl3/ - tier 1 is LWJGL, core and the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
