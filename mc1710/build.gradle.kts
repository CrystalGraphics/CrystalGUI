plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library`
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
// api() (not implementation) so consumers of :mc1710 (e.g. gl-debug-harness) get
// core classes on their compile classpath without a separate direct dependency.
dependencies {
    api(project(":core"))
}

// Remove Kotlin and a Java 9 file from JOML when shadowing.
// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
// This is for distributing the jar.
// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
// - Hussar
// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }
    exclude("module-info.class")
    exclude("kotlin/**")
    exclude("org/jetbrains/kotlin/**")

    // Bundle core/ subproject classes into the shadow JAR so the mod is self-contained
    dependsOn(":core:jar")
}

afterEvaluate {
    tasks.shadowJar.configure {
        val coreJar = project(":core").tasks.named<Jar>("jar").get()
        from(zipTree(coreJar.archiveFile.get()))
    }
}
