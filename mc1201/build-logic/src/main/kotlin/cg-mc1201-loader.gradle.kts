import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar

plugins {
    id("cg-java17")
    id("xyz.wagyourtail.jvmdowngrader")
}

// :core emits Java 21 bytecode (v65) and MC 1.20.1 ships a Java 17 runtime, so the bundled classes are
// rewritten to 17 -- the same mechanism mc1710 uses to reach Java 8. Compiling against v65 needs only a
// 21 toolchain (see cg-java17); LOADING it on a player's JVM needs this.
jvmdg.downgradeTo.set(JavaVersion.VERSION_17)

// LWJGL 3.3.1 -- what MC 1.20.1 ships -- predates Java 21 and does not recognise its JNI version. It
// patches the JNIEnv function table on a guessed layout anyway; under a debugger's JVMTI agent that
// table is instrumented, so the write lands past it and the process dies with a native fail-fast
// (0xC0000409 on Windows) before the window opens.
//
// A dev run here is ALWAYS on Java 21: cg-java17 raises the toolchain to 21 so javac can read :core's
// v65 classes, and ModDevGradle takes the run JVM from the toolchain. So the client runs fine and
// cannot be debugged -- which reads as an IDE fault rather than a library one.
//
// 3.3.3 knows the version and uses the right layout. Dev runs only; nothing shipped resolves LWJGL.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("LWJGL 3.3.1 corrupts the JNIEnv table on Java 21 under a debugger")
        }
    }
}


repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":core"))

    // Taffy and JOML: :core has them compileOnly so they reach nobody transitively, and UIElement holds
    // a NodeId and a Matrix4f as fields. Needed at RUNTIME too -- a field descriptor resolves at class
    // load, so without them the UI classes do not load at all. plan_mc1201.md 4.3.
    "compileOnly"(project(":taffy"))
    "runtimeOnly"(project(":taffy"))

    // Minecraft supplies log4j and gson. :core pins modern ones runtimeOnly for its tests and the
    // harness, and those reach a loader -- where NeoForge requires {strictly 2.19.0}/{strictly 2.10.1}
    // and the conflict fails its entire runtime graph.
    //
    // Per-dependency, never on the configuration: excluding the groups from runtimeClasspath would
    // take Minecraft's own log4j with it, which Loom's dev run reads.
    "runtimeOnly"(project(":mc1201:common")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }
    "runtimeOnly"(project(":core")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${property("mc1201.mixin")}")
    "annotationProcessor"("org.spongepowered:mixin:${property("mc1201.mixin")}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")
}

// Shared shadow JAR bundling: bundles :core and :mc1201:common into shadowJar.
cgbuildlogic.configureShadowJarBundling(project)

// A dev run must BUILD what mods{} makes visible.
//
// `mods { sourceSet(project(":mc1201:common")...) }` puts those classes on the run classpath, and that
// is all it does -- it creates no task dependency. `compileOnly`/`runtimeOnly` create none either that
// ModDevGradle honours. So a run can launch against a source set that was never compiled, and the
// symptom is a NoClassDefFoundError for a class that plainly exists on disk:
//
//     NoClassDefFoundError: com/crystalgui/mc/platform/Lifecycle1201
//         at com.crystalgui.mc.forge.CrystalGUI1201Forge.<init>
//
// which reads as a packaging or classloader fault rather than as a missing build step.
tasks.matching { it.name.startsWith("run") || it.name.startsWith("prepare") }.configureEach {
    dependsOn(":core:classes", ":mc1201:common:classes")
}

// The shipping jar, with every bundled class at Java 17.
//
// Downgrading the SHADOW jar rather than :core's own covers taffy and mc1201:common in one pass and
// needs one classpath. DowngradeJar.classpath must be set explicitly: it defaults to this module's
// compileClasspath, and jvmdg walks supertypes to decide what needs a stub -- an incomplete classpath
// is 1,800 lines of "Could not find class" and silently no stubs.
val downgradeShadowJar = tasks.register<DowngradeJar>("downgradeShadowJar") {
    group = "build"
    description = "Rewrites the shadow JAR's classes to Java 17."
    val shadow = tasks.named<org.gradle.api.tasks.bundling.Jar>("shadowJar")
    dependsOn(shadow)
    inputFile.set(shadow.flatMap { it.archiveFile })
    classpath = configurations.getByName("compileClasspath")
    archiveClassifier.set("java17")
}

tasks.named("assemble") { dependsOn(downgradeShadowJar) }
