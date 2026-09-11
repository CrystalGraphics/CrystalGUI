// `java` is the JavaPluginExtension in a build script, so the PACKAGE has to be imported to be named.
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

// Root project — pure build coordinator. No source lives here.
//
// MC version subprojects:
//   :runtime:mc:1710         — Minecraft 1.7.10 + Forge (LWJGL 2, gtnhconvention)
//   :runtime:mc:modern:common  — the 1.20.x platform seam, shared by all three loaders
//   :runtime:mc:modern:{forge,neoforge,fabric} — registration only
//
// Platform-agnostic subprojects:
//   :core     — platform-agnostic UI engine

plugins {
    idea
    // Applied here so the whole build shares one copy. gtnhgradle and ModDevGradle both want idea-ext
    // but request it under different Maven coordinates, so Gradle loads both classes -- and moddev's
    // `hasPlugin(IdeaExtPlugin.class)` guard names its own, applies a second copy, and the `settings`
    // extension collides. The root buildscript scope is the parent of every subproject's, so
    // parent-first loading gives both plugins the same class.
    //
    // Only breaks an IDE sync; a CLI build constructs no IDEA model. plan/platform-mc1201.md L0.
    id("org.jetbrains.gradle.plugin.idea-ext")

    // What this mod says about itself, declared once and printed into every format the merged jar
    // needs. Applied to the ROOT because the merged descriptors describe every loader at once and
    // belong to no one of them.
    id("cg-descriptors")

    // The merge: four thin jars and one engine into the artifact every loader installs.
    id("cg-single-jar")
}

// Machine-local settings (`local.properties`, gitignored) onto every project's `extra`, before
// anything reads one. @see gradle/local-settings.gradle.kts
apply(from = rootProject.file("gradle/local-settings.gradle.kts").toURI())

// ── Everything a consuming mod's dev run reads, built ────────────────────────────────────────────
//
// A mod that consumes CrystalGUI as a composite puts these jars on its game classpath, and nothing
// else in its build asks for them: Gradle compiles against a project's CLASSES variant, so no jar task
// ever enters the graph, and ModDevGradle's additionalRuntimeClasspath yields jar PATHS without
// registering the tasks that produce them. The consumer therefore runs whatever is on disk -- which
// meant a months-old crystalgui-mc1201-common, and a platform.jar predating a fix to CgGL that crashed
// on a source line that no longer existed.
//
// One task rather than a list the consumer maintains, and it lives here because only this build can
// reach CrystalGraphics: gradle.includedBuild("CrystalGraphics") is not resolvable from a build that
// includes US. A jar added to the consumer's classpath later is added here, not in every consumer.
// ── Every single jar into every real client (J5, J8) ─────────────────────────────────────────────
//
// One task rather than four, because there is now one artifact per mod rather than one per loader.
// CrystalGraphics goes with it: CrystalGUI does not run without it, and shipping one of a matched
// pair is how an afternoon disappears.
//
// `-PcgNoLanguage` leaves `crystalgui_language` out, which is the degraded configuration J8 exists to
// make possible and therefore the one worth being able to run: the editor opens, colours from core's
// built-in lexers, and the log says the stack is absent.
//
// The instance directories live in `local.properties`, which is gitignored. UNSET IS NORMAL -- a
// fresh clone has none and must still build -- so a missing key skips that instance and says so.
val cgWithLanguage = !providers.gradleProperty("cgNoLanguage").isPresent

// ── Which instances to install into ──────────────────────────────────────────────────────────────
//
//   prismInstance.<label> = <dir>       one line per instance; <label> is prodSmoke's target name
//   prismInstanceJoml     = 1710,1171   those whose Minecraft ships no JOML -- see below
//
// A LIST rather than four fixed keys, so adding a target is a line in a gitignored file instead of an
// edit here. The four `prismLauncher<X>Dir` keys are still read when the file names no
// `prismInstance.` key, so a local.properties written before this keeps working.
//
// JOML IS OPT-IN PER INSTANCE and cannot be derived from the label. Minecraft ships JOML from 1.19.3
// on; below that the companion jar is required and above it a second copy is a split package that
// kills Forge in module resolution. 1.7.10 needs it, and so does anything else pre-1.19.3.
data class PrismInstance(val key: String, val label: String, val needsJoml: Boolean)

val prismInstances: List<PrismInstance> = run {
    val file = rootProject.file("local.properties")
    val settings = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    val prefix = "prismInstance."
    val joml = (settings.getProperty("prismInstanceJoml") ?: "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val declared = settings.stringPropertyNames()
        .filter { it.startsWith(prefix) && !settings.getProperty(it).isNullOrBlank() }
        .sorted()
        .map { key -> key.removePrefix(prefix).let { PrismInstance(key, it, it in joml) } }
    declared.ifEmpty {
        listOf(
            PrismInstance("prismLauncher1710Dir", "1710", true),
            PrismInstance("prismLauncher1201ForgeDir", "1201forge", false),
            PrismInstance("prismLauncher1204NeoForgeDir", "1204neoforge", false),
            PrismInstance("prismLauncher1201FabricDir", "1201fabric", false),
        )
    }
}

val deploySingleJars = tasks.register("deploySingleJars") {
    group = "crystalgui"
    description = "Puts every single jar into every Prism instance named in local.properties."

    val crystalGraphics = gradle.includedBuild("CrystalGraphics")
    dependsOn("checkSingleJar", crystalGraphics.task(":checkSingleJar"))
    if (cgWithLanguage) dependsOn("checkLanguageJar")

    val guiJar = layout.buildDirectory.file("libs/crystalgui-$version.jar")
    val langJar = layout.buildDirectory.file("libs/crystalgui-language-$version.jar")
    val graphicsJar = File(crystalGraphics.projectDir, "build/libs/crystalgraphics-$version.jar")
    // JOML, FOR THE LWJGL2 TARGETS ONLY -- see the note where it is installed below.
    val jomlJar = File(crystalGraphics.projectDir, "build/libs/crystalgraphics-joml-$version.jar")
    val withLanguage = cgWithLanguage
    val localProperties = rootProject.file("local.properties")
    val instances = prismInstances

    doLast {
        if (!localProperties.isFile) {
            logger.lifecycle("[cgui] no local.properties; nothing to deploy to")
            return@doLast
        }
        val settings = Properties().apply { localProperties.inputStream().use { load(it) } }
        val jars = buildList {
            add(guiJar.get().asFile)
            if (withLanguage) add(langJar.get().asFile)
            add(graphicsJar)
        }
        (jars + jomlJar).filterNot { it.isFile }
            .forEach { throw GradleException("${it.name} was not built") }
        if (!withLanguage) logger.lifecycle("[cgui] -PcgNoLanguage: crystalgui_language is NOT deployed")

        instances.forEach { inst ->
            val key = inst.key
            val dir = settings.getProperty(key)
            if (dir.isNullOrBlank()) {
                logger.lifecycle("[cgui] {} is not set; skipping", key)
                return@forEach
            }
            val mods = File(dir, ".minecraft/mods")
            mods.mkdirs()
            // ONLY OURS. UniMixins on 1.7.10 and fabric-api on Fabric live here too and are not ours
            // to delete -- and the names being deleted include the RETIRED per-loader ones, so an
            // instance that had `crystalgui-mc1201-forge-...-srg.jar` does not end up with both.
            //
            // `crystalgui_` IS A RETIRED NAME AND STAYS IN THIS LIST. The language jar was
            // `crystalgui_lang-1.0.0.jar` until 2026-09-10; dropping the prefix when the name changed
            // left the old jar beside the new one in every instance, and two jars exporting
            // `com.crystalgui.language` is a split package -- Forge died straight after "Initialized
            // transformers" with nothing in any log, exactly as an unrelocated ASM does. A name this
            // task stops matching is a name it stops CLEANING UP, so retired spellings are added here,
            // never removed.
            val oursPrefixes = listOf("crystalgui-", "crystalgui_", "crystalgraphics-")
            mods.listFiles().orEmpty()
                .filter { file -> oursPrefixes.any { file.name.startsWith(it) } }
                .forEach { it.delete() }
            // JOML GOES ONLY WHERE MINECRAFT SHIPS NONE, and it is the one artefact with that shape.
            //
            // MC 1.19.3+ ships JOML as a real named module, so a second copy in `mods/` is a split
            // package -- measured, E-J9-JOML: Forge dies in module resolution before it writes a log
            // line. 1.7.10 has no JOML at all and no module system to object, so it needs exactly
            // this. Installing it everywhere would break every 1.19.3+ instance, so it is declared
            // per instance in local.properties (prismInstanceJoml) rather than inferred here.
            val instanceJars = if (inst.needsJoml) jars + jomlJar else jars

            instanceJars.forEach { source ->
                val target = File(mods, source.name)
                // A RUNNING CLIENT HOLDS ITS MOD JARS OPEN on Windows, and Kotlin's copyTo reports
                // that as "tried to overwrite the destination, but failed to delete it" -- which
                // names neither the instance nor the cause.
                if (target.exists() && !target.delete()) {
                    throw GradleException(
                        "${target.name} in $key is locked, so it cannot be replaced. A Minecraft "
                            + "client is still running from that instance; close it and run again.")
                }
                source.copyTo(target, overwrite = true)
            }
            logger.lifecycle("[cgui] {} -> {}", key, mods)
        }
    }
}

// ── Every installed client, driven (J6) ──────────────────────────────────────────────────────────
//
// The client half of the matrix. `checkSingleJar` asserts what the jar IS; this asserts that four
// real clients each draw from it. Sequential, because there is one GPU and one launcher.
//
//   ./gradlew prodSmoke
//   ./gradlew prodSmoke -PcgTargets=1710,1201forge
val prodSmoke = tasks.register<cgbuildlogic.ProdSmoke>("prodSmoke") {
    // -PcgNoDeploy drives whatever is ALREADY installed. Rebuilding and redeploying both single jars is
    // minutes and driving the clients is seconds, so paying for the first while iterating on the second
    // is most of the wall clock for no answer.
    if (!providers.gradleProperty("cgNoDeploy").isPresent) dependsOn(deploySingleJars)
    // The same list `deploySingleJars` installs into, so a target added to local.properties is driven
    // without being named twice. `-PcgTargets` filters it by label.
    instances.set(prismInstances.map { "${it.key}=${it.label}" })
    outputDir.set(layout.buildDirectory.dir("prodSmoke"))
    onlyTargets.set(
        (providers.gradleProperty("cgTargets").orNull ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() })
}

tasks.register("assembleConsumerRuntime") {
    group = "crystalgui"
    description = "Builds every jar a consuming mod's dev run puts on its classpath."

    dependsOn(":core:jar", ":taffy:jar", ":runtime:mc:modern:common:jar", ":runtime:mc:modern:forge:jar")

    listOf(":core:jar", ":platform:jar", ":freetype-msdfgen-harfbuzz-bindings:jar",
           ":runtime:mc:modern:common:jar", ":runtime:mc:modern:forge:jar")
        .forEach { dependsOn(gradle.includedBuild("CrystalGraphics").task(it)) }
}

// ── download/locations.json: where every runtime download comes from ─────────────────────────────────
//
// ONE FILE at the repository root, bundled by :core beside DownloadLocations and re-read from master by
// every released jar, so a dead link is repaired for players by editing it; download/README.md is the
// guide. Here because the file is every module's. Its engine bands are :language's, read through the
// same bundle configurations the loaders bundle them from. The tasks read the file with JsonSlurper, and
// core's DownloadUrlsLiveInOneFileTest holds that reading to the runtime's.

repositories {
    // What the engine bands are made of, resolved here for the checks below.
    mavenCentral()
}

val engineBand8: Configuration by configurations.creating { isCanBeConsumed = false }
val engineBand11: Configuration by configurations.creating { isCanBeConsumed = false }
val engineBand17: Configuration by configurations.creating { isCanBeConsumed = false }

dependencies {
    engineBand8(project(path = ":language", configuration = "engineBand8Bundle"))
    engineBand11(project(path = ":language", configuration = "engineBand11Bundle"))
    engineBand17(project(path = ":language", configuration = "engineBand17Bundle"))
}

val downloadLocationsFile: File = file("download/locations.json")

/** The repository in that file which is this repository's own download-mirror release. */
val mirrorRepository = "mirror"

/** What a mirrored file is redistributed under, by name prefix. A file matching none is never mirrored. */
val mirrorLicences = linkedMapOf(
    "org.eclipse." to "EPL-2.0",
    "ecj-" to "EPL-2.0",
    "rhino-" to "MPL-2.0",
    "cfr-" to "MIT, (c) Lee Benfield",
    // Dual Apache-2.0 / LGPL-2.1; taken under Apache-2.0, as THIRD-PARTY.md records.
    "jna-" to "Apache-2.0",
    "org.osgi." to "Apache-2.0",
    "osgi." to "Apache-2.0",
    "intermediary-" to "CC0-1.0",
)

fun mirrorLicenceOf(fileName: String): String? =
    mirrorLicences.entries.firstOrNull { fileName.startsWith(it.key) }?.value

/** A JSON object's members by name; anything else has none. */
fun membersOf(value: Any?): Map<String, Any?> =
    (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

/** A string, or a list of them, as a list; anything else is none. */
fun stringsOf(value: Any?): List<String> = when (value) {
    is String -> listOf(value)
    is List<*> -> value.filterIsInstance<String>()
    else -> emptyList()
}

/** The file as JsonSlurper reads it: maps, lists, strings and numbers. */
fun readDownloadLocations(): Map<String, Any?> = membersOf(
    try {
        JsonSlurper().parse(downloadLocationsFile)
    } catch (malformed: Exception) {
        throw GradleException("download/locations.json is not JSON: ${malformed.message}")
    }
)

/** Maven's name for the file at [coordinates]: org.benf:cfr:0.152 is cfr-0.152.jar; @zip names the extension. */
fun mavenFile(coordinates: String): String {
    val parts = coordinates.substringBefore('@').split(':')
    if (parts.size !in 3..4 || parts.any { it.isEmpty() }) {
        throw GradleException("'$coordinates' is not group:artifact:version, with an optional :classifier and @extension")
    }
    val classifier = if (parts.size == 4) "-" + parts[3] else ""
    return parts[1] + "-" + parts[2] + classifier + "." + coordinates.substringAfter('@', "jar")
}

/** Where a Maven repository keeps it: org/benf/cfr/0.152/cfr-0.152.jar. */
fun mavenPath(coordinates: String): String {
    val file = mavenFile(coordinates)
    val parts = coordinates.substringBefore('@').split(':')
    return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + file
}

/** One download: its pin, every address best first, those not on our mirror, and its name there if it is. */
data class DownloadLine(
    val id: String,
    val digest: String?,
    val urls: List<String>,
    val upstream: List<String>,
    val mirrorFile: String?,
) {
    val isTemplate: Boolean get() = '{' in id
}

/** Every download the file lists, its addresses expanded as DownloadLocations expands them. */
fun downloadLines(json: Map<String, Any?>): List<DownloadLine> {
    val repositories = membersOf(json["repositories"]).mapValues { stringsOf(it.value) }
    fun line(id: String, digest: Any?, urls: List<String>, maven: String?, from: List<String>): DownloadLine {
        val all = urls.toMutableList()
        val upstream = urls.toMutableList()
        var mirrorFile: String? = null
        if (maven != null) {
            for (repository in from) {
                val layouts = repositories[repository]
                    ?: throw GradleException("$id comes from '$repository', a repository the file does not define")
                val expanded = layouts.map { it.replace("{path}", mavenPath(maven)).replace("{file}", mavenFile(maven)) }
                all += expanded
                if (repository == mirrorRepository) mirrorFile = mavenFile(maven) else upstream += expanded
            }
        }
        return DownloadLine(id, digest as? String, all, upstream, mirrorFile)
    }
    val lines = mutableListOf<DownloadLine>()
    for ((id, value) in membersOf(json["files"])) {
        val spec = membersOf(value)
        lines += line(id, spec["digest"], stringsOf(spec["urls"]), spec["maven"] as? String, stringsOf(spec["from"]))
    }
    val engines = membersOf(json["engines"])
    val from = stringsOf(engines["from"])
    for ((band, jars) in membersOf(engines["bands"])) {
        for ((coordinates, digest) in membersOf(jars)) {
            lines += line("engine/$band/" + mavenFile(coordinates), digest, emptyList(), coordinates, from)
        }
    }
    return lines
}

/** [file]'s digest in the algorithm [pin] names -- the three CacheFiles reads -- tagged the same way. */
fun taggedDigestOf(file: File, pin: String): String {
    val algorithm = pin.substringBefore(':', "md5")
    val bytes = file.readBytes()
    val digest = when (algorithm) {
        "md5" -> MessageDigest.getInstance("MD5").digest(bytes)
        "sha1" -> MessageDigest.getInstance("SHA-1").digest(bytes)
        // Git hashes "blob <size>", one zero byte, then the content.
        "gitblob" -> MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}".toByteArray() + 0.toByte() + bytes)
        else -> throw GradleException("'$pin' names an algorithm CacheFiles does not read")
    }
    return algorithm + ":" + digest.joinToString("") { "%02x".format(it) }
}

/**
 * What the file must say about the engine bands: each resolved jar's coordinates and SHA-1.
 *
 * Hashed from the file Gradle resolved, so the pin is the exact bytes this build was tested against, and
 * named by coordinates rather than by the resolver's URL, which on a developer's machine may be a local
 * mirror nobody else can reach.
 */
fun resolvedEngineBands(): Map<String, Map<String, String>> =
    listOf("8" to engineBand8, "11" to engineBand11, "17" to engineBand17).associate { (band, configuration) ->
        band to configuration.resolvedConfiguration.resolvedArtifacts.associate { artifact ->
            val id = artifact.moduleVersion.id
            val classifier = artifact.classifier?.let { ":$it" } ?: ""
            val extension = if (artifact.extension == "jar") "" else "@" + artifact.extension
            val coordinates = id.group + ":" + id.name + ":" + id.version + classifier + extension
            if (mavenFile(coordinates) != artifact.file.name) {
                throw GradleException("$coordinates resolved as ${artifact.file.name}, not the name Maven gives it")
            }
            coordinates to taggedDigestOf(artifact.file, "sha1:")
        }.toSortedMap()
    }

/** The "bands" object in the file's own layout, to paste over a stale one. */
fun bandsJson(bands: Map<String, Map<String, String>>): String = buildString {
    appendLine("    \"bands\": {")
    bands.entries.forEachIndexed { b, (band, jars) ->
        appendLine("      \"$band\": {")
        jars.entries.forEachIndexed { j, (coordinates, digest) ->
            appendLine("        \"$coordinates\": \"$digest\"" + (if (j < jars.size - 1) "," else ""))
        }
        appendLine("      }" + (if (b < bands.size - 1) "," else ""))
    }
    append("    }")
}

/** Fetches [url] into [into]: null when it arrived, else why not. [probe] asks for one byte, not all. */
fun fetchInto(url: String, into: File, probe: Boolean = false): String? = try {
    val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 60_000
    connection.instanceFollowRedirects = true
    if (probe) connection.setRequestProperty("Range", "bytes=0-0")
    val status = connection.responseCode
    if (status !in 200..299) {
        "HTTP $status"
    } else {
        connection.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
        if (into.length() == 0L) "an empty body" else null
    }
} catch (unreachable: Exception) {
    unreachable.toString()
}

// Part of `check` through :language's, since a band re-pinned there is what makes the file stale.
tasks.register("checkDownloadLocations") {
    group = "verification"
    description = "Fails if download/locations.json is malformed, or its engines are not the resolved bands."
    doLast {
        val json = readDownloadLocations()
        val offences = mutableListOf<String>()
        if ((json["format"] as? Number)?.toInt() != 1) {
            offences += "'format' must be 1, the layout DownloadLocations reads"
        }
        val self = stringsOf(json["self"])
        if (self.isEmpty()) offences += "'self' is empty, so a released jar could not re-read this file"
        val repositories = membersOf(json["repositories"]).mapValues { stringsOf(it.value) }
        for ((name, layouts) in repositories) {
            layouts.filter { "{path}" !in it && "{file}" !in it }
                .forEach { offences += "repository $name: $it holds neither {path} nor {file}" }
        }
        val lines = try {
            downloadLines(json)
        } catch (malformed: GradleException) {
            offences += malformed.message ?: malformed.toString()
            emptyList()
        }
        for (line in lines) {
            val pin = line.digest
            if (line.urls.isEmpty()) offences += "${line.id} has no address"
            if (line.isTemplate && pin != null) offences += "${line.id} is a template, and a template is never pinned"
            if (line.isTemplate && line.mirrorFile != null) {
                offences += "${line.id} is a template; only a pinned download can be mirrored"
            }
            if (pin != null && !Regex("(md5|sha1|gitblob):[0-9a-f]+").matches(pin)) {
                offences += "${line.id}: '$pin' is not md5:, sha1: or gitblob: over lower-case hex"
            }
        }
        (self + repositories.values.flatten() + lines.flatMap { it.urls }).distinct()
            .filterNot { it.startsWith("https://") }.forEach { offences += "$it is not https" }

        // THE ENGINES ARE THE BANDS, so a re-pin that forgets this file fails here rather than as a download
        // that never matches its pin on somebody else's machine.
        val expected = resolvedEngineBands()
        val listed = membersOf(membersOf(json["engines"])["bands"])
            .mapValues { band -> membersOf(band.value).mapValues { it.value.toString() } }
        if (listed != expected) {
            offences += "the engines are not the resolved bands; replace \"bands\" with:\n" + bandsJson(expected)
        }

        if (offences.isNotEmpty()) {
            throw GradleException("download/locations.json:\n  " + offences.joinToString("\n  "))
        }
        println("download locations OK: ${lines.size} downloads, ${expected.values.sumOf { it.size }} engine jars")
    }
}

tasks.register("verifyDownloadLocations") {
    group = "verification"
    description = "Fetches every address in download/locations.json and checks what it serves. Needs the network."
    doLast {
        val json = readDownloadLocations()
        val self = stringsOf(json["self"])
        // A template's address has no bytes until filled, so it is probed at a version somebody runs.
        val samples = mapOf("{minecraft}" to "1.20.1", "{java}" to "17")
        val file = File(temporaryDir, "fetched")
        val unserved = mutableListOf<String>()
        for (line in downloadLines(json) + DownloadLine("self", null, self, self, null)) {
            var served = 0
            for (template in line.urls) {
                val url = samples.entries.fold(template) { filled, sample -> filled.replace(sample.key, sample.value) }
                val pin = line.digest
                var why = fetchInto(url, file, probe = line.isTemplate)
                if (why == null && pin != null && !taggedDigestOf(file, pin).equals(pin, ignoreCase = true)) {
                    why = "other bytes than the pin"
                }
                file.delete()
                if (why == null) served++ else logger.warn("${line.id}: $url -- $why")
            }
            if (served == 0) unserved += line.id
        }
        if (unserved.isNotEmpty()) throw GradleException("no address serves: " + unserved.joinToString(", "))
        println("every download has an address that serves it; a dead one is warned above")
    }
}

tasks.register("stageDownloadMirror") {
    group = "distribution"
    description = "Collects what the download-mirror release holds into build/download-mirror, verified."
    doLast {
        val directory = layout.buildDirectory.dir("download-mirror").get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        // The bands are already on this machine; only what Gradle never resolved is fetched.
        val resolved = listOf(engineBand8, engineBand11, engineBand17).flatMap { it.resolve() }.associateBy { it.name }
        val rows = mutableListOf<String>()
        for (line in downloadLines(readDownloadLocations())) {
            val name = line.mirrorFile ?: continue
            val pin = line.digest
                ?: throw GradleException("${line.id} is mirrored and pins nothing, so no jar could check the copy")
            val licence = mirrorLicenceOf(name)
                ?: throw GradleException("$name has no entry in mirrorLicences, so it may not be mirrored")
            val target = directory.resolve(name)
            if (target.exists()) {
                if (!taggedDigestOf(target, pin).equals(pin, ignoreCase = true)) {
                    throw GradleException("$name is named by two downloads with different pins")
                }
                continue
            }
            val local = resolved[name]
            if (local != null && taggedDigestOf(local, pin).equals(pin, ignoreCase = true)) {
                local.copyTo(target)
            } else if (line.upstream.none { url ->
                    fetchInto(url, target) == null && taggedDigestOf(target, pin).equals(pin, ignoreCase = true)
                }) {
                throw GradleException("${line.id}: no upstream address serves the pinned bytes")
            }
            rows += "| `$name` | $licence | `${line.id}` |"
        }
        val notice = directory.resolve("NOTICE.md")
        notice.writeText(
            "# download-mirror\n\n" +
            "Unmodified copies of files CrystalGUI downloads at runtime, so a released jar still finds them if\n" +
            "their publisher moves them. Every jar checks each file against the digest it shipped with, so this\n" +
            "release can only ever serve the bytes the publisher did. `download/locations.json` lists them.\n\n" +
            "Each file keeps its own licence.\n\n" +
            "| File | Licence | Id |\n|---|---|---|\n" + rows.joinToString("\n") + "\n")
        val files = directory.listFiles()!!.sorted().joinToString(" ") { "\"" + it.invariantSeparatorsPath + "\"" }
        println("Staged ${rows.size} files in ${directory.invariantSeparatorsPath}. Publishing is yours to run:\n\n" +
            "  gh release create download-mirror --repo CrystalGraphics/CrystalGUI --target master --latest=false " +
            "--title \"Download mirror\" --notes-file \"${notice.invariantSeparatorsPath}\"    (once)\n" +
            "  gh release upload download-mirror --repo CrystalGraphics/CrystalGUI --clobber $files")
    }
}
