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
    // LOG4J: COMPILED AGAINST THE OLDEST VERSION core/ MUST RUN ON, WHICH IS MINECRAFT 1.7.10's.
    //
    // 1.7.10 ships log4j 2.0-beta9. The parameterised overloads `warn(String, Object)`,
    // `warn(String, Object, Object)` and friends were only added in 2.6 -- beta9 has just
    // `warn(String, Object...)`. Overload selection happens at COMPILE time, so building against a
    // modern log4j-api makes javac emit `warn(String, Object)` for every `LOGGER.warn("x {}", a)` in
    // this module, and each one is a NoSuchMethodError the first time it is reached in game:
    //
    //     java.lang.NoSuchMethodError:
    //       org.apache.logging.log4j.Logger.warn(Ljava/lang/String;Ljava/lang/Object;)V
    //       at com.crystalgui.style.sheet.DeclarationParser.parseBlock
    //
    // It is a landmine rather than a build break: it only fires on the branch that logs, so the first
    // one found was in a CSS warning path -- which meant opening the editor died inside the user-agent
    // stylesheet parse and looked like a resource problem. 27 files in this module use `{}` args.
    //
    // Compiling against beta9 binds them all to the varargs overload, which every later log4j still
    // has, so the harness and the tests (running 2.26.1) are unaffected. API ONLY: core names just
    // LogManager and Logger. The implementation stays modern and runtime-scoped, which also keeps it
    // off mc1710's classpath, where Minecraft supplies its own.
    compileOnly("org.apache.logging.log4j:log4j-api:2.0-beta9")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.26.1")
    testImplementation("org.apache.logging.log4j:log4j-core:2.26.1")

    // JSpecify, declared because it is USED (UIInputHandler and friends import @Nullable from it).
    // It used to arrive transitively from log4j-core 2.26.1 -- modern log4j-api depends on it -- so
    // moving log4j off the compile classpath above took an annotation package with it. Annotation-only
    // and CLASS-retention, so compileOnly is the whole requirement.
    compileOnly("org.jspecify:jspecify:1.0.0")
    testCompileOnly("org.jspecify:jspecify:1.0.0")

    // Taffy layout engine + JOML (consumed from CG at runtime; needed here for compile)
    compileOnly(project(":taffy"))
    testImplementation(project(":taffy"))
    compileOnly("org.joml:joml:${rootProject.properties["jomlVersion"]}")
    testImplementation("org.joml:joml:${rootProject.properties["jomlVersion"]}")

    // @Nullable / @NonNull annotations — javax.annotation not on module path in JDK 11+
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // GSON: COMPILED AGAINST THE OLDEST VERSION core/ MUST RUN ON, WHICH IS MINECRAFT 1.7.10's.
    //
    // The same trap as log4j above, and found the same way -- at runtime, on a branch that had not been
    // taken yet. 1.7.10 ships gson 2.2.4; `JsonParser.parseString(String)` is a STATIC method added in
    // 2.8.6, so building against a modern gson emits a call that does not exist in game:
    //
    //     java.lang.NoSuchMethodError: com.google.gson.JsonParser.parseString(...)
    //       at com.crystalgui.core.settings.SettingsCodec.fromJson
    //
    // It only fired once a preferences file existed for loadPreferences to read, which is why several
    // clean launches preceded it. Compiling against 2.2.4 makes javac pick `new JsonParser().parse(...)`
    // -- deprecated in modern gson, present in every version -- so tests and the harness are unaffected.
    //
    // THE GENERAL RULE, now paid for twice: a library Minecraft also supplies must be COMPILED against
    // the oldest version any target ships, never merely tested against the newest. The failure is always
    // a NoSuchMethodError on a cold path, never a build error.
    compileOnly("com.google.code.gson:gson:2.2.4")
    runtimeOnly("com.google.code.gson:gson:2.11.0")
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
// A second resource root, so a non-Java file may sit beside the code it belongs to.
//
// Gradle copies src/main/resources and nothing else, so a .css next to a .java compiles, ships, and
// is absent from the jar -- which fails at runtime as a missing file rather than at build time as a
// missing rule. com/crystalgui/example/machine/ui/machine.css is the one file relying on this today:
// the example panel is meant to be read as a single directory (model, tree, theme, both session
// halves), and splitting its theme into the assets tree would mean opening two source roots to read
// one panel.
//
// This is NOT the way to ship an engine asset. Anything a resource pack is expected to override
// belongs under assets/crystalgui/ where CgIO and the resource manager can find it; this root is
// read with plain getResourceAsStream and a pack cannot reach it.
//
// The exclude is what keeps processResources from copying every .java file in the module into the
// jar alongside the classes.
sourceSets.main {
    resources {
        srcDir("src/main/java")
        exclude("**/*.java")
    }
}

val headlessTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

dependencies {
    "headlessTestImplementation"("junit:junit:4.13.2")
    // For EngineBoundaryTest's bytecode scan of the strangler line (plan_m5.md §2), nothing else.
    // Test-only: the mod jar's ASM is language/'s, relocated, and this must not become a second copy.
    "headlessTestImplementation"("org.ow2.asm:asm:${rootProject.properties["asmVersion"]}")
    "headlessTestImplementation"("com.crystalgraphics:platform:1.0.0")
    "headlessTestImplementation"("org.apache.logging.log4j:log4j-core:2.26.1")
    "headlessTestImplementation"("com.google.code.gson:gson:2.11.0")
    "headlessTestImplementation"(project(":taffy"))
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


// -- M5 acceptance ------------------------------------------------------------
// The new engine's own definition of done, as ONE invocation: `./gradlew :core:m5Acceptance`.
//
// Named rather than left as a list somebody retypes, because the point of the list is that it is
// the SAME list every time -- the seam suite over both trees, the strangler boundary read out of
// the constant pool, one-pass layout compared against the old engine, hit-testing with no paint
// having happened, the focus and hit-test rows, and the engine-parity comparison (which SKIPS with
// instructions when no GL run has produced its PNGs -- an environment gate, never an answer gate).
val m5Headless = tasks.register<Test>("m5AcceptanceHeadless") {
    description = "M5's headless half: the seam, the boundary, the box tree, the services."
    group = "verification"
    testClassesDirs = headlessTest.output.classesDirs
    classpath = headlessTest.runtimeClasspath
    useJUnit()
    filter {
        includeTestsMatching("com.crystalgui.ui.dom.*")
        includeTestsMatching("com.crystalgui.ui.box.*")
        includeTestsMatching("com.crystalgui.ui.service.*")
        includeTestsMatching("com.crystalgui.headless.EngineBoundaryTest")
        includeTestsMatching("com.crystalgui.net.mirror.*")
    }
}

val m5Fonted = tasks.register<Test>("m5AcceptanceFonted") {
    description = "M5's half that needs fonts and CSS: one-pass layout on both engines, shaped text."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter {
        includeTestsMatching("com.crystalgui.ui.box.*")
        includeTestsMatching("com.crystalgui.ui.dom.*")
        includeTestsMatching("com.crystalgui.ui.service.*")
    }
}

tasks.register("m5Acceptance") {
    description = "M5 is done by its own definition: run this."
    group = "verification"
    dependsOn(m5Headless, m5Fonted)
}


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
tasks.withType<Test>().configureEach {
    // `-Pbench` runs the timing measurements, which `check` must not.
    //
    // Not squeamishness about slow tests -- these numbers MOVE WITH TEST ORDER. The same assertion
    // measured 727us of avoidable work with the fix disabled and 978us with it enabled, in a run where
    // ninety other tests had warmed the JVM first. A threshold that can be beaten by JIT state is not a
    // regression guard, it is a coin toss that fails on somebody else's machine. What ships instead is
    // the DETERMINISTIC half: the decoration must not go stale. See EditorFrameCostTest.
    systemProperty("cgui.test.bench", project.hasProperty("bench").toString())
}

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

// ── M13 §25.4 — our own sources, shipped ─────────────────────────────────────────────────────────
//
// A hover over `UIElement.addChild` in a shipped client quotes the declaration its author wrote, with
// its javadoc, instead of the assembled form. `SourceArchives.ResourceArchive` reads them straight off
// the loader; the read path is one `getResourceAsStream` because the JVM already indexed the jar's
// central directory when it opened it.
//
// LOOSE ENTRIES, NOT A NESTED ZIP. `ZipFile` needs a real file and cannot open an archive inside
// another; `ZipInputStream` works over a resource stream and is strictly sequential, so every hover
// would decompress entries until it found the one it wanted. The only way out of that is extracting to
// disk on first run, which buys a temp file, a write and a staleness question at every mod update.
//
// WHOLE, NOT STRIPPED. `SourceHeaders` exists for an archive whose size is a problem; ours is 8.2 MB of
// text that deflates to under 3 MB, and full bodies mean the quoted declaration keeps the author's real
// layout. The transform is what makes a 43 MB `src.zip` viable, not this.
//
// It reaches the mod jar for free: `:mc1710`'s shadowJar copies `core.jar` with `from(zipTree(...))`,
// which takes every entry including these.
tasks.jar {
    from(sourceSets.main.get().allJava) { into("assets/crystalgui/sources") }
}

// Runs the worked example in com.crystalgui.example.machine end to end, printing every envelope
// the loopback wire. It is documentation you can execute:
//
//     ./gradlew :core:runExample
//
// ON THE HEADLESS CLASSPATH ON PURPOSE. That source set has CrystalGraphics deliberately absent, so
// the demo running at all is evidence the whole session layer is server-safe -- rather than a claim
// in a javadoc that nothing checks. Run it on main's runtime classpath instead and it would prove
// only that it works where everything is present, which is the case nobody doubts.
tasks.register<JavaExec>("runExample") {
    group = "documentation"
    description = "Runs com.crystalgui.example.machine.MachineDemo -- a server-built UI over a loopback wire."
    mainClass.set("com.crystalgui.example.machine.MachineDemo")
    classpath = headlessTest.runtimeClasspath
    // log4j2 with no configuration file defaults its root level to ERROR, so MachineTrace's INFO
    // lines -- the ones naming the thread each step ran on -- are dropped and the demo looks like it
    // has no logging at all. In game Minecraft configures log4j and they appear; here nothing does.
    systemProperty("org.apache.logging.log4j.level", "INFO")
}
