import java.security.MessageDigest
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("cg-java17")
    id("xyz.wagyourtail.jvmdowngrader")
}

// :core emits Java 21 bytecode (v65) and MC 1.20.1 ships a Java 17 runtime, so the bundled classes are
// rewritten to 17 -- the same mechanism mc1710 uses to reach Java 8. Compiling against v65 needs only a
// 21 toolchain (see cg-java17); LOADING it on a player's JVM needs this.
jvmdg.downgradeTo.set(JavaVersion.VERSION_17)

// LWJGL 3.3.1 -- what MC 1.20.1 ships -- predates Java 21 and does not recognise its JNI version. It
// patches the JNIEnv function table on a guessed layout anyway; under a debugger's JVMTI agent that
// table is instrumented, so the write lands past it and the process dies with a native fail-fast
// (0xC0000409 on Windows) before the window opens.
//
// A dev run here is ALWAYS on Java 21: cg-java17 raises the toolchain to 21 so javac can read :core's
// v65 classes, and ModDevGradle takes the run JVM from the toolchain. So the client runs fine and
// cannot be debugged -- which reads as an IDE fault rather than a library one.
//
// 3.3.3 knows the version and uses the right layout. Dev runs only; nothing shipped resolves LWJGL.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("LWJGL 3.3.1 corrupts the JNIEnv table on Java 21 under a debugger")
        }
    }
}


repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":core"))

    // Taffy and JOML: :core has them compileOnly so they reach nobody transitively, and UIElement holds
    // a NodeId and a Matrix4f as fields. Needed at RUNTIME too -- a field descriptor resolves at class
    // load, so without them the UI classes do not load at all. plan/platform-mc1201.md 4.3.
    "compileOnly"(project(":taffy"))
    "runtimeOnly"(project(":taffy"))

    // :language -- the grammars, ECJ and Rhino, plus the ScriptService seam. Bundled rather than
    // omitted: the editor still opens every file without it, but it colours from core's word-list
    // lexers and does not analyse, which is not the degradation somebody installing a code editor
    // wants. plan/platform-mc1201.md 4.3.
    "compileOnly"(project(":language"))
    // AND ON THE RUNTIME CLASSPATH, like :core and :mc1201:common above. compileOnly alone put it on
    // no run at all: `Lifecycle1201.bootstrapClient` calls `ScriptService1201.install()`, so the first
    // dev client to reach it died with
    //
    //     NoClassDefFoundError: com/crystalgui/language/platform/ScriptService
    //
    // The shipped jar was never affected -- shadowJar bundles :language from `cgBundledProjects` -- so
    // this is a dev-run-only hole, and the one loader that shows it first is whichever runs the seam.
    // Same excludes as the two above, for the same NeoForge strict-version reason.
    "runtimeOnly"(project(":language")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }

    // Minecraft supplies log4j and gson. :core pins modern ones runtimeOnly for its tests and the
    // harness, and those reach a loader -- where NeoForge requires {strictly 2.19.0}/{strictly 2.10.1}
    // and the conflict fails its entire runtime graph.
    //
    // Per-dependency, never on the configuration: excluding the groups from runtimeClasspath would
    // take Minecraft's own log4j with it, which Loom's dev run reads.
    "runtimeOnly"(project(":mc1201:common")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }
    "runtimeOnly"(project(":core")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${property("mc1201.mixin")}")
    "annotationProcessor"("org.spongepowered:mixin:${property("mc1201.mixin")}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")
}

// Shared shadow JAR bundling: bundles :core and :mc1201:common into shadowJar.
cgbuildlogic.configureShadowJarBundling(project)

// ── The thin jar (J1) ────────────────────────────────────────────────────────────────────────────
//
// One input to the single-jar merge: this loader's own classes and resources, plus :mc1201:common,
// and NOTHING else. The engine, the language stack, Taffy, the bands and tree-sitter enter the merge
// once at the root; a copy here would ship four times over.
//
// `common` has to be relocated because the single jar carries THREE remapped copies of it -- SRG on
// Forge, official on NeoForge, intermediary on Fabric -- and three classes cannot share a name.
//
// THE FOUR PACKAGES ARE MOVED INDIVIDUALLY, never their parent: relocating `com.crystalgui.mc` would
// rewrite this loader's own `com.crystalgui.mc.<loader>` too, into `...<loader>.common.<loader>`.
// Each keeps its leaf name under the new root rather than being flattened into it, so a class that
// was `mc.client.CgUiScreen1201` becomes `mc.forge.common.client.CgUiScreen1201` and stays unique.
val cgCommonPackages = listOf("client", "net", "platform", "example")
val cgThinRoot = "com.crystalgui.mc.${project.name}.common"

val thinShadowJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("thinShadowJar") {
    group = "build"
    description = "This loader plus :mc1201:common, relocated -- the merge's input, before remapping."
    // DEV NAMES STILL. Forge reobfuscates this, Fabric remaps it, NeoForge ships it as it is; the
    // classifier says so, so a `-thin-dev` jar is never mistaken for something installable.
    archiveClassifier.set("thin-dev")
    configurations = emptyList()
    from(sourceSets["main"].output)
    val commonJar = project(":mc1201:common").tasks.named<Jar>("jar")
    dependsOn(commonJar)
    from(commonJar.map { zipTree(it.archiveFile) })
    cgCommonPackages.forEach { relocate("com.crystalgui.mc.$it", "$cgThinRoot.$it") }
}

// Nothing in :mc1201:common may be NAMED from a descriptor or a service file.
//
// The relocation rewrites class references inside the jar; it cannot rewrite a name sitting in
// `mods.toml`, `fabric.mod.json` or `META-INF/services/...`, so such a name would point at a class
// that no longer exists under that spelling -- on three loaders, silently, at the moment something
// asks for it. The loader's OWN packages are fine: they are not relocated.
val checkDescriptorsNameNoCommon = tasks.register("checkDescriptorsNameNoCommon") {
    group = "verification"
    description = "Fails if a descriptor or service file names a class that the thin jar relocates."
    val resourceRoot = layout.projectDirectory.dir("src/main/resources").asFile
    val forbidden = cgCommonPackages.map { "com.crystalgui.mc.$it" }
    inputs.dir(resourceRoot).optional(true).withPropertyName("resources")
    outputs.upToDateWhen { true }
    doLast {
        if (!resourceRoot.isDirectory) return@doLast
        val hits = resourceRoot.walkTopDown()
            .filter { it.isFile }
            .flatMap { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                forbidden.filter { text.contains(it) }.map { file.relativeTo(resourceRoot) to it }
            }
            .toList()
        if (hits.isNotEmpty()) {
            throw GradleException(
                "A descriptor or service file names a package the thin jar relocates, so the name "
                    + "will be wrong on every loader:\n"
                    + hits.joinToString("\n") { (path, pkg) -> "  $path  names  $pkg" })
        }
    }
}
tasks.named("check") { dependsOn(checkDescriptorsNameNoCommon) }

// A dev run must BUILD what mods{} makes visible.
//
// `mods { sourceSet(project(":core")...) }` writes the source set's output DIRECTORY into
// -Dfml.modFolders and does nothing else -- it adds no task dependency, and neither compileOnly nor
// runtimeOnly adds one ModDevGradle honours. Verified: with this block removed, neither :core:classes
// nor :mc1201:common:classes appears in `prepareClientRun --dry-run`.
//
// prepareClientRun is the task the IDE runs before launching, so an IDE launch pointed at a directory
// nothing had compiled into. The symptom is a NoClassDefFoundError for a class that plainly exists on
// disk, at a call site that plainly compiles:
//
//     NoClassDefFoundError: com/crystalgui/mc/platform/Lifecycle1201
//         at com.crystalgui.mc.forge.CrystalGUI1201Forge.<init>
//
// which reads as a packaging or classloader fault rather than as a missing build step.
tasks.matching { it.name.startsWith("run") || it.name.startsWith("prepare") }.configureEach {
    dependsOn(":core:classes", ":mc1201:common:classes", ":language:classes")
}

// The engine band, for a DEV run only.
//
// EngineHost tries a configured directory, then a band bundled in the jar, then a download. A dev run
// has no jar to bundle into, so without this it reaches the download arm and finds no manifest either:
// "no engine jars for band JAVA_17 ... the editor will colour but not analyse". Pointing the first arm
// at :language's own staged output is what mc1710 does, and it keeps the network out of a dev launch.
tasks.withType<JavaExec>().matching { it.name.startsWith("run") }.configureEach {
    dependsOn(":language:stageEngines")
    systemProperty("crystalgui.engines.dir",
        project(":language").layout.buildDirectory.dir("engines").get().asFile.absolutePath)
}


// -- The engine bands, in the shipped jar (L6) --------------------------------------------------
//
// A dev run gets its band from -Dcrystalgui.engines.dir above. A SHIPPED jar has no such property, so
// without this it carries no bands at all and the editor colours without analysing -- a legitimate
// degradation, and not the one somebody installing a code editor wants.
//
// Ported from mc1710 with ONE difference: it defaults to band 17 where mc1710 defaults to 8, because
// that is what this Minecraft runs. EngineBand reads java.specification.version, so a host selecting a
// band this jar does not carry falls through to the manifest and fetches it.
// -- What the shipped jar and the dev run BOTH need ----------------------------------------------
//
// These lived in `crystalgraphics-run.gradle.kts`, which is ModDevGradle's dev-run integration and is
// applied by forge and neoforge only. `ShadowUtils` needs the merged services for the SHIPPED jar, so
// fabric -- which uses Loom and applies no such script -- could not configure its own shadowJar at all:
//
//     Could not create task ':mc1201:fabric:shadowJar'
//       > Task with name 'mergeDevServices' not found in project ':mc1201:fabric'
//
// A jar every loader builds cannot depend on a script two of them apply.

fun cgMainSourceSet(target: Project) =
    target.extensions.getByType(SourceSetContainer::class.java)["main"]

/**
 * The projects bundled INTO the crystalgui mod, named once.
 *
 * Three places need this list -- the service merge, the dev run's resource staging and MOD_CLASSES --
 * and it was written out in each. The loader project itself is NOT here: its resources carry
 * META-INF/mods.toml and must stay their own root.
 */
val cgBundledProjects = listOf(project(":core"), project(":mc1201:common"), project(":language"))

/** Where each of those keeps META-INF/services, for the merge and for its up-to-date check. */
val cgServiceDirs: List<File> = cgBundledProjects.mapNotNull { owner ->
    cgMainSourceSet(owner).output.resourcesDir?.let { File(it, "META-INF/services") }
}

/**
 * ONE merged META-INF/services root, for both consumers.
 *
 * :core and :language each ship a WorkbenchExtension service file, and a copy keeps whichever arrived
 * first while silently DROPPING the other -- with :core first the jar carried its eight extensions and
 * lost the language Run panel. ShadowJar's own mergeServiceFiles does not help: these arrive through
 * from(zipTree(...)) and the duplicate is dropped by the copy before any transformer sees it.
 */
val cgMergedServicesDir: File = layout.buildDirectory.dir("merged-services").get().asFile

tasks.register("mergeDevServices") {
    group = "build"
    description = "Unions :core's and :language's META-INF/services so neither shadows the other."
    // THE FILES IT READS, or it is compared on its outputs alone and stays UP-TO-DATE across an edit to
    // one of them -- leaving a merged copy that describes the previous build.
    inputs.files(cgServiceDirs).withPropertyName("serviceDirs").optional()
    outputs.dir(cgMergedServicesDir)
    // ORDERING, which declaring the inputs does not give: without it this can run before the resources
    // are copied and union an empty directory.
    dependsOn(cgBundledProjects.map { "${it.path}:processResources" })
    doLast {
        val sources = cgServiceDirs.filter { it.isDirectory }
        val byService = linkedMapOf<String, MutableList<String>>()
        for (directory in sources) {
            for (file in directory.listFiles().orEmpty()) {
                val providers = byService.getOrPut(file.name) { mutableListOf() }
                file.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() && it !in providers }
                    .forEach { providers.add(it) }
            }
        }
        val out = File(cgMergedServicesDir, "META-INF/services")
        out.mkdirs()
        out.listFiles().orEmpty().forEach { it.delete() }
        byService.forEach { (service, providers) ->
            File(out, service).writeText(buildString {
                appendLine("# Merged for the jar and the dev run by mergeDevServices; see its declaration.")
                providers.forEach { appendLine(it) }
            })
        }
    }
}

// Read by crystalgraphics-run.gradle.kts, which stages the dev run's resources from the same list.
extra["cgBundledProjects"] = cgBundledProjects
extra["cgMergedServicesDir"] = cgMergedServicesDir

/**
 * Third-party libraries this jar carries -- EMPTY on 1.20.x, and that is the whole point.
 *
 * A library is bundled only where the platform does not already have it, and 1.20.x has both of the
 * ones this engine needs, at the versions we pin: `fastutil 8.5.12` and `joml 1.10.8` are Minecraft's
 * own libraries. Shipping them again is not merely redundant, it is FATAL -- two modules exporting
 * `it.unimi.dsi.fastutil.ints` fails module resolution before a single mod class loads:
 *
 *     Modules it.unimi.dsi.fastutil and crystalgui export package it.unimi.dsi.fastutil.ints
 *     to module minecraft
 *
 * A dev run cannot show it. There the classes come off a source-set directory rather than a jar, so
 * nothing declares a second module and the layer resolves.
 *
 * The seam stays because the answer is per platform, not universal. 1.7.10 has neither library, and
 * mc1710 accordingly ships both -- with the two treated DIFFERENTLY, which any new target must copy:
 *
 *  - **fastutil is CrystalGUI's and is RELOCATED** (`com.crystalgui.shadow.it.unimi.dsi.fastutil`).
 *    Taffy needs it, nothing outside this jar sees those types, and a stock copy in another mod must
 *    not win a classloader race.
 *  - **JOML is CrystalGraphics' and is NOT relocated.** Its types cross the boundary between the two
 *    mods -- `UINode` and `ElementStyle` hold `Matrix4f` FIELDS, and `Quad.pose` takes one -- so
 *    relocating it in one jar and not the other makes two unrelated types with the same name.
 *    CrystalGUI bundles no JOML at all on 1.7.10 and uses CrystalGraphics' copy.
 *
 * Declared by coordinate rather than resolved off :core or :taffy: those declare JOML and Taffy
 * `compileOnly` so they reach nobody transitively, and reading another project's compileClasspath at
 * configuration time reaches across the composite build and fails outright.
 */
val shippedLibs: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }

val engineBand8: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
val engineBand11: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
val engineBand17: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }

dependencies {
    // Nothing in shippedLibs: Minecraft 1.20.x provides joml and fastutil itself. @see shippedLibs
    add("engineBand8", project(path = ":language", configuration = "engineBand8Bundle"))
    add("engineBand11", project(path = ":language", configuration = "engineBand11Bundle"))
    add("engineBand17", project(path = ":language", configuration = "engineBand17Bundle"))
}

/** Which bands this jar CARRIES. `-PcgBundleBands=17`, `8,17`, or `none`. */
val bundledBands: List<Int> = providers.gradleProperty("cgBundleBands").orNull
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "none" }?.map { it.toInt() }
    ?: listOf(17)

fun configurationForBand(band: Int): Configuration = when (band) {
    8 -> engineBand8
    11 -> engineBand11
    17 -> engineBand17
    else -> throw GradleException("unknown engine band $band; known bands are 8, 11 and 17")
}

val bundleEngineBands = tasks.register<Sync>("bundleEngineBands") {
    group = "build"
    description = "Lays the selected bands' jars out as jar resources, with the index EngineBundle reads."
    // The Sync's OWN destination is the bundle root and each band is a path INSIDE it: syncing straight
    // into a band directory makes THAT the task output, so from(...) copies its contents and the
    // assets/ prefix is silently gone.
    into(layout.buildDirectory.dir("engine-bundle"))
    for (band in bundledBands) {
        into("assets/crystalgui/engines/$band") { from(configurationForBand(band)) }
    }
    doLast {
        for (band in bundledBands) {
            val directory = layout.buildDirectory
                .dir("engine-bundle/assets/crystalgui/engines/$band").get().asFile
            // SORTED: Sync copies in whatever order the filesystem reports, and two jars declaring one
            // package would otherwise resolve differently per build host.
            val jars = directory.listFiles()?.filter { it.name.endsWith(".jar") }?.map { it.name }?.sorted()
                ?: emptyList()
            directory.resolve("index.txt").writeText(buildString {
                appendLine("# Band $band engine jars, in classpath order. Written by bundleEngineBands.")
                jars.forEach { appendLine(it) }
            })
        }
    }
}

/**
 * One manifest per band: name, digest and where to fetch it -- what a host whose band is not bundled
 * reads. A SEPARATE output tree from the bundle's, because that one is a Sync and a Sync deletes
 * whatever is not in its source.
 *
 * The digest is computed from the file Gradle resolved rather than read from Maven's `.sha1`: it pins
 * the exact bytes this build was tested against, needs no network, and verifies offline. MD5 because
 * CacheFiles computes MD5 -- a corruption-and-drift check, never a security boundary.
 */
fun manifestFor(band: Int, configuration: Configuration) {
    val directory = layout.buildDirectory
        .dir("engine-manifests/assets/crystalgui/engines/$band").get().asFile
    directory.mkdirs()
    val rows = configuration.resolvedConfiguration.resolvedArtifacts.map { artifact ->
        val id = artifact.moduleVersion.id
        val path = id.group.replace('.', '/') + "/" + id.name + "/" + id.version
        val digest = MessageDigest.getInstance("MD5")
            .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
        artifact.file.name + "|" + digest + "|https://repo1.maven.org/maven2/" + path + "/" + artifact.file.name
    }.sorted()
    directory.resolve("manifest.txt").writeText(buildString {
        appendLine("# Band $band engine jars: name|md5|url. Written by writeEngineManifests.")
        rows.forEach { appendLine(it) }
    })
}

val writeEngineManifests = tasks.register("writeEngineManifests") {
    group = "build"
    description = "Writes one name|md5|url manifest per engine band, for bands the jar does not carry."
    outputs.dir(layout.buildDirectory.dir("engine-manifests"))
    doLast {
        manifestFor(8, engineBand8)
        manifestFor(11, engineBand11)
        manifestFor(17, engineBand17)
    }
}

/**
 * Fails the build if a bundled band's jars and its manifest disagree.
 *
 * They are written by different tasks from the same configuration, so they can drift -- and nothing at
 * runtime would notice, because a bundled band is used as-is and the manifest is read only when a band
 * is MISSING. The mismatch would surface as a download that always fails its digest on somebody else's
 * machine, which is about as far from the cause as a symptom gets.
 */
val checkEngineManifest = tasks.register("checkEngineManifest") {
    group = "verification"
    description = "Fails if any bundled band's jars and its manifest disagree."
    dependsOn(bundleEngineBands, writeEngineManifests)
    doLast {
        for (band in bundledBands) {
            val bundled = layout.buildDirectory
                .dir("engine-bundle/assets/crystalgui/engines/$band").get().asFile
            val manifest = layout.buildDirectory
                .file("engine-manifests/assets/crystalgui/engines/$band/manifest.txt").get().asFile
            if (!manifest.isFile) throw GradleException("band $band has no manifest; run writeEngineManifests")
            val declared = manifest.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { row -> row.split("|").let { it[0] to it[1] } }
            val present = (bundled.listFiles() ?: emptyArray()).filter { it.name.endsWith(".jar") }
                .associate { jar ->
                    jar.name to MessageDigest.getInstance("MD5")
                        .digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
                }
            val missing = present.keys - declared.keys
            val extra = declared.keys - present.keys
            val wrong = present.filter { (name, digest) -> declared[name]?.equals(digest) == false }.keys
            if (missing.isNotEmpty() || extra.isNotEmpty() || wrong.isNotEmpty()) {
                throw GradleException(
                    "band $band's manifest does not describe its bundled jars." + "\n"
                        + "  bundled but not declared: $missing" + "\n"
                        + "  declared but not bundled: $extra" + "\n"
                        + "  declared with a stale digest: $wrong")
            }
        }
    }
}

tasks.named("check") { dependsOn(checkEngineManifest) }

// The shipping jar, with every bundled class at Java 17.
//
// Downgrading the SHADOW jar rather than :core's own covers taffy and mc1201:common in one pass and
// needs one classpath. DowngradeJar.classpath must be set explicitly: it defaults to this module's
// compileClasspath, and jvmdg walks supertypes to decide what needs a stub -- an incomplete classpath
// is 1,800 lines of "Could not find class" and silently no stubs.
val downgradeShadowJar = tasks.register<DowngradeJar>("downgradeShadowJar") {
    group = "build"
    description = "Rewrites the shadow JAR's classes to Java 17."
    val shadow = tasks.named<org.gradle.api.tasks.bundling.Jar>("shadowJar")
    dependsOn(shadow)
    inputFile.set(shadow.flatMap { it.archiveFile })
    classpath = configurations.getByName("compileClasspath")
    archiveClassifier.set("java17")
}

/**
 * The jvmdg RUNTIME the downgrade just made this jar depend on.
 *
 * Downgrading does not only lower the class-file version: where the source used a Java 21 construct
 * with no Java 17 spelling, jvmdg rewrites the reference to a STUB of its own. A pattern-matching
 * switch in `TaffyBridge` became a reference to
 * `xyz.wagyourtail.jvmdg.j21.stub.java_base.J_L_MatchException`, and that class ships in jvmdg's API
 * jar, which nothing was bundling:
 *
 *     NoClassDefFoundError: xyz/wagyourtail/jvmdg/j21/stub/java_base/J_L_MatchException
 *         at com.crystalgui.style.TaffyBridge.<clinit>
 *
 * It fails at the first widget constructed, which on this loader is mod construction itself. A dev run
 * never downgrades at all, so nothing there can show it. mc1710 has always shaded this; 1.20.x was
 * downgrading and not shading, which is the half-measure that produces a jar that loads and then dies.
 */
val shadeDowngradedShadowJar = tasks.register<ShadeJar>("shadeDowngradedShadowJar") {
    group = "build"
    description = "Bundles jvmdg's runtime stubs that downgradeShadowJar's output now references."
    inputFile.set(downgradeShadowJar.flatMap { it.archiveFile })
    // NAMED, never defaulted: the default is the archive base name, and `crystalgui-mc1201-forge`
    // has hyphens in it -- not a legal package identifier, so the module system rejects the jar and
    // the launch dies before the early display with nothing in any log. Same prefix taffy relocates
    // under, for one shaded namespace rather than two.
    shadePath.set({ _: String -> "com/crystalgui/shadow" })
    archiveClassifier.set("java17-shaded")
}

tasks.named("assemble") { dependsOn(shadeDowngradedShadowJar) }

/**
 * A server run task's game directory.
 *
 * ModDevGradle exposes `gameDirectory`. Loom exposes nothing usable: it sets `workingDir` too late for a
 * doFirst to read, so `workingDir` is still the PROJECT directory there -- which is where an earlier
 * version wrote eula.txt and server.properties while the server read `runs/server/eula.txt` and quit
 * with "You need to agree to the EULA", having ignored both files.
 *
 * So the fallback is the CONVENTION all three loaders declare rather than a guess: forge and neoforge
 * set gameDirectory to runs/server, fabric sets runDir to the same. If one ever diverges the EULA guard
 * names the path it looked at, so it fails visibly rather than writing into the void.
 */
fun gameDirOf(task: JavaExec): File = runCatching {
    (task.javaClass.getMethod("getGameDirectory").invoke(task) as DirectoryProperty).get().asFile
}.getOrNull() ?: task.project.file("runs/server")

// ── Dedicated-server smoke ────────────────────────────────────────────────────────────────────────
//
// Wired to `runServer` rather than being its own JavaExec: reproducing that task's classpath, JVM args
// and working directory is a copy that goes stale, and the run has to be the real one or it proves
// nothing. The task name is read out of the start parameters because a Gradle PROPERTY cannot be set by
// a task dependency -- so `serverSmoke` and `runServer -PcgServerSmoke` are the same run, spelled twice.
val serverSmokeRequested = gradle.startParameter.taskNames.any {
    it == "serverSmoke" || it.endsWith(":serverSmoke")
}
val serverSmokeReport = layout.buildDirectory.file("serverSmoke/result.txt")
val classLoadLog = layout.buildDirectory.file("serverSmoke/classload.log")
val acceptEula = providers.gradleProperty("cgAcceptEula").isPresent
val smokePort = providers.gradleProperty("cgSmokePort").orNull ?: "25599"

// withType, not named: this plugin is applied BEFORE the loader plugin that creates runServer.
tasks.withType<JavaExec>().matching { it.name == "runServer" }.configureEach {
    if (!serverSmokeRequested && !providers.gradleProperty("cgServerSmoke").isPresent) return@configureEach

    val exec = this

    systemProperty("crystalgui.server.smoke", "true")
    systemProperty("crystalgui.server.smoke.report", serverSmokeReport.get().asFile.absolutePath)

    // The dedicated server otherwise opens a Swing console; a check that needs a window closed is not a
    // check a pipeline can run. A PROVIDER, not args(): ModDevGradle passes the real program arguments
    // through one (the @argfile whose first line is the main class), and JavaExec emits getArgs() BEFORE
    // providers -- so args("nogui") became argv[0] and devlaunch read it as the main class to run.
    argumentProviders.add(CommandLineArgumentProvider { listOf("nogui") })

    // ASKING THE JVM, because reflection cannot ask here. findLoadedClass is protected, and the mod runs
    // in FML's named module `crystalgui` -- so --add-opens ...=ALL-UNNAMED cannot reach it, and the module
    // does not exist at JVM start for a static one to name. Left reflective, the "no client-only class
    // loaded" assertion cannot run and passes VACUOUSLY, which is the failure its own javadoc warns about.
    // -Xlog needs no access to anything and is the JVM's own record of every class it defined.
    jvmArgs("-Xlog:class+load=info:file=" + classLoadLog.get().asFile.absolutePath)
    systemProperty("crystalgui.server.smoke.classlog", classLoadLog.get().asFile.absolutePath)

    // The code source is a union: URL under FML, so the client package cannot be enumerated from it.
    // The build knows where those classes are, so it says so.
    systemProperty("crystalgui.server.smoke.classdir",
            project(":mc1201:common").extensions.getByType<SourceSetContainer>()["main"]
                    .output.classesDirs.asPath)

    doFirst {
        // Deleted BEFORE the run, so a stale report from a previous run cannot pass for this one.
        serverSmokeReport.get().asFile.delete()
        classLoadLog.get().asFile.also { it.parentFile.mkdirs(); it.delete() }

        val runDir = gameDirOf(exec)
        runDir.mkdirs()

        // A PORT OF ITS OWN. On 1.7.10 this check's first run caught it about itself: 25565 was in use,
        // the bind failed, the started event never fired so not one assertion ran, and the JVM exited 0
        // -- BUILD SUCCESSFUL having checked nothing. 1.20.x parses no --port, so it goes in the file.
        val properties = File(runDir, "server.properties")
        val lines = if (properties.isFile) properties.readLines() else emptyList()
        properties.writeText(
            (lines.filterNot { it.startsWith("server-port=") } + "server-port=$smokePort")
                .joinToString(System.lineSeparator(), postfix = System.lineSeparator()))

        // Accepting Mojang's EULA is the developer's to do, not the build's. Detected rather than
        // written, so the task never agrees to a licence on someone's behalf.
        val eula = File(runDir, "eula.txt")
        val accepted = eula.isFile && eula.readLines().any { it.replace(" ", "") == "eula=true" }
        if (!accepted) {
            if (!acceptEula) {
                throw GradleException(
                    "The dedicated server needs Mojang's EULA accepted before it will start.\n" +
                        "Re-run with -PcgAcceptEula to write eula=true to " + eula.absolutePath + ",\n" +
                        "or write it yourself. https://aka.ms/MinecraftEULA")
            }
            eula.writeText("eula=true" + System.lineSeparator())
        }
    }
}

tasks.register("serverSmoke") {
    group = "crystalgui"
    description = "Boots a dedicated server, asserts the server-side stack came up, and stops it."
    dependsOn(tasks.named("runServer"))
    outputs.upToDateWhen { false }

    // THE HALF THAT MAKES IT SOUND. The mod halts(1) when a check fails, which covers "ran and failed";
    // nothing covers "never ran", and on 1.7.10 that is the case that actually happened first. An absent
    // report is a failure with a message, never a pass.
    doLast {
        val file = serverSmokeReport.get().asFile
        if (!file.isFile) {
            throw GradleException(
                "The dedicated server produced no smoke report at " + file.absolutePath + ".\n" +
                    "The server never reached its started event, so NO check ran -- this is not a pass.\n" +
                    "Look above for the reason: a failed port bind, a mod refusing to load, or a crash\n" +
                    "during startup. Use -PcgSmokePort=<n> if " + smokePort + " is taken.")
        }
        if (file.readLines().firstOrNull()?.trim() != "PASS") {
            throw GradleException("Dedicated-server smoke FAILED:" + System.lineSeparator() + file.readText())
        }
        logger.lifecycle(file.readText())
    }
}

// Diagnostics reach the GAME's JVM, not Gradle's. A -D on the Gradle command line sets a property on
// the daemon and the run never sees it, which reads as a flag that does nothing -- so the ones worth
// turning on from a command line are forwarded explicitly.
//
//   ./gradlew :mc1201:forge:runClient -Dcrystalgui.layer.probe=true
val cgForwardedProperties = listOf(
    "crystalgui.layer.probe", "crystalgui.editor.trace", "crystalgui.clientProbe",
    "crystalgui.glass.probe", "crystalgui.language.noLiveBytes")
tasks.withType<JavaExec>().matching { it.name.startsWith("run") }.configureEach {
    cgForwardedProperties.forEach { key ->
        val value = providers.systemProperty(key).orNull
        if (value != null) systemProperty(key, value)
    }
}

// The scripted client run joins a world by itself:
//
//   ./gradlew :mc1201:forge:runClient -Dcrystalgui.clientProbe=true [-PcgWorld="Some World"]
//
// ClientProbe1201 can only start once there is a world, and nothing in a dev run reaches the title
// screen on its own. A missing world is not reported usefully -- the client just sits on the menu --
// so the name is a property and the run logs which one it asked for.
val cgProbeWorld = (project.findProperty("cgWorld") as String?) ?: "New World"
tasks.matching { it.name == "runClient" }.configureEach {
    if (providers.systemProperty("crystalgui.clientProbe").orNull != null) {
        (this as JavaExec).argumentProviders.add(CommandLineArgumentProvider {
            logger.lifecycle("[cgui] client probe: auto-joining '{}'", cgProbeWorld)
            listOf("--quickPlaySingleplayer", cgProbeWorld)
        })
    }
}