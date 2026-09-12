// runtime/mc/shared — what every loader variant of CrystalGUI's single jar shares.
//
// SINCE J11.0 it carries the variant selector: the table a merged jar declares itself with, the range
// test, the reader and the reflection that spans an FML API change. One jar holds several loaders'
// entry classes and, above one Minecraft version per loader, several of each; this is the half that
// decides which one runs, and it names no loader to do it.
//
// `LoaderProbe` and `CrashVariant` lived here and are CrystalGraphics' now: CrystalGUI requires
// CrystalGraphics on every loader, so a second copy bought nothing. Anything added here is merged once,
// never relocated and never remapped, so it may name no Minecraft class and no loader.
//
// JAVA 8 SOURCE AND TARGET. Its classes run under FML 1.7.10, whose ModDiscoverer reads every entry
// of every jar with asm-debug-all-5.0.3 and refuses anything above major 52 -- and unlike `core`,
// nothing downgrades this module on the way in.

plugins {
    `java-library`
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc-shared") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

dependencies {
    // The ONLY dependency, and compileOnly: every loader supplies Mixin at runtime, and a second copy
    // in the jar would be a second `MixinService` for the one already running.
    //
    // 0.8.5 rather than the shaded Mixin GTNH's 1.7.10 toolchain puts on `mc1710`'s classpath, whose
    // IMixinConfigPlugin takes `org.spongepowered.asm.lib.tree.ClassNode`. E-J2 measured that
    // UniMixins TRANSFORMS a plugin compiled against vanilla 0.8.5 to fit its environment, so one
    // class serves LaunchWrapper, ModLauncher and Knot alike.
    compileOnly("org.spongepowered:mixin:0.8.5")

    // ASM, because `IMixinConfigPlugin` takes a `ClassNode` in two of its methods and Mixin's own POM
    // does not bring it. compileOnly for the same reason Mixin is: every loader has one, and the
    // version it has is the one that must be used.
    compileOnly("org.ow2.asm:asm-tree:${property("asmVersion")}")

    // The one line a bootstrapper prints, and nothing else. 1.7.10's own log4j, so this cannot reach
    // an API a newer runtime supplies.
    compileOnly("org.apache.logging.log4j:log4j-api:${property("mcshared.log4j")}")

    // The variant table decides which entry class every loader constructs, and it is read from a
    // resource on every boot -- so it is worth a test that runs without one.
    testImplementation("junit:junit:${property("dep.junit")}")
}

// NO LOADER CODE HERE, and that is a measured decision rather than a preference.
//
// The annotated bootstrapper cannot live in this module: it has to be part of the MOD for a loader's
// scanner to find it, and this module reaches a dev run as a LIBRARY -- ModDevGradle's
// `additionalRuntimeClasspath`, "dependencies of every run, that should not be considered boot
// classpath modules". A class on it is never scanned for `@Mod`. So each loader module carries its
// own ~20-line bootstrapper, and what lives here is the half that names no loader at all: the table,
// the range test, the reader and the reflection that spans an FML API change.
//
// Measured on 2026-09-12: with the bootstrapper here instead, a Forge dedicated server failed mod
// discovery outright -- "The Mod File build/dev-resources has mods that were not found" -- and moving
// only that class into the loader module got past it, to a NoClassDefFoundError for this module's own
// classes, which is what named the missing `additionalRuntimeClasspath` entry.

// A Java 8 toolchain would be ideal; the repository standardises on 21 and `release` is what actually
// pins the API surface, so nothing here can reach a Java 9+ method by accident.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}
