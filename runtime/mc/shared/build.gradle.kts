// runtime/mc/shared — what every loader variant of CrystalGUI's single jar shares. EMPTY, and kept.
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
}

// A Java 8 toolchain would be ideal; the repository standardises on 21 and `release` is what actually
// pins the API surface, so nothing here can reach a Java 9+ method by accident.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}
