// CrystalGUI — the language stack. Everything below L3 that is not `core/`'s interfaces.
//
// SEPARATE MODULE ON PURPOSE. `core/` must stay loadable on a dedicated server with no GL, no native
// libraries and no 15MB compiler, so it defines the SPIs (`text.syntax`, `text.lang`) and nothing more.
// Everything with a .dll/.so or an engine behind it lives here, and an application with this module
// absent behaves exactly as it did before — the built-in lexer colours, and nothing resolves.
//
// Sub-packages by concern, so a later split is a move rather than an untangling (plan_syntax.md §5.1):
//
//   .grammar   tree-sitter: tokenizer, query loading, injections            <- here today
//   .java      the ECJ adapter: compile, bindings, diagnostics, completion  <- M5/M6
//   .js        the Rhino adapter: execution, parse diagnostics              <- M10
//   .resolve   engine-neutral: type index, fuzzy matcher, sandbox policy    <- M9
//
// AND ONE SPLIT INSIDE THIS MODULE: execution must not require the grammar natives. A dedicated server
// runs scripts and has no editor, so `.java`/`.js`/`.resolve` must never touch `.grammar` — lazy class
// init is the mechanism and an M7 headless test is the proof.
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
    implementation(files(rootProject.file("lib/tree-sitter/tree-sitter-xml-0.7.0.jar")))

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
