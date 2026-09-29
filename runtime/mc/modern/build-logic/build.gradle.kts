plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    maven("https://maven.neoforged.net/releases") // ModDevGradle — needed for neoFormRuntime {} in cg-modern-common
}

dependencies {
    // jvmDowngrader: :core emits Java 25 bytecode and MC 1.20.1 ships a Java 17 runtime, so the shipped
    // jar's classes are rewritten to 17. Same mechanism mc1710 uses to reach Java 8.
    implementation("xyz.wagyourtail.jvmdowngrader:gradle-plugin:1.3.5")

    // Shadow, so ShadowJar is a type these scripts can name. The version matches the pin in
    // settings.gradle.kts that every loader applies; two spellings of one version is the hazard
    // gradle.properties spends a paragraph on.
    // The shared single-jar tasks: CheckSingleJar and ModDescriptor. @see CrystalGraphics/singlejar-logic
    implementation("com.crystalgraphics.build:singlejar-logic")

    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.2.2")
}
