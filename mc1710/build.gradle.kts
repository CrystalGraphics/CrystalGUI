import java.security.MessageDigest
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library`
}

// Resolvable, and on no compile or run classpath -- see the `downgradeJar` block below for why that
// matters and why these jars may never be beside the application.
val engineApi: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/** Band 8's jars — bundled by default. @see bundleEngineBands */
val engineBand8: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/**
 * Bands 11 and 17, resolved for their MANIFESTS and never copied into the jar.
 *
 * A 1.7.10 client on Java 17 -- which lwjgl3ify and GTNH make an ordinary configuration -- selects band
 * 17, finds nothing bundled, and has no analysis at all. Shipping all three bands is 41 MB; shipping a
 * manifest per band is a few hundred bytes and lets the client fetch the one it needs.
 */
val engineBand11: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val engineBand17: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/**
 * Mixin's LaunchWrapper tweaker, for the OBFUSCATED run only. @see stageObfMods
 *
 * Non-transitive: this stages a mods folder, and pulling unimixins' own dependencies into it would put
 * libraries beside mods where FML expects only mods.
 */
val obfMixinBootstrap: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgui").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

// ASK shadowImplementation FOR JARS.
//
// It carries RetroFuturaGradle's obfuscation attributes, and a PROJECT dependency publishes several
// variants (classes, resources, the jar) where a Maven artifact publishes one -- so the moment :taffy
// stopped being `dev.vfyjxf:taffy` and became a module of ours, resolution became ambiguous and
// shadowJar failed before it started: "we cannot choose between the following variants of project
// :taffy". Naming the element type is the whole fix; the RFG attributes in that error are unmatched
// on every variant equally and are not what the resolver is stuck on.
configurations.named("shadowImplementation") {
    attributes {
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }
}

// ...and say who PRODUCES that jar. Asking for the JAR element type above makes shadowJar read
// `taffy/build/libs/taffy.jar` directly, and Gradle cannot infer the producing task through an
// attribute override -- it fails the build outright rather than racing, which is the good outcome and
// still needs answering. Solution 2 of the three Gradle offers, because it is the one that states the
// relationship rather than merely ordering it.
tasks.named("shadowJar") { dependsOn(":taffy:jar") }
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
//
// compileOnly, and it MUST NOT be api()/implementation(). :core compiles to Java 21 bytecode (its Jabel
// annotationProcessor is commented out, so nothing desugars it), and a dev run puts every runtime
// dependency on LaunchWrapper's classpath, where FML's ModDiscoverer opens each jar with
// asm-debug-all-5.0.3 looking for @Mod. ASM 5.0.3 tops out at Java 8 class files, so it fails on the
// first entry with:
//
//     There was a problem reading the entry com/crystalgui/core/CrystalGuiCore.class in the jar
//     .../core/build/libs/core.jar - probably a corrupt zip
//     Caused by: java.lang.IllegalArgumentException at org.objectweb.asm.ClassReader.<init>
//
// "probably a corrupt zip" is FML guessing, and the zip is fine -- which is what makes this expensive
// to diagnose from the message alone.
//
// The classes still reach runtime, downgraded: shadowJar below bundles core's output and jvmdg rewrites
// the result to major 52. So the mod jar carries a Java 8 core and the raw Java 21 jar stays off the
// classpath entirely. Verify with:
//     unzip -p mc1710/build/libs/crystalgui-1.0.0-dev.jar com/crystalgui/ui/UIWindow.class | od -An -t u1 -N 8
dependencies {
    compileOnly(project(":core"))

    // :language's engine API, for the DOWNGRADE CLASSPATH ONLY -- see downgradeJar below.
    engineApi(project(path = ":language", configuration = "engineApi"))

    // The bands, for bundling and for manifests -- see bundleEngineBands and writeEngineManifests.
    engineBand8(project(path = ":language", configuration = "engineBand8Bundle"))
    engineBand11(project(path = ":language", configuration = "engineBand11Bundle"))
    engineBand17(project(path = ":language", configuration = "engineBand17Bundle"))

    // The version the GTNH convention already puts on the DEV run -- stated here because only the dev
    // variant is on that classpath and an obf mods folder needs the release one. @see obfMixinBootstrap
    obfMixinBootstrap("io.github.legacymoddingmc:unimixins:0.2.1")
}

// ── The engine band, bundled (§26.2) ────────────────────────────────────────────────────────────
//
// A shipped jar has no `-Dcrystalgui.engines.dir`, so without this it has no bands at all and the editor
// colours without analysing -- a legitimate degradation, and not the one anybody installing a code editor
// wants. Bundled rather than downloaded, which is the opposite call from the mappings and deliberately
// so: EPL and MPL plainly permit redistribution, and offline-by-default is worth more than a slim jar for
// a tool people install in order to write code.
//
// AS RESOURCES UNDER assets/, not as a flattened classpath. They must stay whole jars: EngineClassLoader
// is a URLClassLoader and opens them by URL after extraction, and merging their classes into the mod jar
// would put ECJ and Rhino beside the application -- exactly what the band split exists to prevent.
//
// The INDEX is what makes them findable. A ClassLoader cannot list a resource directory, and every route
// that fakes it is a special case of where the resource physically lives; one text file reads the same
// however it is stored. @see EngineBundle
/**
 * Which bands this jar CARRIES. `-PcgBundleBands=8`, `8,17`, or `none`.
 *
 * Default 8, because that is what a 1.7.10 client runs. A pack that ships lwjgl3ify and Java 17 can bake
 * 17 instead -- or both, at about 29 MB -- and a slim build can bake none and rely entirely on the
 * download. The runtime path is identical either way: bundled is tried first, the download second, and
 * `firstOf` takes the first non-empty answer.
 */
val bundledBands: List<Int> = providers.gradleProperty("cgBundleBands").orNull
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "none" }?.map { it.toInt() }
    ?: listOf(8)

fun configurationForBand(band: Int): Configuration = when (band) {
    8 -> engineBand8
    11 -> engineBand11
    17 -> engineBand17
    else -> throw GradleException("unknown engine band $band; known bands are 8, 11 and 17")
}

val bundleEngineBands = tasks.register<Sync>("bundleEngineBands") {
    group = "build"
    description = "Lays the selected bands' jars out as jar resources, with the index EngineBundle reads."
    // The Sync's OWN destination is the bundle root and each band is a path INSIDE it. Syncing straight
    // into a band directory instead makes that directory the task's output, so `from(...)` in shadowJar
    // copies its CONTENTS -- jars and an index at the root of the mod jar, with the assets/ prefix
    // silently gone and nothing to say so.
    into(layout.buildDirectory.dir("engine-bundle"))
    for (band in bundledBands) {
        into("assets/crystalgui/engines/$band") { from(configurationForBand(band)) }
    }
    doLast {
        for (band in bundledBands) {
            val directory = layout.buildDirectory
                .dir("engine-bundle/assets/crystalgui/engines/$band").get().asFile
            val jars = directory.listFiles()?.filter { it.name.endsWith(".jar") }?.map { it.name }?.sorted()
                ?: emptyList()
            // SORTED, so the classpath order is identical on every machine that builds this. `Sync` copies
            // in whatever order the filesystem reports, and two jars declaring the same package would
            // otherwise resolve differently per build host -- the bug that reproduces for one person only.
            directory.resolve("index.txt").writeText(
                "# Band $band engine jars, in classpath order. Written by :mc1710:bundleEngineBands.\n"
                        + jars.joinToString("\n") + "\n")
        }
    }
}

/**
 * One manifest per band: artifact name, digest, and where to fetch it.
 *
 * WHY THE DIGEST IS COMPUTED HERE rather than read from Maven's published `.sha1`. Hashing the file
 * Gradle resolved pins *the exact bytes this build was tested against*, needs no network at build time,
 * and can be verified offline. Reading upstream's checksum pins whatever the remote says today, which is
 * a different claim and a weaker one.
 *
 * It is MD5 because `CacheFiles` computes MD5, and that is honest about what it buys: a
 * corruption-and-drift check -- a truncated transfer, a mirror serving something else, a half-written
 * cache entry. It is NOT a security boundary and must not be described as one; authenticity comes from
 * HTTPS to Maven Central.
 *
 * The URL is derived from the module coordinates rather than taken from the resolver, because a developer
 * resolving through a mirror or a local cache would otherwise bake their own machine's addresses into a
 * shipped artifact.
 *
 * DECLARED BEFORE ITS USES. A .gradle.kts script runs top to bottom, so a forward reference to a
 * script-level fun or val does not resolve -- unlike a class, where member order is free.
 */
fun manifestFor(band: Int, configuration: Configuration) {
    // A SEPARATE OUTPUT TREE from bundleEngineBands', and that is not tidiness. That task is a `Sync`,
    // and a Sync DELETES whatever is not in its source -- so manifests written into engine-bundle/ would
    // survive or vanish depending on which task ran last, which is the kind of thing that works on the
    // machine that wrote it and fails in CI.
    val directory = layout.buildDirectory.dir("engine-manifests/assets/crystalgui/engines/$band").get().asFile
    directory.mkdirs()
    val rows = configuration.resolvedConfiguration.resolvedArtifacts
        .map { artifact ->
            val id = artifact.moduleVersion.id
            val path = id.group.replace('.', '/') + "/" + id.name + "/" + id.version
            val fileName = artifact.file.name
            val digest = MessageDigest.getInstance("MD5")
                .digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            "$fileName|$digest|https://repo1.maven.org/maven2/$path/$fileName"
        }
        // SORTED, for the same reason index.txt is: two hosts must produce byte-identical output, or the
        // manifest changes with the filesystem's mood and every build shows a diff nobody made.
        .sorted()
    directory.resolve("manifest.txt").writeText(
        "# Band $band engine jars: name|md5|url. Written by :mc1710:writeEngineManifests.\n"
                + rows.joinToString("\n") + "\n")
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
 * The two are written by different tasks from the same configuration, so they can drift: a re-pin that
 * regenerates one and not the other ships a jar whose digest describes the previous version. Nothing at
 * runtime would notice -- the bundled band is used as-is and the manifest is only read when a band is
 * MISSING -- so the mismatch would surface as a download that always fails its digest on some other
 * host, which is about as far from the cause as a symptom can get.
 *
 * WHAT IT CAN AND CANNOT CATCH, since it depends on writeEngineManifests and therefore always compares
 * against a freshly written file: it catches the two tasks DISAGREEING ABOUT THEIR INPUTS -- a different
 * configuration, a filter added to one and not the other, a band bundled from one source and described
 * from another. It cannot catch a hand-edited manifest, because the generator overwrites one before the
 * comparison runs. That is the drift worth guarding: nobody edits these by hand, and the tasks are edited
 * separately.
 *
 * Part of `check`, because a guard nobody runs is a guard that is not there.
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
            if (!manifest.isFile) {
                throw GradleException("band $band has no manifest; run writeEngineManifests")
            }

            val declared = manifest.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { row -> row.split("|").let { it[0] to it[1] } }
            val present = (bundled.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".jar") }
                .associate { jar ->
                    jar.name to MessageDigest.getInstance("MD5").digest(jar.readBytes())
                        .joinToString("") { "%02x".format(it) }
                }

            val missing = present.keys - declared.keys
            val extra = declared.keys - present.keys
            val wrong = present.filter { (name, digest) -> declared[name]?.equals(digest) == false }.keys
            if (missing.isNotEmpty() || extra.isNotEmpty() || wrong.isNotEmpty()) {
                throw GradleException(
                    "band $band's manifest does not describe its bundled jars.\n"
                            + "  bundled but not declared: $missing\n"
                            + "  declared but not bundled: $extra\n"
                            + "  declared with a stale digest: $wrong"
                )
            }
        }
    }
}

tasks.named("check") { dependsOn(checkEngineManifest) }

// ECJ, Rhino and the two Eclipse platform jars the adapter names, on `downgradeJar`'s classpath.
//
// NOT on the compile or run classpath, and that distinction is the whole point: an engine is loaded by
// EngineClassLoader from a band directory, so these must never be beside the application. But jvmdg is
// not running the classes, it is REWRITING them -- it walks the supertypes of every referenced type to
// decide what needs a Java 8 stub, and `DowngradeJar.classpath` defaults to this module's
// `main.compileClasspath`, which cannot contain them because `compileOnly` is not transitive across a
// project dependency.
//
// So every one of the 140 types in those jars was reported `Could not find class` and then treated as
// having no supertypes at all: 1,820 error lines per build. Harmless here -- the stub mapper only needs
// a supertype walk to notice a Java 9+ API reached THROUGH an inherited member, and these are all
// Java 8-era third-party APIs -- but a wall of red that hides anything real, and the same absence would
// be silent rather than harmless the day one of them did inherit something stubbed.
//
// Borrowed from :language rather than re-declared, so `jdt.core:3.26.0` is pinned in exactly one file.
tasks.named<DowngradeJar>("downgradeJar") {
    classpath = classpath.plus(engineApi)
}

// Remove Kotlin and a Java 9 file from JOML when shadowing.
// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
// This is for distributing the jar.
// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
// - Hussar
// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
// GL-state diagnostics, off unless asked for. CrystalGraphics/AGENTS.md documents both:
//
//   ./gradlew :mc1710:runClient -PcgNoDedup      never eliminate a GL call -- distinguishes "the state
//                                                shadow is lying" from a real rendering bug in one run
//   ./gradlew :mc1710:runClient -PcgStateVerify  verify the shadow against the driver before eliding
//                                                anything, naming the offending domain. Very slow.
//
// A missing GL call produces wrong rendering and NO exception, so there is nothing to search for
// without these; they are the only way to rule the manager in or out.
// APPLIED TO BOTH CLIENTS, and that is what makes §26.8 testable at all. `runObfClient` launches the
// FORGE OBFUSCATED client -- Minecraft at SRG names, which is production -- so it is the only run in this
// build where the namespace probe answers "obfuscated" and the mapping path actually executes. Wiring the
// harness to `runClient` alone would leave the last mile verifiable only by hand, which is precisely the
// loop the harness exists to remove.
listOf("runClient", "runObfClient").forEach { runTask ->
tasks.named<JavaExec>(runTask) {
    if (providers.gradleProperty("cgNoDedup").isPresent) {
        systemProperty("crystalgraphics.state.noDedup", "true")
    }
    if (providers.gradleProperty("cgStateVerify").isPresent) {
        systemProperty("crystalgraphics.state.verify", "true")
    }

    // THE ENGINE BANDS, staged for the dev run exactly as runHarness does it.
    //
    // ECJ and Rhino are NOT on the mod's classpath and must not be: EngineClassLoader loads each band
    // in isolation, and putting ECJ beside the application defeats the band split entirely. What they
    // need is a directory laid out one subdirectory per band, named by this property.
    //
    // Band 8 is the one that matters here and it is not a compromise -- the 1.7.10 dev run launches on
    // a Java 8 JVM, so EngineBand.detect() answers 8 and loads Rhino 1.7.15.1 and ECJ 3.26.0, which are
    // pinned precisely because they are the last releases whose class files that JVM can read.
    //
    // Absent is a legitimate deployment: EngineHost prints one line to stderr and the editor colours
    // without analysing. That is the degradation the whole stack is built around, so this is wiring a
    // capability on, not repairing a hole.
    //
    // -PcgBundledEngines instead points at NOTHING and makes the client fall back to the band bundled
    // inside the jar, extracting it to <cacheRoot>/engines on first use. That is exit criterion 1 -- "a
    // shipped jar opens the editor with analysis working, no system property set" -- and it is only
    // testable by withholding the property, because the property WINS when it is set.
    //
    // The bundle directory joins the run classpath rather than the jar, because a dev run loads the mod
    // from source sets and never opens the shadow jar at all: without this the resource is in an artifact
    // nothing on this classpath reads, and extraction would report an empty band while the jar it was
    // testing is perfectly correct.
    if (providers.gradleProperty("cgBundledEngines").isPresent) {
        dependsOn(tasks.named("bundleEngineBands"), tasks.named("writeEngineManifests"))
        classpath(layout.buildDirectory.dir("engine-bundle"))
        // AND THE MANIFESTS, which is what makes -PcgBundleBands=none a usable test rather than just a
        // smaller jar: with no band bundled the client falls through to the DOWNLOAD, and the download
        // reads its name|md5|url list from here. It is the only way to exercise that path on a host whose
        // own band is the bundled one.
        classpath(layout.buildDirectory.dir("engine-manifests"))
    } else {
        dependsOn(":language:stageEngines")
        systemProperty("crystalgui.engines.dir",
            project(":language").layout.buildDirectory.dir("engines").get().asFile.absolutePath)
    }

    // UNATTENDED CAPTURE:  ./gradlew :mc1710:runClient -PcgAutoTest
    //
    // Opens CrystalEditor over the main menu, paints ten frames, writes a PNG and quits -- so the whole
    // launch/open/look loop is one blocking command with an artifact at the end, exactly what
    // ArtifactService.requestCapture gives the GL debug harness. Every render defect before this was
    // diagnosed by a person launching the game and describing what they saw, which is slow and puts a
    // human in a loop that is really "render N frames and read the pixels".
    // -PcgTrace prints where the first editor open spends its time. Separate from cgAutoTest because it
    // is just as useful on a hand-driven run, where the freeze was noticed in the first place.
    if (providers.gradleProperty("cgTrace").isPresent) {
        systemProperty("crystalgui.startup.trace", "true")
    }

    // -PcgFrameProfile names where a SLOW frame's time went, once a second, for as long as frames are
    // slow. cgTrace above covers the first frame only, which is the startup question; this is the
    // steady-state one -- "opening this file drops 120fps to 55" needs to know which phase, and for the
    // cascade which ELEMENTS are being re-matched.
    if (providers.gradleProperty("cgFrameProfile").isPresent) {
        systemProperty("crystalgui.frameprofile", "true")
    }

    // -PcgNetProbe runs BOTH in-game network probes, in order. CgUiNetProbe echoes raw frames
    // client->server->client over the Forge channel; CgUiSessionProbe then runs a real
    // Server/ClientUiSession pair over the same wire and checks the description handshake, a state
    // delta, an event and a server->client call. The headless tests cover everything above
    // CgNetworkChannel and nothing below it, so neither layer is proven without this.
    if (providers.gradleProperty("cgNetProbe").isPresent) {
        systemProperty("crystalgui.net.probe", "true")
    }

    // -PcgGlassProbe reports, once every 120 frames, what the backdrop material actually costs: the
    // frame PERIOD, the CPU time inside the capture and the blur, how many consumers asked, how often
    // the capture had to be retaken, and the size of the region it covered against the size of the
    // screen. The region is the whole point -- glass was a frame killer while the capture was sized to
    // the surface rather than to what asked for it -- so `rect=WxH (N% of ...)` is the line to read.
    //
    // CPU-side timing: neither glFinish nor a timer query is on CgGL, so the stage figures are a lower
    // bound and the frame period is the honest number. If the stages stay small while the period grows,
    // the cost is GPU-side in the work they enqueue.
    if (providers.gradleProperty("cgGlassProbe").isPresent) {
        systemProperty("crystalgui.glass.probe", "true")
    }

    // -PcgSessionProbe runs a real Server/ClientUiSession pair over the connections CgUiConnections
    // opens on player join -- the path that ships. SEPARATE from -PcgNetProbe on purpose: a channel
    // takes one inbound handler, so the raw transport probe owns it and the lifecycle stands down while
    // that flag is set. One tests the engine, the other tests the wiring.
    if (providers.gradleProperty("cgSessionProbe").isPresent) {
        systemProperty("crystalgui.session.probe", "true")
    }

    // -PcgWireProbe moves 4 MB each way over a REAL socket and reports the rate. The frame ceiling is
    // asymmetric by a factor of 64 on 1.7.10 -- 32,766 bytes up, 2,097,050 down -- so an upload and a
    // download of one file are not the same transfer, and no in-JVM test can show the difference.
    // Pair with -PcgJoin against a running :mc1710:runServer.
    if (providers.gradleProperty("cgWireProbe").isPresent) {
        systemProperty("crystalgui.wire.probe", "true")
    }

    // -PcgEditorProbe opens the editor and then works through it, on the INTEGRATED server. That
    // configuration is the one a player actually runs and the one no other probe covers: every other
    // probe here closes the GUI or never opens one, which is how a `doesGuiPauseGame` returning true
    // took the whole workspace down in single-player with nothing in the log. A dedicated server
    // cannot be paused by a client GUI, so this deliberately refuses to run in multiplayer.
    if (providers.gradleProperty("cgEditorProbe").isPresent) {
        systemProperty("crystalgui.editor.probe", "true")
    }

    // -PcgTwoClientProbe=writer|watcher, run on BOTH of two clients joined to one runServer. The
    // watcher subscribes and reports what reached it; the writer creates a file and then edits it.
    // Everything the watcher, presence and the conflict path exist for is a statement about a SECOND
    // client, and one client is the fixture that passes against all of it.
    providers.gradleProperty("cgTwoClientProbe").orNull?.let { role ->
        systemProperty("crystalgui.twoclient.probe", role)
    }

    // -PcgJoin=host[:port] makes the client connect straight to a server instead of the main menu.
    // 1.7.10's own Main parses --server/--port, and Minecraft.startGame goes to GuiConnecting when
    // serverName is set -- so this needs no code of ours, only the arguments.
    //
    // With -PcgRemoteProbe it is the whole point: the integrated server shares a JVM and a filesystem,
    // so "the workspace lives on the server" is untestable in single player. Two processes is the test.
    providers.gradleProperty("cgJoin").orNull?.let { target ->
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "25565")
        args("--server", host, "--port", port)
        systemProperty("crystalgui.remote.probe", "true")
    }

    if (providers.gradleProperty("cgAutoTest").isPresent) {
        systemProperty("crystalgui.autotest", "true")
        systemProperty("crystalgui.autotest.out",
            layout.buildDirectory.file("crystalgui-autotest.png").get().asFile.absolutePath)
        // -PcgAutoTest=<saveFolder> loads that world first; a bare -PcgAutoTest stays on the main menu.
        providers.gradleProperty("cgLateFrame").orNull?.let {
            systemProperty("crystalgui.autotest.lateFrame", it)
        }
        val world = providers.gradleProperty("cgAutoTest").get()
        if (world.isNotEmpty() && world != "true") systemProperty("crystalgui.autotest.world", world)

        // -PcgScript=Probe.java compiles and runs one snippet once the editor is up, logging each step.
        // The EXTENSION picks the language, which is the comparison that matters when one runs and the
        // other kills the client. -PcgScriptSource overrides the snippet. @see CgUiAutoTest#runScriptOnce
        providers.gradleProperty("cgScript").orNull?.let {
            systemProperty("crystalgui.autotest.script", it)
        }
        providers.gradleProperty("cgScriptSource").orNull?.let {
            systemProperty("crystalgui.autotest.scriptSource", it)
        }
        providers.gradleProperty("cgFrame").orNull?.let {
            systemProperty("crystalgui.autotest.frame", it)
        }
        // -PcgBytes=net/minecraft/client/Minecraft reports every member the LIVE class declares and no
        // file does -- the §15.5 A claim, shown rather than asserted.
        providers.gradleProperty("cgBytes").orNull?.let {
            systemProperty("crystalgui.autotest.bytes", it)
        }
        // -PcgComplete=true logs the member list the live editor produces for four receiver shapes,
        // with the classpath it resolved them against. Every layer answers correctly in every JVM this
        // can be driven from -- including the harness, through the same call -- so the remaining
        // difference is the client itself, and it cannot be reached any other way.
        providers.gradleProperty("cgComplete").orNull?.let {
            systemProperty("crystalgui.autotest.complete", it)
        }
        // -PcgNoLive turns §15.5 A's live name environment off while leaving the platform registered,
        // so the same client can be run both ways and a difference attributed to that one input.
        providers.gradleProperty("cgNoLive").orNull?.let {
            systemProperty("crystalgui.language.noLiveBytes", it)
        }
    }
}
}

// ── The dedicated-server smoke check ─────────────────────────────────────────────────
//
//     ./gradlew :mc1710:serverSmoke
//
// Boots a dedicated server, asserts the server-side stack came up, stops it. Exit 0 on pass, 1 on fail.
//
// THIS IS THE ONLY CHECK IN THE BUILD THAT CAN SEE ITS CLASS OF BUG. Three fatal defects shipped
// undetected until a server was booted by hand for the first time -- CrystalGraphics' platform bundle
// building all nine services eagerly, CgPlatform asking for a GL backend unconditionally, and a
// client-only guard sitting one level too high. Every one is a RUNTIME property ("a client-only class is
// constructed on a server"), so 1090 headless tests could not see them, the GL harness could not see them
// (it is a client by design), and an import scan cannot answer a question about class loading. Starting
// the server found all three in one run. @see CgUiServerSmoke, which carries the full reasoning.
//
// Wired to `runServer` rather than being its own JavaExec: reproducing that task's classpath, JVM args
// and working directory is a copy that goes stale, and the run has to be the real one or it proves
// nothing. The task name is read out of the start parameters because a Gradle PROPERTY cannot be set by
// a task dependency -- so `serverSmoke` and `runServer -PcgServerSmoke` are the same run, spelled twice.
val serverSmokeRequested = gradle.startParameter.taskNames.any {
    it == "serverSmoke" || it.endsWith(":serverSmoke")
}

val serverSmokeReport = layout.buildDirectory.file("serverSmoke/result.txt")

tasks.named<JavaExec>("runServer") {
    if (serverSmokeRequested || providers.gradleProperty("cgServerSmoke").isPresent) {
        systemProperty("crystalgui.server.smoke", "true")
        systemProperty("crystalgui.server.smoke.report", serverSmokeReport.get().asFile.absolutePath)

        // --nogui because the dedicated server otherwise opens a Swing console and blocks on it; a check
        // that needs a window closed is not a check a pipeline can run.
        args("nogui")

        // A PORT OF ITS OWN, and this is not tidiness -- it is the first thing the check caught, about
        // itself. 25565 was in use (another server, a leftover, a second worktree), the bind failed, FML
        // forced SERVER_STOPPED, FMLServerStartedEvent never fired so not one assertion ran, and the JVM
        // exited 0: BUILD SUCCESSFUL having checked nothing. 1.7.10's MinecraftServer.main parses --port,
        // so this needs no code of ours.
        val port = providers.gradleProperty("cgSmokePort").orNull ?: "25599"
        args("--port", port)

        // Deleted BEFORE the run, so a stale report from a previous run cannot pass for this one.
        doFirst { serverSmokeReport.get().asFile.delete() }
    }
}

tasks.register("serverSmoke") {
    group = "crystalgui"
    description = "Boots a dedicated server, asserts the server-side stack came up, and stops it."
    dependsOn(tasks.named("runServer"))
    outputs.upToDateWhen { false }

    // THE HALF THAT MAKES IT SOUND. The mod halts(1) when a check fails, which covers "ran and failed";
    // nothing covered "never ran", and that is the case that actually happened on the first run. An
    // absent report is therefore a failure with a message, never a pass.
    doLast {
        val file = serverSmokeReport.get().asFile
        if (!file.isFile) {
            throw GradleException(
                "The dedicated server produced no smoke report at " + file.absolutePath + ".\n" +
                    "The server did not reach FMLServerStartedEvent, so NO check ran -- this is not a " +
                    "pass. Look above for the reason: a failed port bind, a mod refusing to load, or a " +
                    "crash during startup. Use -PcgSmokePort=<n> if 25599 is taken.")
        }
        val verdict = file.readLines().firstOrNull()?.trim().orEmpty()
        if (verdict != "PASS") {
            throw GradleException("Dedicated-server smoke FAILED:\n" + file.readText())
        }
        logger.lifecycle(file.readText())
    }
}

// ── The reobfuscated client (§26.8) ─────────────────────────────────────────────────────────────
//
// `runObfClient` launches Minecraft at SRG names, which is the only run in this build where the
// namespace probe answers "obfuscated" and the mapping path actually executes. Two things stop it
// working out of the box, and neither is ours:
//
//  1. GTNH's ToolchainModule overrides the working directory of runClient, runVanillaClient AND
//     runObfClient to `runClientDirectory` -- while RFG's prepareObfModsFolder stages the reobfuscated
//     jars into `run/obfuscated/mods`. So the obf client searches run/client/mods, finds nothing, and
//     loads three mods (mcp, FML, Forge) with no error anywhere. Put back.
//
//  2. Only THIS project's jar is staged. CrystalGraphics is the parent mod and always present in
//     production, so an obf run without it is not a production shape at all -- its reobfuscated jar is
//     copied in beside ours.
val stageObfMods = tasks.register<Copy>("stageObfMods") {
    group = "crystalgui"
    description = "Puts the reobfuscated CrystalGUI and CrystalGraphics jars where the obf client looks."
    into(layout.projectDirectory.dir("run/obfuscated/mods"))
    from(tasks.named("reobfJar"))
    // CrystalGraphics is an INCLUDED BUILD, not a subproject, so `project(":CrystalGraphics:mc1710")`
    // does not resolve -- the `:CrystalGraphics:` prefix in Gradle's own output is the included build's
    // name rather than a project path. Its task is reachable through `gradle.includedBuild` and its
    // artifact by path; there is no substitution for a reobfuscated jar, because substitutions resolve
    // the DEV one.
    dependsOn(gradle.includedBuild("CrystalGraphics").task(":mc1710:reobfJar"))
    from(rootProject.file("CrystalGraphics/mc1710/build/libs/crystalgraphics-1.0.0.jar"))

    // AND THE MIXIN BOOTSTRAP. CrystalGraphics declares mixins.crystalgraphics.json, so LaunchWrapper
    // asks for org.spongepowered.asm.launch.MixinTweaker before any mod loads -- and the dev run gets it
    // from a `devOnlyNonPublishable` dependency that, by definition, is not published into an obf mods
    // folder. Without it the obf client dies with a ClassNotFoundException naming Mixin, several layers
    // above anything of ours.
    //
    // The RELEASE artifact, not the `-dev` one the dev run uses: `-dev` is mapped for a deobfuscated
    // environment, which is the opposite of what this run is for.
    from(obfMixinBootstrap)
}

tasks.named<JavaExec>("runObfClient") {
    dependsOn(stageObfMods)
    workingDir = layout.projectDirectory.dir("run/obfuscated").asFile
}

tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }
    // The band, as resources. Shadow rewrites .class entries and copies everything else verbatim, so a
    // nested jar crosses intact -- which is required: relocating inside ECJ would rename types its own
    // reflection looks up by string.
    from(bundleEngineBands)
    // AND THE MANIFESTS, which are the other half of the same directory: band 8's jars plus a
    // name|md5|url list per band, so a host on a band the jar does not carry can fetch its own.
    dependsOn(writeEngineManifests)
    from(writeEngineManifests)
    exclude("module-info.class")
    exclude("kotlin/**")
    exclude("org/jetbrains/kotlin/**")

    // Bundle core/ and language/ classes into the shadow JAR so the mod is self-contained
    dependsOn(":core:jar")
    dependsOn(":language:jar")
}

afterEvaluate {
    tasks.shadowJar.configure {
        val coreJar = project(":core").tasks.named<Jar>("jar").get()
        from(zipTree(coreJar.archiveFile.get()))

        // :language, and the tree-sitter jars it needs.
        //
        // UNPACKED HERE RATHER THAN DECLARED AS `shadowImplementation`, and the difference is not
        // stylistic: shadowImplementation RELOCATES, and every tree-sitter jar carries the JNI natives
        // for its grammar. A JNI symbol is named Java_<mangled-package>_<class>_<method>, so renaming
        // the Java package renames the symbol the .dll does NOT export, and the first parser built
        // throws UnsatisfiedLinkError. `from(zipTree(...))` copies the entries verbatim, which is what
        // both the classes and the natives need.
        //
        // (Taffy above is relocated and that is fine -- it is pure Java, zero natives, checked.)
        val languageJar = project(":language").tasks.named<Jar>("jar").get()
        from(zipTree(languageJar.archiveFile.get()))
        rootProject.file("lib/tree-sitter").listFiles()
            ?.filter { it.name.endsWith(".jar") }
            ?.forEach { from(zipTree(it)) }
    }
}
