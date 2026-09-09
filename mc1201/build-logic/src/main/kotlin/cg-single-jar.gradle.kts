import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import java.util.jar.JarFile

// ── One jar for every loader (J4) ────────────────────────────────────────────────────────────────
//
// Four thin jars, each this loader's own classes at its own production names, plus the engine and its
// libraries ONCE. Every loader reads its own descriptor, constructs its own entry class, and never
// defines a class from another variant -- so those classes' references to a Minecraft it is not
// running are never resolved.
//
// The order is singleShadowJar -> downgrade -> shade -> singleJar, and it is not interchangeable:
// jvmdg rewrites bytecode and adds references to its own stubs, so the stubs have to be shaded in
// AFTER the rewrite. Remapping already happened per thin jar, which is the one inversion from the fat
// chain and is safe because jvmdg does not read names.

plugins {
    // The root coordinates rather than compiles, so it has no `assemble` of its own until this.
    // `java` rather than `base`, because jvmdg's DowngradeJar reads `sourceSets` when it is created
    // -- for a default classpath this task overrides anyway.
    java
    id("com.gradleup.shadow")
    id("xyz.wagyourtail.jvmdowngrader")
}

// Nothing compiles here. The java plugin's own jar would be an empty artifact beside the real one.
tasks.named<Jar>("jar") { enabled = false }

// The root carries no version of its own, and the artifact is named after it.
group = property("modGroup").toString()
version = property("modVersion").toString()

// jvmdg's own conventions are for a module that compiles; this project compiles nothing.
jvmdg.defaultShadeTask { enabled = false }
jvmdg.defaultTask { enabled = false }
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

repositories {
    mavenCentral()
}

/**
 * Third-party libraries the merged jar carries, relocated.
 *
 * fastutil is Taffy's and is RELOCATED: nothing outside this jar sees those types, and Minecraft
 * 1.20.x ships its own copy that a second unrelocated one would collide with as a split package.
 * 1.7.10 has neither library, so the union ships and the relocation makes it safe everywhere.
 */
val singleJarLibs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    singleJarLibs("it.unimi.dsi:fastutil:${property("fastutil_version")}")
}

/**
 * ONE merged `META-INF/services`, unioned rather than copied.
 *
 * `core` and `language` each ship a `WorkbenchExtension` file, and a copy keeps whichever arrived
 * first while silently dropping the other -- the jar then carries eight extensions and loses the Run
 * panel, with nothing reporting it. Shadow's own `mergeServiceFiles` does not help: these arrive
 * through `from(zipTree(...))` and the duplicate is dropped before any transformer sees it.
 */
val singleJarServiceOwners = listOf(project(":core"), project(":language"))
val singleJarServicesDir = layout.buildDirectory.dir("single-jar/services").get().asFile

val mergeSingleJarServices = tasks.register("mergeSingleJarServices") {
    group = "build"
    description = "Unions every bundled module's META-INF/services so none shadows another."
    val sources = singleJarServiceOwners.mapNotNull { owner ->
        owner.extensions.getByType(SourceSetContainer::class.java)["main"].output.resourcesDir
            ?.let { File(it, "META-INF/services") }
    }
    // THE FILES IT READS, or it compares on outputs alone and stays UP-TO-DATE across an edit to one
    // of them, leaving a merged copy that describes the previous build.
    inputs.files(sources).withPropertyName("serviceDirs").optional(true)
    outputs.dir(singleJarServicesDir)
    dependsOn(singleJarServiceOwners.map { "${it.path}:processResources" })
    val out = singleJarServicesDir
    doLast {
        val byService = linkedMapOf<String, MutableList<String>>()
        sources.filter { it.isDirectory }.forEach { directory ->
            directory.listFiles().orEmpty().forEach { file ->
                val providers = byService.getOrPut(file.name) { mutableListOf() }
                file.readLines().map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() && it !in providers }
                    .forEach { providers.add(it) }
            }
        }
        val dir = File(out, "META-INF/services")
        dir.mkdirs()
        dir.listFiles().orEmpty().forEach { it.delete() }
        byService.forEach { (service, providers) ->
            File(dir, service).writeText(buildString {
                appendLine("# Unioned by mergeSingleJarServices; see its declaration.")
                providers.forEach { appendLine(it) }
            })
        }
    }
}

// Captured at SCRIPT scope: inside a task-configuration block `property(...)` resolves against the
// TASK, not the project, and fails with "unknown property 'modId'".
val singleJarModId = property("modId").toString()
val singleJarFileName = "$singleJarModId-${project.version}.jar"

/** The four thin jars, each already at its loader's production names. */
// Named per loader because each toolchain names its own production step: ModDevGradle's
// `reobfuscate` derives `reobfThinShadowJar` from the task it consumes, Loom's remap task is the one
// registered by name, NeoForge needs no mapping step at all, and the 1.7.10 one is registered here.
val singleJarThinJars = listOf(
    ":mc1710" to "reobfThinJar",
    ":mc1201:forge" to "reobfThinShadowJar",
    ":mc1201:neoforge" to "thinShadowJar",
    ":mc1201:fabric" to "remapThinJar",
)

/** Compiled once, shipped once: the engine and everything under it. */
val singleJarLibraryProjects = listOf(":core", ":language", ":taffy", ":mc-shared")

val singleShadowJar = tasks.register<ShadowJar>("singleShadowJar") {
    group = "build"
    description = "Four loaders and one engine in one jar, before downgrading."
    archiveClassifier.set("merged")
    configurations = emptyList()
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))

    singleJarThinJars.forEach { (path, task) ->
        val jar = project(path).tasks.named<AbstractArchiveTask>(task)
        dependsOn(jar)
        from(jar.map { zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
    }
    singleJarLibraryProjects.forEach { path ->
        val jar = project(path).tasks.named<Jar>("jar")
        dependsOn(jar)
        from(jar.map { zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
    }
    from(mergeSingleJarServices)

    // The tree-sitter jars go in VERBATIM and are never relocated: each carries the JNI natives for
    // its grammar, and a JNI symbol is named after the mangled package -- renaming it renames the
    // symbol the .dll does not export, and the first parser built throws UnsatisfiedLinkError.
    rootProject.file("lib/tree-sitter").listFiles()
        ?.filter { it.name.endsWith(".jar") }
        ?.sortedBy { it.name }
        ?.forEach { from(zipTree(it)) }

    singleJarLibs.forEach { from(zipTree(it)) }
    relocate("dev.vfyjxf.taffy", "com.crystalgui.shadow.dev.vfyjxf.taffy")
    relocate("it.unimi.dsi.fastutil", "com.crystalgui.shadow.it.unimi.dsi.fastutil")

    // JOML: THE SAME REWRITE CrystalGraphics APPLIES, over no classes of ours.
    //
    // CrystalGraphics ships the one copy, relocated, because a jar containing `org/joml` is a split
    // package against Minecraft's own module on Forge and NeoForge and 1.7.10 has no JOML at all. Its
    // types cross the boundary between the two mods -- `UINode` and `ElementStyle` hold `Matrix4f`
    // FIELDS -- so this jar's references have to be rewritten to the same name or they are two
    // unrelated types. Safe because no loader host of ours names JOML, so nothing hands a matrix to
    // or from Minecraft. J9 replaces both with a vendored `com.crystalgraphics.math`.
    relocate("org.joml", "com.crystalgraphics.shadow.org.joml")

    // Both bands: 8 is what a 1.7.10 client runs and 17 what 1.20.x does, and one jar serves both.
    // Taken from the two loaders that already bundle them rather than re-resolved here.
    listOf(":mc1710" to "bundleEngineBands", ":mc1201:forge" to "bundleEngineBands",
           ":mc1710" to "writeEngineManifests", ":mc1201:forge" to "writeEngineManifests")
        .forEach { (path, task) ->
            val producer = project(path).tasks.named(task)
            dependsOn(producer)
            from(producer)
        }

    // The descriptors every loader reads, printed from one declaration.
    val descriptors = tasks.named("generateMergedDescriptors")
    dependsOn(descriptors)
    from(descriptors)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // What each loader reads out of the manifest, in one manifest.
    //
    // FML 1.7.10 needs all four of its lines: a jar carrying a TweakClass is a coremod unless
    // ForceLoadAsMod says otherwise, and MixinConfigs is its ONLY channel for a mixin config.
    // ModLauncher reads MixinConfigs too, which is exactly why the config plugin gates by loader.
    manifest {
        attributes(
            "FMLCorePluginContainsFMLMod" to true,
            "ForceLoadAsMod" to true,
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.crystalgui.json",
            // mods.toml says ${file.jarVersion}, which FML reads from here.
            "Implementation-Version" to project.version.toString(),
            "Automatic-Module-Name" to singleJarModId,
        )
    }

    // Loom's own attributes, copied from the fabric thin jar rather than restated: they describe how
    // that jar was remapped -- eleven of them, measured in J0 -- and a Loom upgrade that adds a
    // twelfth is carried without an edit. The PREFIX is copied, never a list. `Multi-Release` is
    // deliberately not among them: it would put modern classes under META-INF/versions, where FML
    // 1.7.10's scanner reads them and calls the jar corrupt.
    val fabricThin = project(":mc1201:fabric").tasks.named<AbstractArchiveTask>("remapThinJar")
    doFirst {
        JarFile(fabricThin.get().archiveFile.get().asFile).use { jar ->
            jar.manifest?.mainAttributes?.forEach { key, value ->
                val name = key.toString()
                if (name.startsWith("Fabric-")) manifest.attributes[name] = value.toString()
            }
        }
    }
}

val downgradeSingleJar = tasks.register<DowngradeJar>("downgradeSingleJar") {
    group = "build"
    description = "Rewrites every class in the merged jar to Java 8."
    dependsOn(singleShadowJar)
    inputFile.set(singleShadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    archiveClassifier.set("merged-java8")
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))
}

val shadeSingleJar = tasks.register<ShadeJar>("shadeSingleJar") {
    group = "build"
    description = "Bundles the jvmdg runtime stubs the downgrade now references."
    inputFile.set(downgradeSingleJar.flatMap { it.archiveFile })
    // NAMED, never defaulted: the default is the archive base name, and a hyphenated one is not a
    // legal package identifier, so the module system rejects the jar before the early display.
    shadePath.set({ _: String -> "com/crystalgui/shadow" })
    archiveClassifier.set("merged-java8-shaded")
    destinationDirectory.set(layout.buildDirectory.dir("single-jar"))
}

/**
 * The artifact. Unclassified, because it is the product rather than a stage of one.
 *
 * A Copy rather than another Jar: the bytes are finished, and re-zipping them would change the hash
 * for no reason -- which the reproducibility check would then report as non-determinism.
 */
val singleJar = tasks.register<Copy>("singleJar") {
    group = "build"
    description = "The one jar every loader installs."
    dependsOn(shadeSingleJar)
    from(shadeSingleJar.map { it.archiveFile })
    into(layout.buildDirectory.dir("libs"))
    val fileName = singleJarFileName
    rename { fileName }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    // The same bytes twice, so a jar's hash means something: update detection, the engine manifests'
    // digests, and the reproducibility check below all rest on it.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val checkSingleJar = tasks.register<cgbuildlogic.CheckSingleJar>("checkSingleJar") {
    dependsOn(singleJar)
    jar.set(layout.buildDirectory.file("libs/$singleJarFileName"))
    classMajorCeiling.set(52)
    forbiddenPrefixes.set(listOf(
        "META-INF/versions/",
        // Unrelocated, these are split packages against Minecraft's own modules on Forge and NeoForge,
        // which fails module resolution before a single mod class loads.
        "it/unimi/dsi/fastutil/", "dev/vfyjxf/taffy/",
    ))
    expectSingle.set(listOf("com/crystalgui/ui/", "com/crystalgui/language/"))
    relocatedClasses.set(mapOf(
        "com/crystalgui/mc/platform/Lifecycle1201.class" to 3,
        "com/crystalgui/mc/client/CgUiScreen1201.class" to 3,
    ))
    requiredEntries.set(listOf(
        "META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta",
        "mixins.crystalgui.json",
        "com/crystalgui/mc/shared/LoaderProbe.class",
        "com/crystalgui/mixins/CrystalGuiMixins.class",
    ))
    requiredManifest.set(mapOf(
        "FMLCorePluginContainsFMLMod" to "true",
        "ForceLoadAsMod" to "true",
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.crystalgui.json",
        "Fabric-Loom-Mixin-Remap-Type" to "",
    ))
    expectServices.set(mapOf(
        "com.crystalgui.workbench.extension.WorkbenchExtension" to "com.crystalgui.language",
        "com.crystalgui.text.syntax.LanguageKinds" to "com.crystalgui.language.LanguageStack",
    ))
}

tasks.named("assemble") { dependsOn(singleJar) }
tasks.register("checkSingle") {
    group = "verification"
    description = "Builds the single jar and asserts everything four loaders need of it."
    dependsOn(checkSingleJar)
}
