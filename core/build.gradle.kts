// CrystalGUI core — platform-agnostic UI engine.
// NO Minecraft, Forge, or LWJGL imports permitted in this subproject (import guard below).

import java.io.File as JFile

plugins {
    `java-library`
}

group = "com.crystalgui"
version = rootProject.version.toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    // Disable auto target JVM compatibility — core/ uses a JDK 21 toolchain but
    // deliberately targets Java 8 bytecode via Jabel + --release 8.
    // Without this, Gradle restricts dependency resolution to JVM 8 compatible
    // libraries, which breaks modern deps like Taffy (JVM 17+).
    disableAutoTargetJvm()
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
    compileOnly("com.crystalgraphics:crystalgraphics:1.0.0")

    // Taffy layout engine + JOML (consumed from CG at runtime; needed here for compile)
    compileOnly("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    testImplementation("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    compileOnly("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    testImplementation("org.joml:joml:${rootProject.properties["jomlVersion"]}")

    // @Nullable / @NonNull annotations — javax.annotation not on module path in JDK 11+
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

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
    annotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:1.0.0")
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:1.0.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Use -source/-target instead of --release 8.
    //
    // --release 8 would restrict the compiler's boot classpath to Java 8 APIs, which
    // causes "cannot access Record" when the compiler reads Taffy's class files (Taffy
    // uses Java Records internally). -source/-target produce the same Java 8 bytecode
    // without the boot classpath restriction, so modern dependencies resolve correctly.
    //
    // IntelliJ reads the toolchain JDK (21) as the module language level regardless —
    // no options.release.set() call means IntelliJ keeps the toolchain version.
    //
    // Jabel still activates: it checks for -target 8 and applies its AST patching.
    options.compilerArgs.addAll(listOf("-source", "8", "-target", "8"))
    // Jabel uses ByteBuddy which only officially supports up to JDK 20.
    options.isFork = true
    options.forkOptions.jvmArgs!!.add("-Dnet.bytebuddy.experimental=true")
}

// -- Import guard -------------------------------------------------------------
// Fails the build if any core/ source imports MC, Forge, or LWJGL classes.
// Runs as a doLast hook on compileJava — same pattern as CrystalGraphics/core/.
//
// Known V3.x legacy exemptions (scheduled for deletion in Phase 2):
//   ScissorStack.java — uses raw GL11 from LWJGL; the entire core/render/ package
//   will be replaced by a CrystalGraphics-backed implementation in Phase 2.
val platformImportExemptions = setOf("ScissorStack.java")

tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = JFile(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" && it.name !in platformImportExemptions }
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
