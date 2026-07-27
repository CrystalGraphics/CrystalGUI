// CrystalGUI core — platform-agnostic UI engine.
// NO Minecraft, Forge, or LWJGL imports permitted in this subproject (import guard below).

import java.io.File as JFile

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        // Jabel is stable on 17 and 21. It is not stable on 25.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        // LWJGL 2.9.4-nightly is distributed via Minecraft's CDN, not Maven Central.
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}

dependencies {
    // CrystalGraphics API — resolved via composite build substitution to CG's mc1710 subproject
//    compileOnly("com.crystalgraphics:crystalgraphics:1.0.0")
    compileOnly("com.crystalgraphics:core:1.0.0")
    compileOnly("com.crystalgraphics:platform:1.0.0")
    // testImplementation, not testCompileOnly: tests need CG on the RUNTIME classpath too, or
    // anything that touches a CG type (e.g. CgUiSprite.setTexture -> CgTextureManager) dies with
    // NoClassDefFoundError instead of running. Note this only makes the classes loadable — calls
    // that actually allocate GL objects still need a live context and remain harness-only.
    testImplementation("com.crystalgraphics:core:1.0.0")
    testImplementation("com.crystalgraphics:platform:1.0.0")
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")

    // Taffy layout engine + JOML (consumed from CG at runtime; needed here for compile)
    compileOnly("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    testImplementation("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    compileOnly("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    testImplementation("org.joml:joml:${rootProject.properties["jomlVersion"]}")

    // @Nullable / @NonNull annotations — javax.annotation not on module path in JDK 11+
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Real Gson (not shaded) — core must run standalone (tests, gl-debug-harness), so this can't be
    // compileOnly on the assumption a loader/Minecraft provides it at runtime.
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")

    // LWJGL 2 — needed for ScissorStack.java (V3.x legacy, scheduled for deletion in Phase 2).
    // ScissorStack uses raw GL11 calls; this compileOnly dep lets it compile without an import
    // guard violation (ScissorStack is in the exemption list).
    compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")

    // Lombok (compile-time only — no runtime dependency)
    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    // Jabel: backports modern Java syntax (records, sealed classes, etc.) to Java 8 bytecode.
    // Requires --release 8 in compilerArgs (see tasks.withType<JavaCompile> below).
//    annotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:1.0.1")
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:1.0.1")

    testImplementation("junit:junit:4.13.2")
}


// -- Import guard -------------------------------------------------------------
// Fails the build if any core/ source imports MC, Forge, or LWJGL classes.
// Runs as a doLast hook on compileJava — same pattern as CrystalGraphics/core/.
//
// Known V3.x legacy exemptions (scheduled for deletion in Phase 2):
//   ScissorStack.java — uses raw GL11 from LWJGL; the entire core/render/ package
//   will be replaced by a CrystalGraphics-backed implementation in Phase 2.
//val platformImportExemptions = setOf("ScissorStack.java")

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
                        trimmed.contains("cpw.mods.fml") ||
                        trimmed.contains("net.minecraftforge") ||
                        trimmed.contains("org.lwjgl")
                    )
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            error("MC/Forge/LWJGL imports found in core/ — must not leak into the platform-agnostic layer:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
