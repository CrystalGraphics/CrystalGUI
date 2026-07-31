// CrystalGUI — tree-sitter syntax backend.
//
// SEPARATE MODULE ON PURPOSE. `core/` must stay loadable on a dedicated server with no GL and no native
// libraries, so it defines the SyntaxTokenizer SPI and nothing more. Everything that needs a .dll/.so
// lives here, and an editor with this module absent simply falls back to the built-in lexer.
//
// The tree-sitter binding is consumed as built jars from a local fork rather than from Maven Central,
// because the official `jtreesitter` requires JDK 23+ and the Foreign Function & Memory API. The fork is
// `tree-sitter-ng`, JNI-based and compiled to Java 8 bytecode, and its jars bundle the natives for
// x86_64 Windows/Linux/macOS and aarch64 Linux/macOS.

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

// Overridable, because an absolute path in a build file is a portability bug waiting to happen. Settings
// only includes this module when the path resolves, so a checkout without the fork builds fine without it.
val treeSitterHome: String = (findProperty("treeSitterHome") as String?)
    ?: "${rootDir.parent}/tree-sitter/tree-sitter-ng-v0.26.6"

dependencies {
    api(project(":core"))
    implementation(files("$treeSitterHome/tree-sitter/build/libs/tree-sitter-0.26.6.jar"))
    implementation(files("$treeSitterHome/tree-sitter-java/build/libs/tree-sitter-java-0.23.5.jar"))

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
