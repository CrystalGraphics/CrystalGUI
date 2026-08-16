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
//   .map       readable <-> runtime mapping boundary, on plain ASM          <- M6
//   .run       execution: lifecycle, safepoints, cache, run/stop commands   <- M7
//   .resolve   engine-neutral: type index, fuzzy matcher, sandbox policy    <- M9
//
// AND ONE SPLIT INSIDE THIS MODULE: execution must not require the grammar natives. A dedicated server
// runs scripts and has no editor, so `.java`/`.js`/`.map`/`.run`/`.resolve` must never touch `.grammar`.
// ExecutionNeedsNoGrammarTest is the proof, and it is a BYTECODE SCAN rather than a runtime check: a
// reference in the constant pool is the real question, because if a class file names a tree-sitter type
// at all then some input can reach it. It fails on the commit that adds the import rather than on a
// server months later with a NoClassDefFoundError naming a native library.
//
// The tree-sitter binding is consumed as jars checked in under lib/, because the official `jtreesitter`
// requires JDK 23+ and the Foreign Function & Memory API. See lib/tree-sitter/README.md for provenance and licence.

// `java` is the JavaPluginExtension accessor inside a Kotlin DSL script, which shadows the PACKAGE of
// the same name -- so `java.util.zip.ZipFile` does not resolve here and has to be imported.
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.jar.JarFile
import java.util.zip.ZipFile

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

// ── Pinned engine versions, one place ───────────────────────────────────────────────────────────
//
// The numbers themselves are load-bearing and each was measured rather than assumed. `EngineBand` in
// the Java sources carries the same three pins and `PinnedEngineVersionsTest` asserts the two agree —
// a build that resolves one version while the runtime asks for another fails at the user, on the one
// platform nobody building it has.
val jdtBand8 = "3.26.0"        // Eclipse 4.21, 2021-09. 3.27.0 is the first that needs Java 11
val jdtBand11 = "3.33.0"       // Eclipse 4.28, 2023-06. 3.34.0 is the first that needs Java 17
val jdtBand17 = "3.46.0"       // newest at 2026-08-12
val rhinoBand8 = "1.7.15.1"    // last release whose class files are Java 8
// `org.eclipse.text` — where IDocument and TextEdit live, and therefore what ASTRewrite's only usable
// exit is spelled in. Named here rather than only inside platformBand8 because the ADAPTER compiles
// against it too (see the compileOnly pair below), and two spellings of one version is the hazard the
// note above this block already describes.
val eclipseTextBand8 = "3.11.0"
val asmVersion = "9.10"     // real classes are major 49; only module-info is 53
val rhinoModern = "1.9.1"      // needs Java 11 -- so bands 11 and 17 SHARE it; see EngineBand

// ── The Eclipse platform closure, pinned per band ───────────────────────────────────────────────
//
// EVERY TRANSITIVE ARTIFACT IS PINNED, and that is not belt-and-braces. `org.eclipse.jdt.core`
// declares its platform dependencies as OPEN RANGES -- `[3.14.0,4.0.0)` -- so a resolver takes
// whatever is newest on the day it runs. Two things break at once:
//
//   1. THE BAND GUARANTEE. Pinning only jdt.core:3.26.0 for band 8 resolved
//      org.eclipse.osgi-3.24.200 and jna-5.18.1 beside it -- 2024-era jars whose class files are
//      major 53+ and cannot load on Java 8 at all. The top artifact was right and the closure was
//      unloadable, which is the failure mode that reaches a user and nobody else.
//   2. REPRODUCIBILITY. The same build resolves differently in six months, with no commit to blame.
//
// The versions below are the newest of each artifact whose BASE class files are within the band's
// ceiling (52/55/61), found by walking every published version and reading the jar. `checkEngineBands`
// re-derives the floor from the resolved files and fails the build, so a future range widening cannot
// reintroduce this quietly.
val platformBand8 = listOf(
    "org.eclipse.platform:org.eclipse.core.resources:3.14.0",
    // 3.20.100 and NOT 3.22.0, which is also major 52 and also loads. Eclipse rotated its signing
    // certificate between 4.19 and 4.20: 3.22.0 carries serial 34a447.., equinox.common's newest
    // Java-8 version carries 15d2bad0.., and the two SPLIT THE `org.eclipse.core.runtime` PACKAGE
    // between them. A JVM refuses a package whose classes come from differently-signed jars, so the
    // pair loaded fine, resolved fine, and threw SecurityException on first use -- on Java 8 only.
    // `checkEngineBands` now derives this from the certificates rather than trusting this comment.
    "org.eclipse.platform:org.eclipse.core.runtime:3.20.100",
    "org.eclipse.platform:org.eclipse.core.filesystem:1.9.300",
    "org.eclipse.platform:org.eclipse.text:$eclipseTextBand8",
    "org.eclipse.platform:org.eclipse.core.jobs:3.11.0",
    "org.eclipse.platform:org.eclipse.core.contenttype:3.7.1000",
    "org.eclipse.platform:org.eclipse.core.expressions:3.7.100",
    "org.eclipse.platform:org.eclipse.core.commands:3.9.800",
    "org.eclipse.platform:org.eclipse.equinox.common:3.14.100",
    "org.eclipse.platform:org.eclipse.equinox.preferences:3.9.100",
    "org.eclipse.platform:org.eclipse.equinox.registry:3.10.100",
    "org.eclipse.platform:org.eclipse.equinox.app:1.5.100",
    "org.eclipse.platform:org.eclipse.osgi:3.16.100",
)
val platformBand11 = listOf(
    "org.eclipse.platform:org.eclipse.core.resources:3.18.200",
    "org.eclipse.platform:org.eclipse.core.runtime:3.26.100",
    "org.eclipse.platform:org.eclipse.core.filesystem:1.9.500",
    "org.eclipse.platform:org.eclipse.text:3.12.300",
    "org.eclipse.platform:org.eclipse.core.jobs:3.13.300",
    "org.eclipse.platform:org.eclipse.core.contenttype:3.8.200",
    "org.eclipse.platform:org.eclipse.core.expressions:3.8.200",
    "org.eclipse.platform:org.eclipse.core.commands:3.10.400",
    "org.eclipse.platform:org.eclipse.equinox.common:3.18.200",
    "org.eclipse.platform:org.eclipse.equinox.preferences:3.10.400",
    "org.eclipse.platform:org.eclipse.equinox.registry:3.11.400",
    "org.eclipse.platform:org.eclipse.equinox.app:1.6.400",
    "org.eclipse.platform:org.eclipse.osgi:3.24.200",
)

/** Class-file major a band's JVM can load: 52 = Java 8, 55 = Java 11, 61 = Java 17. */
val bandCeiling = mapOf("8" to 52, "11" to 55, "17" to 61)

// Resolvable, and consumed by nothing. `./gradlew :language:engineReport` prints each band's closure.
val engineBand8: Configuration by configurations.creating { isCanBeConsumed = false }
val engineBand11: Configuration by configurations.creating { isCanBeConsumed = false }
val engineBand17: Configuration by configurations.creating { isCanBeConsumed = false }

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

    // @Nullable, the same way `core/` gets it and for the same reason -- javax.annotation is not on the
    // module path from JDK 11. **compileOnly is the mitigation, not an oversight**: the package only
    // clashes with the JDK's own when it is on a RUNTIME classpath, and it is on neither module's --
    // `:language:dependencies --configuration runtimeClasspath` shows no jsr305 at all. Spotbugs'
    // `edu.umd.cs.findbugs.annotations` would dodge the clash structurally and is the right answer if
    // these ever have to ship; while both are compile-only it buys nothing and would put two spellings
    // of @Nullable in one codebase, since core has hundreds of the javax one.
    //
    // This module had no nullability annotations at all before, which was an accident of nothing needing
    // them rather than a decision.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")


    // THE ADAPTER COMPILES AGAINST THE OLDEST BAND, AND ONLY THE OLDEST (§6.3). `compileOnly`, so it
    // never reaches a runtime classpath -- at run time the engine comes from EngineClassLoader and this
    // artifact is not there at all. Pinning band 8's version here is what turns "one adapter across
    // three bands" into a compile-time guarantee: an API added after 3.26.0 fails the build rather than
    // failing on a Java 8 host months later.
    //
    // Note this is jdt.core ALONE, without band 8's platform closure. The adapter uses the compiler,
    // not the workspace, and pulling the closure in would let it reach APIs that happen to resolve here
    // and are absent from a real deployment's loader.
    compileOnly("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand8")

    // AND org.eclipse.text, for the SAME BAND and for one reason: `ASTRewrite` lives in jdt.core but
    // both its exits are spelled in types that do not -- `rewriteAST` takes an `org.eclipse.jface.text
    // .IDocument` and returns an `org.eclipse.text.edits.TextEdit`, and neither is nameable without
    // this artifact. Every quick fix is built on that rewrite, so without this line the whole
    // correction layer would have to hand-roll text ranges instead. @see Rewrites
    //
    // THIS IS NOT THE CLOSURE THE COMMENT ABOVE REFUSES. That refusal is about reaching APIs which
    // might be absent from a deployment's loader; this jar is pinned into `platformBand8`,
    // `platformBand11` and band 17's resolved closure alike, so it is present at runtime on every band
    // -- compiling against it asserts something that is true in production rather than only here.
    // Pinned to band 8's version for the usual reason: an API added later must fail the build now.
    compileOnly("org.eclipse.platform:org.eclipse.text:$eclipseTextBand8")

    // AND ON THE TEST COMPILE PATH, so a fix's test can write `IProblem.UnusedImport` instead of the
    // literal 268435844. That is not merely nicer to read: a correction is keyed on a problem id, and a
    // test naming the id in digits is a test nobody can check against the table it is testing.
    //
    // COMPILE ONLY, AND IT STAYS THAT WAY. These are `static final int`, so javac inlines them and the
    // compiled test carries the number with no reference to the class -- which is what keeps the tests
    // honest, because the ONLY route to an engine remains EngineSource + EngineClassLoader. The trap it
    // opens is narrow and loud: naming a non-constant JDT API here compiles and then dies at runtime with
    // NoClassDefFoundError, since jdt.core is on no test RUNTIME classpath. Constants only.
    testCompileOnly("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand8")

    // ── ASM, for the mapping boundary (§15.5) ───────────────────────────────────────────────────
    //
    // A REAL DEPENDENCY, not compileOnly: the remapper runs wherever a script is compiled, so it ships.
    // Chosen over tiny-remapper (§23 row 13) on three measurements rather than taste -- ASM's real
    // classes are major 49 (Java 5), so it runs on every band; the three jars total 0.24 MB; and it has
    // no transitive dependencies at all. tiny-remapper is a similar size on its own but pulls asm-util
    // and mapping-io behind it, and its API is built around remapping jars on disk with a thread pool,
    // which is a workflow rather than the one focused thing needed here.
    //
    // Only `module-info.class` in those jars is major 53, and a Java 8 JVM never reads it -- which is
    // exactly the false positive `checkEngineBands` now skips.
    api("org.ow2.asm:asm:$asmVersion")
    api("org.ow2.asm:asm-commons:$asmVersion")
    api("org.ow2.asm:asm-tree:$asmVersion")

    // ── The engine bands (plan_syntax.md §6) ────────────────────────────────────────────────────
    //
    // DELIBERATELY NOT ON ANY COMPILE OR RUNTIME CLASSPATH. These configurations are resolvable and
    // nothing consumes them, because an engine is loaded reflectively into an isolated child-first
    // classloader at runtime -- see EngineBand. Putting ECJ on `implementation` would let a stray import
    // compile against whichever band happened to be declared, which is the one mistake the whole banding
    // design exists to prevent: the adapter must compile against the OLDEST band's API and no other.
    //
    // BOUNDARIES MEASURED, NOT READ OFF RELEASE NOTES. Each pin below is the last version whose base
    // class files load on that band's JVM, checked by reading the class-file major version out of the
    // jar (52/55/61) and ignoring META-INF/versions/, which an older JVM never looks at.
    engineBand8("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand8")
    engineBand8("org.mozilla:rhino:$rhinoBand8")
    platformBand8.forEach { engineBand8(it) }

    engineBand11("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand11")
    engineBand11("org.mozilla:rhino:$rhinoModern")
    platformBand11.forEach { engineBand11(it) }

    // Band 17 takes what the ranges resolve to: its ceiling is "whatever is newest", so the two
    // failure modes above collapse into one -- a build that is not byte-identical across months. That
    // is worth fixing when this ships, and is not worth pinning thirteen artifacts for while the
    // adapter does not exist. `checkEngineBands` still holds it to major 61.
    engineBand17("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand17")
    engineBand17("org.mozilla:rhino:$rhinoModern")
}

/** Highest class-file major among a jar's BASE entries — `META-INF/versions/` is what an old JVM ignores. */
fun baseClassMajor(jar: File): Int {
    var highest = 0
    val zip = ZipFile(jar)
    try {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.getName()
            if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")) continue
            // module-info.class IS ALWAYS MAJOR 53+, because modules are a Java 9 feature -- and a
            // Java 8 JVM does not read it at all. Counting it reports every modern jar as needing
            // Java 9 whatever its real classes are: ASM's are major 49 and it would have been rejected
            // from band 8 on the strength of a file that band never opens. A check that fails falsely
            // is as bad as one that passes falsely, and this one nearly cost a correct dependency.
            if (name == "module-info.class" || name.endsWith("/module-info.class")) continue
            val stream = zip.getInputStream(entry)
            try {
                val head = ByteArray(8)
                if (stream.read(head) == 8 && head[0] == 0xCA.toByte() && head[1] == 0xFE.toByte()) {
                    val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                    if (major > highest) highest = major
                }
            } finally {
                stream.close()
            }
        }
    } finally {
        zip.close()
    }
    return highest
}

/**
 * What each band actually costs, and what is in it.
 *
 * <p>Not part of any build — a report, run by hand when a pin changes. The weight matters because
 * these ship inside a mod jar: the plan priced the DOM stack at "~10–15MB" and the real closure is
 * what decides whether all three bands can be bundled together or whether a build has to pick one.</p>
 */
/**
 * A jar's signing certificate, as a short fingerprint — or "unsigned".
 *
 * <p>Read straight out of the PKCS#7 signature block (the `.RSA`, `.DSA` or `.EC` entry under
 * `META-INF`) rather than through `JarEntry.getCertificates()`. That API is the obvious one and it is
 * a trap here: it answers null until the entry's stream has been drained, and it answered null even
 * then for several of these jars — so a check built on it reported every Eclipse jar as unsigned and
 * passed unconditionally, which is worse than no check at all. `CertificateFactory` parses the block
 * directly and needs no verification dance.</p>
 *
 * <p><b>Note for anyone editing the comments in this file:</b> Kotlin block comments NEST, unlike
 * Java's. A slash-star sequence inside a doc comment opens a nested comment, whose close then ends
 * only the inner one — leaving the outer comment open and silently swallowing the rest of the script.
 * Everything after it stops registering, `gradlew tasks` still succeeds, and the only symptom is a
 * task that "does not exist". Writing a glob such as the one above with a literal star cost an hour
 * here. Do not put a slash-star in a comment.</p>
 */
fun signerFingerprint(file: File): String {
    val zip = ZipFile(file)
    try {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.getName().uppercase()
            if (!name.startsWith("META-INF/")) continue
            if (!(name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) continue
            val stream = zip.getInputStream(entry)
            try {
                val certificates = CertificateFactory.getInstance("X.509").generateCertificates(stream)
                if (certificates.isEmpty()) continue
                // THE WHOLE CHAIN, ORDER-INDEPENDENT -- not the first certificate. The block holds the
                // leaf AND its issuers, and generateCertificates does not promise leaf-first: both
                // Eclipse eras share the same DigiCert intermediate, so hashing "the first one" made two
                // genuinely different signers look identical and the check passed on the exact pin it
                // was written to catch. Verified by putting the bad pin back and watching it fail.
                val perCert = certificates.map { certificate ->
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())
                            .joinToString("") { byte -> "%02x".format(byte) }
                }.sorted()
                val digest = MessageDigest.getInstance("SHA-256")
                        .digest(perCert.joinToString("|").toByteArray(Charsets.UTF_8))
                val hex = StringBuilder()
                for (index in 0 until 8) hex.append("%02x".format(digest[index]))
                return hex.toString()
            } catch (unreadable: Exception) {
                return "unreadable-signature"
            } finally {
                stream.close()
            }
        }
        return "unsigned"
    } finally {
        zip.close()
    }
}

/**
 * No Java package may be split across jars signed by different certificates.
 *
 * <p><b>This is the check that cost a Java 8 debugging session.</b> Eclipse rotated its signing
 * certificate between 4.19 and 4.20, and the `org.eclipse.core.runtime` package is split across
 * `org.eclipse.core.runtime` and `org.eclipse.equinox.common`. Pinning each artifact to "the newest
 * version that loads on Java 8" — mechanically right, semantically wrong — took one from each side of
 * the rotation. Every jar resolved, every class file was major 52, the ceiling check was green, and
 * the JVM threw `SecurityException: signer information does not match` on the first `ASTParser`
 * construction. On Java 8 only, because that is the only band the mismatched pair could occur in.</p>
 *
 * <p>The default package is skipped: several jars ship a stray class in it, and "" is not a package
 * two libraries meaningfully share — reporting it pairs every unsigned jar with every signed one and
 * drowns the real finding.</p>
 */
fun signerConflicts(band: String, jars: List<File>): List<String> {
    val byPackage = mutableMapOf<String, MutableMap<String, String>>()
    for (file in jars) {
        val fingerprint = signerFingerprint(file)
        val zip = ZipFile(file)
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().getName()
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue
                val slash = name.lastIndexOf('/')
                if (slash < 0) continue
                val packageName = name.substring(0, slash).replace('/', '.')
                byPackage.getOrPut(packageName) { mutableMapOf() }.putIfAbsent(fingerprint, file.name)
            }
        } finally {
            zip.close()
        }
    }
    return byPackage.filterValues { it.size > 1 }.map { (packageName, signers) ->
        "band $band: package $packageName is split across differently-signed jars — " +
                signers.entries.joinToString(", ") { "${it.value} (${it.key})" } +
                ". A JVM refuses this with SecurityException on first use."
    }
}

// ── Fixtures → the harness's scratch workspace ──────────────────────────────────────────────────
//
// `src/test/resources/fixtures/` holds documents that exist to be OPENED rather than run: the syntax
// colouring samples, and the `// FIX:` annotated files a person works down to try every quick fix in a
// family. The harness reads them from `gl-debug-harness/workspace/`, which is GITIGNORED -- it is a
// scratch project the user edits -- so a tracked copy has to live here and be carried across.
//
// That copy was a `cp` line in the fixtures README, which is the kind of step that is done once and then
// silently not repeated when a fixture changes. This is the same step, spelled so it can be re-run.
//
// WRITE-IF-ABSENT, NEVER OVERWRITE, which is the one thing a plain `Copy` task would get wrong: the
// destination is a workspace somebody has been typing in, and clobbering an edited file to install a
// fixture would lose work. Delete the file to take a fresh copy.
val installHarnessFixtures = tasks.register("installHarnessFixtures") {
    group = "harness"
    description = "Copies the tracked fixture documents into the GL harness's scratch workspace."

    val from = layout.projectDirectory.dir("src/test/resources/fixtures")
    val into = rootProject.layout.projectDirectory.dir("gl-debug-harness/workspace/src")
    inputs.dir(from)

    doLast {
        val target = into.asFile
        if (!target.parentFile.parentFile.exists()) {
            logger.lifecycle("gl-debug-harness is not checked out — nothing to install")
            return@doLast
        }
        target.mkdirs()
        var written = 0
        var kept = 0
        from.asFile.listFiles()?.sortedBy { it.name }?.forEach { source ->
            if (source.isDirectory || source.name.endsWith(".md")) return@forEach
            val destination = target.resolve(source.name)
            if (destination.exists()) {
                kept++
            } else {
                source.copyTo(destination)
                written++
            }
        }
        logger.lifecycle("harness fixtures: $written installed, $kept already present at $target")
    }
}

tasks.register("engineReport") {
    group = "verification"
    description = "Resolves each engine band and reports its jars, sizes and class-file floors."
    val bands = mapOf("8" to engineBand8, "11" to engineBand11, "17" to engineBand17)
    val resolved = bands.mapValues { (_, configuration) -> configuration.resolve().toList() }
    doLast {
        resolved.forEach { (band, files) ->
            val total = files.sumOf { it.length() }
            println("-- band $band: ${files.size} jars, ${"%.1f".format(total / 1048576.0)} MB " +
                    "(ceiling major ${bandCeiling[band]})")
            files.sortedByDescending { it.length() }.forEach {
                println("     %8.2f MB  major %-3d %s".format(
                        it.length() / 1048576.0, baseClassMajor(it), it.name))
            }
        }
    }
}

/**
 * Every jar in a band must load on that band's JVM.
 *
 * <p>This is the check that would have caught the open-range resolution described above, and it is a
 * build failure rather than a report because the alternative is discovering it on a Java 8 host at a
 * user's machine. Reading the class-file major is the only honest test — a POM says what a publisher
 * intended, the bytes say what a JVM will accept.</p>
 */
// ── Does a band actually RUN on the JVM it is pinned for? ───────────────────────────────────────
//
// A SEPARATE SOURCE SET COMPILED TO JAVA 8, and it has to be. This module compiles to Java 21, so a
// JUnit test asking "does band 8 work on Java 8" could not load on the JVM it was asking about — the
// question would go unanswered while the test suite went green on Java 21.
//
// `checkEngineBands` proves the jars are LOADABLE and `EngineApiSurfaceTest` proves the API is PRESENT;
// neither runs a compiler. §6.4 rejected downgrading the newest ECJ precisely because its runtime
// behaviour "includes reading ct.sym/jrt images and JPMS metadata, which API stubbing cannot fake" —
// and rt.jar-versus-jrt is exactly what differs between a Java 8 host and a Java 17 one. So this
// resolves a real generic binding against the running VM's own class library, on each launcher.
val bandSmoke: SourceSet by sourceSets.creating

tasks.named<JavaCompile>("compileBandSmokeJava") {
    // Java 8 SOURCE and 8 bytecode, via --release so the Java 21 toolchain cannot let a newer API slip
    // in. `sourceCompatibility` alone would compile against Java 21's class library and fail at run time
    // on a NoSuchMethodError naming a method that plainly exists — on the developer's machine.
    options.release.set(8)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

/** One smoke run: a band's jars, under a launcher of the given feature version. */
fun registerBandSmoke(band: String, launcher: Int, configuration: Configuration) =
        tasks.register<JavaExec>("smokeBand$band") {
            group = "verification"
            description = "Runs band $band's engines under a Java $launcher JVM."
            dependsOn(tasks.named("compileBandSmokeJava"))
            classpath = bandSmoke.output
            mainClass.set("BandSmoke")
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(launcher))
            })
            argumentProviders.add(CommandLineArgumentProvider {
                listOf(band, configuration.asPath)
            })
        }

// Band 8 on a real Java 8 JVM is the one that matters: it is the only band whose host cannot run the
// module that selects it, and the only one where rt.jar rather than a jrt image is what JDT reads.
val smokeBand8 = registerBandSmoke("8", 8, engineBand8)
// Band 11's own launcher is not installed here, and 17 runs its jars faithfully — a Java 11 class file
// loads unchanged on 17. Recorded rather than silently skipped: this checks the JARS, not the band's
// floor, which `checkEngineBands` covers.
val smokeBand11 = registerBandSmoke("11", 17, engineBand11)
val smokeBand17 = registerBandSmoke("17", 17, engineBand17)

// ── Staging the engines where a running application can find them ───────────────────────────────
//
// One directory per band, named by its minimum feature version -- exactly the layout
// `EngineSource.directory` reads. That shape is not a convenience for the harness: it is what a real
// deployment looks like, so the dev run exercises the same code path a shipped jar will, rather than a
// second one that only ever works here.
//
// This is what M7a needed and nothing had: the engineBand configurations are resolvable and consumed by
// NOTHING, which is correct for the compile classpath and left the running application with no engine to
// open. `EngineSource.NONE` is a real deployment, so the absence produced no error anywhere -- the
// editor simply had no diagnostics and no Run command, which reads as the feature not existing.
val stageEngines = tasks.register<Sync>("stageEngines") {
    group = "harness"
    description = "Copies each engine band's jars into build/engines/<band>/ for a dev run."
    into(layout.buildDirectory.dir("engines"))
    into("8") { from(engineBand8) }
    into("11") { from(engineBand11) }
    into("17") { from(engineBand17) }
}

/** Where {@code stageEngines} puts them — read by the harness's run task. */
val stagedEnginesDir: Provider<Directory> = layout.buildDirectory.dir("engines")

// Exposed so another project can depend on the staged layout without reaching into this one's tasks.
val engineStage: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(engineStage.name, stagedEnginesDir) { builtBy(stageEngines) }
}

tasks.register("smokeEngineBands") {
    group = "verification"
    description = "Runs every engine band under a JVM of its own era."
    dependsOn(smokeBand8, smokeBand11, smokeBand17)
}

// Part of `check`, not a thing to remember. The failure it catches -- a jar that cannot load on its own
// band's JVM -- is invisible on the machine that introduces it, so it has to run without being asked.
tasks.named("check") { dependsOn("checkEngineBands") }

tasks.register("checkEngineBands") {
    group = "verification"
    description = "Fails if any jar in an engine band cannot load on that band's JVM."
    val bands = mapOf("8" to engineBand8, "11" to engineBand11, "17" to engineBand17)
    val resolved = bands.mapValues { (_, configuration) -> configuration.resolve().toList() }
    doLast {
        val offences = mutableListOf<String>()
        resolved.forEach { (band, files) ->
            val ceiling = bandCeiling.getValue(band)
            files.forEach { jar ->
                val major = baseClassMajor(jar)
                if (major > ceiling) {
                    offences += "band $band: ${jar.name} is class major $major, above the band's $ceiling"
                }
            }
        }


        resolved.forEach { (band, files) -> offences += signerConflicts(band, files) }

        if (offences.isNotEmpty()) {
            throw GradleException("Engine band jars that cannot be loaded together:\n  " +
                    offences.joinToString("\n  "))
        }
        println("engine bands OK: " + resolved.entries.joinToString(", ") {
            "${it.key} (${it.value.size} jars)" })
    }
}

tasks.test {
    useJUnit()

    // THE CORPUS PASS RUNS EVERY TIME. It was planned as opt-in on the assumption that running every quick
    // fix over every .java file in the repository, applying each and re-parsing, would be minutes; it was
    // measured at twelve seconds for 652 files and 942 applied actions, which is not enough to gate the
    // one test that finds what nobody wrote a fixture for. `-PnoCorpus` skips it for a quick local loop.
    // See CorpusTest.
    systemProperty("cgui.test.corpus", if (project.hasProperty("noCorpus")) "false" else "true")
    // The repository root, so the corpus can find the other modules' sources from inside this one.
    systemProperty("cgui.test.repoRoot", rootProject.projectDir.absolutePath)
    // `-Pcoverage` prints what the engine reports and cannot answer, over the same corpus. An instrument
    // rather than a check -- it asserts nothing and its output is only useful to a person deciding what to
    // build next -- so unlike the corpus it stays off by default. See CoverageProbeTest.
    systemProperty("cgui.test.coverage", project.hasProperty("coverage").toString())
    // AND THE CLASSPATH THOSE SOURCES ACTUALLY COMPILE AGAINST. The probe's second run exists to separate
    // a real gap from an unresolvable-classpath artefact, and it can only do that if the classpath it uses
    // is the real one -- with only the two modules' own classes, JOML, Taffy, log4j and Gson are all
    // missing, so half the file still fails to resolve and the "resolved" run is measuring the same
    // artefacts as the empty one. See CoverageProbeTest.
    // Resolved ONLY under -Pcoverage: asking for a runtime classpath is configuration-time work, and every
    // ordinary build would pay it to hand a property to a test that immediately skips.
    if (project.hasProperty("coverage")) {
        systemProperty("cgui.test.repoClasspath", sourceSets["main"].runtimeClasspath.asPath)
    }

    // THE BAND JARS REACH THE TESTS AS PATHS, NOT AS A DEPENDENCY. Putting them on testRuntimeClasspath
    // would let a test resolve an engine class through the ordinary loader, which is exactly what the
    // isolation exists to prevent -- and the test would then pass against a setup that cannot occur in
    // production. Handing over a path list keeps the only route into an engine the one the application
    // uses: EngineSource, then EngineClassLoader.
    //
    // Resolved lazily inside a provider so a `gradlew :language:test --offline` with a warm cache does
    // not force a repository lookup at configuration time.
    systemProperty("cgui.test.engineBand8", provider { engineBand8.asPath }.get())
    systemProperty("cgui.test.engineBand11", provider { engineBand11.asPath }.get())
    systemProperty("cgui.test.engineBand17", provider { engineBand17.asPath }.get())

    // THE STAGED DIRECTORY, so a test can exercise the route an application actually takes.
    // `cgui.test.engineBand*` above is a path list, which is convenient and is NOT how anything ships;
    // `JavaLanguage.defaultSource()` reads this property and `EngineSource.directory` reads the layout.
    // Without it the registration test would prove the API works while the deployment shape went
    // unexercised — which is exactly the gap M7a exists to close.
    dependsOn(stageEngines)
    systemProperty("crystalgui.engines.dir", stagedEnginesDir.get().asFile.absolutePath)

    // The pins, so a test can hold EngineBand to what the build actually resolves. Two copies of a
    // version number is a real hazard: bump one and the build downloads 3.46.0 while the runtime asks
    // for 3.45.0, which fails on the one platform whoever bumped it does not have.
    systemProperty("cgui.test.pins", listOf(
            "8.jdt=$jdtBand8", "8.rhino=$rhinoBand8",
            "11.jdt=$jdtBand11", "11.rhino=$rhinoModern",
            "17.jdt=$jdtBand17", "17.rhino=$rhinoModern").joinToString(","))
    // Native loading is the one thing that cannot be asserted without the platform it runs on. A failure
    // here is reported rather than swallowed, but the tests themselves skip cleanly when the library will
    // not load -- see TreeSitterTokenizerTest.
    testLogging {
        showStandardStreams = true
    }
}
