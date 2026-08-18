import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library`
}

// Resolvable, and on no compile or run classpath -- see the `downgradeJar` block below for why that
// matters and why these jars may never be beside the application.
val engineApi: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgui").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")
// Composite build integration — injects CrystalGraphics dev deps + RunMinecraftTask bootstrap.
// Uses project-relative path (../gradle/...) to avoid Windows URI issues with rootProject.file().
// Use .toURI() to ensure forward-slash paths on Windows — IntelliJ Gradle sync fails on
// backslash File paths passed to apply(from = ...).
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())

// GTNH Gradle is forcing multirelease for 25,21 even if its set to empty in gradle.properties
// I spent an hour trying to fix this shit - and if not that it's trying to release the original
// no matter. this fixes it.
// :p
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

// :core subproject — platform-agnostic UI engine, bundled into this JAR.
//
// compileOnly, and it MUST NOT be api()/implementation(). :core compiles to Java 21 bytecode (its Jabel
// annotationProcessor is commented out, so nothing desugars it), and a dev run puts every runtime
// dependency on LaunchWrapper's classpath, where FML's ModDiscoverer opens each jar with
// asm-debug-all-5.0.3 looking for @Mod. ASM 5.0.3 tops out at Java 8 class files, so it fails on the
// first entry with:
//
//     There was a problem reading the entry com/crystalgui/core/CrystalGuiCore.class in the jar
//     .../core/build/libs/core.jar - probably a corrupt zip
//     Caused by: java.lang.IllegalArgumentException at org.objectweb.asm.ClassReader.<init>
//
// "probably a corrupt zip" is FML guessing, and the zip is fine -- which is what makes this expensive
// to diagnose from the message alone.
//
// The classes still reach runtime, downgraded: shadowJar below bundles core's output and jvmdg rewrites
// the result to major 52. So the mod jar carries a Java 8 core and the raw Java 21 jar stays off the
// classpath entirely. Verify with:
//     unzip -p mc1710/build/libs/crystalgui-1.0.0-dev.jar com/crystalgui/ui/UIWindow.class | od -An -t u1 -N 8
dependencies {
    compileOnly(project(":core"))

    // :language's engine API, for the DOWNGRADE CLASSPATH ONLY -- see downgradeJar below.
    engineApi(project(path = ":language", configuration = "engineApi"))
}

// ECJ, Rhino and the two Eclipse platform jars the adapter names, on `downgradeJar`'s classpath.
//
// NOT on the compile or run classpath, and that distinction is the whole point: an engine is loaded by
// EngineClassLoader from a band directory, so these must never be beside the application. But jvmdg is
// not running the classes, it is REWRITING them -- it walks the supertypes of every referenced type to
// decide what needs a Java 8 stub, and `DowngradeJar.classpath` defaults to this module's
// `main.compileClasspath`, which cannot contain them because `compileOnly` is not transitive across a
// project dependency.
//
// So every one of the 140 types in those jars was reported `Could not find class` and then treated as
// having no supertypes at all: 1,820 error lines per build. Harmless here -- the stub mapper only needs
// a supertype walk to notice a Java 9+ API reached THROUGH an inherited member, and these are all
// Java 8-era third-party APIs -- but a wall of red that hides anything real, and the same absence would
// be silent rather than harmless the day one of them did inherit something stubbed.
//
// Borrowed from :language rather than re-declared, so `jdt.core:3.26.0` is pinned in exactly one file.
tasks.named<DowngradeJar>("downgradeJar") {
    classpath = classpath.plus(engineApi)
}

// Remove Kotlin and a Java 9 file from JOML when shadowing.
// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
// This is for distributing the jar.
// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
// - Hussar
// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
// GL-state diagnostics, off unless asked for. CrystalGraphics/AGENTS.md documents both:
//
//   ./gradlew :mc1710:runClient -PcgNoDedup      never eliminate a GL call -- distinguishes "the state
//                                                shadow is lying" from a real rendering bug in one run
//   ./gradlew :mc1710:runClient -PcgStateVerify  verify the shadow against the driver before eliding
//                                                anything, naming the offending domain. Very slow.
//
// A missing GL call produces wrong rendering and NO exception, so there is nothing to search for
// without these; they are the only way to rule the manager in or out.
tasks.named<JavaExec>("runClient") {
    if (providers.gradleProperty("cgNoDedup").isPresent) {
        systemProperty("crystalgraphics.state.noDedup", "true")
    }
    if (providers.gradleProperty("cgStateVerify").isPresent) {
        systemProperty("crystalgraphics.state.verify", "true")
    }

    // THE ENGINE BANDS, staged for the dev run exactly as runHarness does it.
    //
    // ECJ and Rhino are NOT on the mod's classpath and must not be: EngineClassLoader loads each band
    // in isolation, and putting ECJ beside the application defeats the band split entirely. What they
    // need is a directory laid out one subdirectory per band, named by this property.
    //
    // Band 8 is the one that matters here and it is not a compromise -- the 1.7.10 dev run launches on
    // a Java 8 JVM, so EngineBand.detect() answers 8 and loads Rhino 1.7.15.1 and ECJ 3.26.0, which are
    // pinned precisely because they are the last releases whose class files that JVM can read.
    //
    // Absent is a legitimate deployment: EngineHost prints one line to stderr and the editor colours
    // without analysing. That is the degradation the whole stack is built around, so this is wiring a
    // capability on, not repairing a hole.
    dependsOn(":language:stageEngines")
    systemProperty("crystalgui.engines.dir",
        project(":language").layout.buildDirectory.dir("engines").get().asFile.absolutePath)

    // UNATTENDED CAPTURE:  ./gradlew :mc1710:runClient -PcgAutoTest
    //
    // Opens CrystalEditor over the main menu, paints ten frames, writes a PNG and quits -- so the whole
    // launch/open/look loop is one blocking command with an artifact at the end, exactly what
    // ArtifactService.requestCapture gives the GL debug harness. Every render defect before this was
    // diagnosed by a person launching the game and describing what they saw, which is slow and puts a
    // human in a loop that is really "render N frames and read the pixels".
    if (providers.gradleProperty("cgAutoTest").isPresent) {
        systemProperty("crystalgui.autotest", "true")
        systemProperty("crystalgui.autotest.out",
            layout.buildDirectory.file("crystalgui-autotest.png").get().asFile.absolutePath)
        // -PcgAutoTest=<saveFolder> loads that world first; a bare -PcgAutoTest stays on the main menu.
        providers.gradleProperty("cgLateFrame").orNull?.let {
            systemProperty("crystalgui.autotest.lateFrame", it)
        }
        val world = providers.gradleProperty("cgAutoTest").get()
        if (world.isNotEmpty() && world != "true") systemProperty("crystalgui.autotest.world", world)

        // -PcgScript=Probe.java compiles and runs one snippet once the editor is up, logging each step.
        // The EXTENSION picks the language, which is the comparison that matters when one runs and the
        // other kills the client. -PcgScriptSource overrides the snippet. @see CgUiAutoTest#runScriptOnce
        providers.gradleProperty("cgScript").orNull?.let {
            systemProperty("crystalgui.autotest.script", it)
        }
        providers.gradleProperty("cgScriptSource").orNull?.let {
            systemProperty("crystalgui.autotest.scriptSource", it)
        }
        providers.gradleProperty("cgFrame").orNull?.let {
            systemProperty("crystalgui.autotest.frame", it)
        }
    }
}

tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }
    exclude("module-info.class")
    exclude("kotlin/**")
    exclude("org/jetbrains/kotlin/**")

    // Bundle core/ and language/ classes into the shadow JAR so the mod is self-contained
    dependsOn(":core:jar")
    dependsOn(":language:jar")
}

afterEvaluate {
    tasks.shadowJar.configure {
        val coreJar = project(":core").tasks.named<Jar>("jar").get()
        from(zipTree(coreJar.archiveFile.get()))

        // :language, and the tree-sitter jars it needs.
        //
        // UNPACKED HERE RATHER THAN DECLARED AS `shadowImplementation`, and the difference is not
        // stylistic: shadowImplementation RELOCATES, and every tree-sitter jar carries the JNI natives
        // for its grammar. A JNI symbol is named Java_<mangled-package>_<class>_<method>, so renaming
        // the Java package renames the symbol the .dll does NOT export, and the first parser built
        // throws UnsatisfiedLinkError. `from(zipTree(...))` copies the entries verbatim, which is what
        // both the classes and the natives need.
        //
        // (Taffy above is relocated and that is fine -- it is pure Java, zero natives, checked.)
        val languageJar = project(":language").tasks.named<Jar>("jar").get()
        from(zipTree(languageJar.archiveFile.get()))
        rootProject.file("lib/tree-sitter").listFiles()
            ?.filter { it.name.endsWith(".jar") }
            ?.forEach { from(zipTree(it)) }
    }
}
