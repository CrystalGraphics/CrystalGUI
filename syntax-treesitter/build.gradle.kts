// CrystalGUI — tree-sitter syntax backend.
//
// SEPARATE MODULE ON PURPOSE. `core/` must stay loadable on a dedicated server with no GL and no native
// libraries, so it defines the SyntaxTokenizer SPI and nothing more. Everything that needs a .dll/.so
// lives here, and an editor with this module absent simply falls back to the built-in lexer.
//
// The tree-sitter binding is consumed as jars checked in under lib/, because the official `jtreesitter`
// requires JDK 23+ and the Foreign Function & Memory API. See lib/tree-sitter/README.md for provenance and licence.

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))

    // Checked in under lib/ rather than resolved. The official `jtreesitter` needs JDK 23+ and the
    // Foreign Function & Memory API, which a Java 8 bytecode target cannot use, so these come from a fork
    // of `tree-sitter-ng` that is JNI-based and compiles to Java 8. They used to be read from a local
    // checkout through a `treeSitterHome` property, which made the build depend on one machine's
    // directory layout -- see lib/tree-sitter/README.md.
    //
    // The natives are inside the jars: x86_64 Windows/Linux/macOS and aarch64 Linux/macOS. The grammar
    // jar needs the core jar at runtime, so both are listed.
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-0.26.6.jar")))
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-java-0.23.5.jar")))
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-css-0.25.0.jar")))
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-javascript-0.25.0.jar")))
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-html-0.23.2.jar")))
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-glsl-0.2.0.jar")))

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Native loading is the one thing that cannot be asserted without the platform it runs on. A failure
    // here is reported rather than swallowed, but the tests themselves skip cleanly when the library will
    // not load -- see TreeSitterTokenizerTest.
    testLogging {
        showStandardStreams = true
    }
}
