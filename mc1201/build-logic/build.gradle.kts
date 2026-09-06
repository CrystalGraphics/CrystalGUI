plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    maven("https://maven.neoforged.net/releases") // ModDevGradle — needed for neoFormRuntime {} in cg-mc1201-common
}

dependencies {
    // ModDevGradle NeoForm mode: provides MC classes as compileOnly without the NeoForge modloader.
    // Used by cg-mc1201-common.gradle.kts to put MC 1.20.1 on the compileOnly classpath of :mc1201:common.
    implementation("net.neoforged:moddev-gradle:2.0.141")

    // jvmDowngrader: :core emits Java 21 bytecode and MC 1.20.1 ships a Java 17 runtime, so the shipped
    // jar's classes are rewritten to 17. Same mechanism mc1710 uses to reach Java 8.
    implementation("xyz.wagyourtail.jvmdowngrader:gradle-plugin:1.3.5")
}
