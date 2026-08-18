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
    // Text shaping. This source set is the one that's *supposed* to have fonts (headlessTest is the
    // one that deliberately doesn't), but the bindings were never wired in — so any test that laid
    // out a non-empty UIText died with NoClassDefFoundError: FreeTypeException, several frames deep
    // in FontFamilyCache. Shaping is pure CPU work (no GL context), so it genuinely works here; only
    // atlas upload and drawing remain harness-only.
    testImplementation("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
    // CG declares commons-io compileOnly (Minecraft ships it at runtime), so it isn't inherited
    // transitively. Tests that load a resource go through CgIO -> IOUtils, so they need it directly.
    testImplementation("commons-io:commons-io:2.4")
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


// -- Headless (server-side) test source set -----------------------------------
// CrystalGraphics *core* is DELIBERATELY absent here, at compile AND runtime. That absence IS the
// assertion: on a dedicated Minecraft server there is no GL context and no fonts, so anything in
// core/ that reaches a CG core type outside a paint method body fails here with
// NoClassDefFoundError rather than in production.
//
// CrystalGraphics `platform` is the one CG module that IS present, and deliberately so. It is pure
// SPI — interfaces, key-code constants, and the CgPlatform registry — with no GL calls and no
// context requirement, and it ships inside every loader jar, so a dedicated server genuinely has it.
// Excluding it would assert something that is not true of production. core/ reaches it for real:
// UIInputHandler implements CgSystemInput, and CgPlatform.input()/sound()/cursor() replaced what
// CrystalGuiCore's static registry used to hold.
//
// JOML and Taffy must stay. UIElement and ElementStyle have *fields* of those types (Matrix4f,
// NodeId, TaffyStyle), and field descriptors resolve at class load — unlike method-body references,
// which don't. A server deployment therefore needs both on its classpath even though it never lays
// anything out. Non-obvious, and someone will eventually try to strip them.
val headlessTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

dependencies {
    "headlessTestImplementation"("junit:junit:4.13.2")
    "headlessTestImplementation"("com.crystalgraphics:platform:1.0.0")
    "headlessTestImplementation"("org.apache.logging.log4j:log4j-core:2.26.1")
    "headlessTestImplementation"("com.google.code.gson:gson:2.11.0")
    "headlessTestImplementation"("dev.vfyjxf:taffy:${rootProject.properties["taffy_version"]}")
    "headlessTestImplementation"("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    "headlessTestCompileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    "headlessTestCompileOnly"("org.projectlombok:lombok:1.18.44")
    "headlessTestAnnotationProcessor"("org.projectlombok:lombok:1.18.44")
}

val headlessTestTask = tasks.register<Test>("headlessTest") {
    description = "Server-side tests. CrystalGraphics is NOT on the classpath — that is the assertion."
    group = "verification"
    testClassesDirs = headlessTest.output.classesDirs
    classpath = headlessTest.runtimeClasspath
    useJUnit()
}

tasks.named("check") { dependsOn(headlessTestTask) }


// -- Import guard -------------------------------------------------------------
// Fails the build if any core/ source imports MC, Forge, or LWJGL classes.
// Runs as a doLast hook on compileJava — same pattern as CrystalGraphics/core/.
//
// Known V3.x legacy exemptions (scheduled for deletion in Phase 2):
//   ScissorStack.java — uses raw GL11 from LWJGL; the entire core/render/ package
//   will be replaced by a CrystalGraphics-backed implementation in Phase 2.
//val platformImportExemptions = setOf("ScissorStack.java")

// Source files are UTF-8, and javac must be told so rather than left to the platform default.
//
// Not cosmetic. Sources here carry non-ASCII character literals that ARE the behaviour -- the whitespace
// markers U+00B7 and U+2192, and the CJK ranges the line breaker classifies. Under a platform default of
// windows-1252 those decode to the wrong characters, which draws the wrong glyph or classifies the wrong
// codepoint: a failure no test asserting on offsets or counts would notice, on developer machines only.
// JDK 18+ happens to default to UTF-8 already, which makes the bug invisible here and waiting for
// whoever builds on an older toolchain.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // NAMES FOR THE EDITOR, and the only mechanism that reaches an INTERFACE method (M13 §25.1).
    //
    // Parameter names already survive compilation for anything with a body -- Gradle passes `-g`, so the
    // `LocalVariableTable` carries them and the hover reads them off the class file with nothing shipped.
    // An abstract or interface method has no `Code` attribute and therefore no such table, which for an
    // SPI-heavy module is most of the interesting surface: `CgUiDrawable.draw`, `SourceAnalyzer.analyze`,
    // every service seam. `MethodParameters` is the one attribute that does not need a body.
    //
    // One flag, roughly 1% class-file growth, and it is read by `ClassFileParameterNames` in preference
    // to the local-variable table because it lists parameters and only parameters, in order.
    options.compilerArgs.add("-parameters")
}

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
