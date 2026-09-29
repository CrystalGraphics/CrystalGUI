// CrystalGUI's scenes for the GL debug harness, which is CrystalGraphics-only: they reach it as a
// HarnessExtension (CrystalGuiHarness), and this script puts them on `:gl-debug-harness:runHarness`.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":gl-debug-harness"))
    implementation(project(":core"))
    // The real parsers. core/ ships word-list lexers so it can load with no natives at all, and they are
    // genuinely fine for keywords, strings and comments -- but a lexer calls any identifier before a "("
    // a function, so a constructor, an enum constant, a declaration and a call are one colour and no
    // scheme can separate them.
    implementation(project(":language"))
    implementation(project(":taffy"))
    implementation("com.google.code.findbugs:jsr305:3.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The harness cannot depend on this module, so this module wires itself into the harness's run.
evaluationDependsOn(":gl-debug-harness")
val harness = project(":gl-debug-harness")

(harness.extra["hostAssetRoots"] as ConfigurableFileCollection).from(
    file("src/main/resources"),
    project(":core").file("src/main/resources"))

harness.tasks.named<JavaExec>("runHarness") {
    classpath += sourceSets.main.get().runtimeClasspath

    // THE ENGINE BANDS, staged one directory per band. Without this the harness opens no engine and the
    // whole semantic layer is silently absent: no diagnostics, no semantic colouring, no Run command --
    // because `EngineSource.NONE` is a legitimate deployment and nothing anywhere treats it as an error.
    //
    // A DIRECTORY rather than the jars on the classpath, and that is the point: the engines must load in
    // EngineClassLoader's isolation, not beside the application.
    dependsOn(":language:stageEngines")
    systemProperty("crystalgui.engines.dir",
        project(":language").layout.buildDirectory.dir("engines").get().asFile.absolutePath)

    // CrystalGUI's own debug flags (crystalgui.keymap.trace), forwarded as the harness forwards
    // crystalgraphics.*: set on the Gradle daemon they would reach nothing.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("crystalgui.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
}
