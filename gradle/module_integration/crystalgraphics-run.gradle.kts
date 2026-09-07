// Puts CrystalGraphics into a ModDevGradle dev run -- applied from mc1201/forge and mc1201/neoforge,
// which are the same mechanism aimed at different Minecraft versions. Fabric is NOT here: Loom's Knot
// classloader does not delegate com.crystalgraphics.*, so that loader bundles jars instead.
//
// Two layers, and which one a module belongs on is decided by whether it touches Minecraft.
//
// LIBRARY -- integration.gradle.kts declares CrystalGraphics compileOnly + runtimeOnly and says in its
// own comment that ModDevGradle dev runs ignore runtimeClasspath, so none of it reached a run:
// CgNetworkChannel's clinit died on NoClassDefFoundError for com/crystalgraphics/platform/CgService.
// build/moddev/clientLegacyClasspath.txt is where to check that. Declared as real dependencies,
// because resolving one BUILDS it -- a bare file path serves whatever happens to sit on disk.
//
// MOD -- CrystalGraphics registers CgPlatform from its own @Mod constructor and nothing in CrystalGUI
// does it, so as a library alone every class resolves and the desktop paints nothing. mods{} cannot
// declare it: sourceSet() takes a SourceSet and CrystalGraphics is a separate build. FML's other door
// is MOD_CLASSES, which is what ModDevGradle fills in from mods{} anyway.
//
// Nothing may appear on both: the exploded directory and the jar export the same packages, and
// BootstrapLauncher fails the launch over the split package.

import java.io.File

// "forge" or "neoforge" -- CrystalGraphics lays its loaders out under the same names.
val loader = project.name
val crystalGraphics = gradle.includedBuild("CrystalGraphics")

dependencies {
    add("additionalRuntimeClasspath", project(":taffy"))
    add("additionalRuntimeClasspath", "com.crystalgraphics:core:1.0.0")
    add("additionalRuntimeClasspath", "com.crystalgraphics:platform:1.0.0")
    add("additionalRuntimeClasspath", "com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings:1.0.0")
}

/** Classes and resources are separate roots to FML, and a mod needs both -- mods.toml is a resource. */
fun modClasses(modId: String, roots: Iterable<File>) = roots.map { "$modId%%$it" }

fun modClasses(modId: String, sourceSet: SourceSet) = modClasses(modId,
    sourceSet.output.classesDirs.files + listOfNotNull(sourceSet.output.resourcesDir))

/** The same for a module in another build, where only the output LAYOUT is reachable from here. */
fun modClasses(modId: String, moduleDir: File) = modClasses(modId, listOf(
    File(moduleDir, "build/classes/java/main"), File(moduleDir, "build/resources/main")))

fun mainSourceSet(project: Project) =
    project.extensions.getByType(SourceSetContainer::class.java)["main"]

// Setting MOD_CLASSES REPLACES what ModDevGradle derived from mods{} rather than adding to it, so the
// crystalgui half names the same source sets that block does. THE TWO LISTS ARE MAINTAINED BY HAND AND
// NOTHING CHECKS THEM AGAINST EACH OTHER: a source set added to mods{} alone is silently dropped here,
// and presents at runtime as a NoClassDefFoundError for a class that is compiled, declared and on disk.
// :language was added to mods{} first and cost four launches proving every other link was sound.
//
// EVERYTHING :language NEEDS AT RUNTIME BELONGS IN THIS MODULE TOO. The library classpath below is a
// different layer and the mod's own classloader does not reach it: LanguageRegistry's ServiceLoader
// found nothing there, and tree-sitter loaded but was invisible to the grammars that call it. What may
// NOT come along is :core, which :language depends on and which is already a root here -- one package
// in two modules and the JVM refuses the layer outright, naming com.crystalgui.core.nav.
/**
 * ONE merged META-INF/services root, read before the others.
 *
 * :core and :language each ship a WorkbenchExtension service file, and FML's ModuleClassLoader answers
 * a resource path from ONE root -- so two roots of one module means one file wins and the other's
 * providers are silently absent. It presented as every CrystalEditor extension being offline except the
 * one :language contributes. Merging by hand and putting the result first is the only lever a dev run
 * has; the shipped jar uses ShadowJar's own mergeServiceFiles.
 */
val mergedServicesDir: File = layout.buildDirectory.dir("merged-services").get().asFile

val mergeDevServices = tasks.register("mergeDevServices") {
    group = "build"
    description = "Unions :core's and :language's META-INF/services so neither shadows the other."
    outputs.dir(mergedServicesDir)
    doLast {
        val sources = listOf(project(":core"), project(":mc1201:common"), project(":language"))
            .map { owner -> File(mainSourceSet(owner).output.resourcesDir, "META-INF/services") }
            .filter { it.isDirectory }
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
        val out = File(mergedServicesDir, "META-INF/services")
        out.mkdirs()
        out.listFiles().orEmpty().forEach { it.delete() }
        byService.forEach { (service, providers) ->
            File(out, service).writeText(buildString {
                appendLine("# Merged for the dev run by mergeDevServices; see its declaration.")
                providers.forEach { appendLine(it) }
            })
        }
    }
}

/** tree-sitter, which :language needs and which must live in the same module it does. */
val treeSitterJars: List<File> = rootProject.file("lib/tree-sitter").listFiles()
    ?.filter { it.name.endsWith(".jar") }?.sorted() ?: emptyList()

val modClassesValue = (
    // The merged services FIRST, so it is the root that answers for META-INF/services.
    modClasses("crystalgui", listOf(mergedServicesDir))
        + listOf(mainSourceSet(project), mainSourceSet(project(":core")),
                 mainSourceSet(project(":mc1201:common")), mainSourceSet(project(":language")))
            .flatMap { modClasses("crystalgui", it) }
        + modClasses("crystalgui", treeSitterJars)
        + modClasses("crystalgraphics", File(crystalGraphics.projectDir, "mc1201/common"))
        + modClasses("crystalgraphics", File(crystalGraphics.projectDir, "mc1201/$loader"))
    ).joinToString(";")

// A directory named in MOD_CLASSES is read at launch with nothing in the task graph behind it, so
// whatever sits there is what runs. prepareClientRun is included because that is the task an IDE
// launch runs -- it then starts the JVM itself, so a dependency only on runClient never fires for it.
tasks.matching {
    it.name in setOf("runClient", "runServer", "prepareClientRun", "prepareServerRun")
}.configureEach {
    // AND THE LIST ITSELF IS AN INPUT, or the prepare tasks stay UP-TO-DATE across a change to it and
    // the launch reads the folder list from the last build that happened to re-run them. Adding a root
    // then costs a `--rerun` nobody knows to type, and the symptom is the change simply not being there:
    // an editor missing every extension whose service file lost, on a build that compiled and copied
    // everything correctly.
    inputs.property("cgModClasses", modClassesValue)
    dependsOn(mergeDevServices)
    dependsOn(crystalGraphics.task(":mc1201:common:classes"))
    dependsOn(crystalGraphics.task(":mc1201:$loader:classes"))

    // And the jars the runtime classpath is made of. These arrive as substituted coordinates, which
    // ModDevGradle resolves with nothing ordering them before the launch -- so a jar could still be
    // mid-rewrite when the JVM reads it, presenting as a NoClassDefFoundError for a class that is in it.
    dependsOn(crystalGraphics.task(":core:jar"))
    dependsOn(crystalGraphics.task(":platform:jar"))
    dependsOn(crystalGraphics.task(":freetype-msdfgen-harfbuzz-bindings:jar"))
}

tasks.matching { it.name in setOf("runClient", "runServer") }.configureEach {
    // RunGameTask is ModDevGradle-internal, so its property is reached by reflection, as
    // integration.gradle.kts reaches RunMinecraftTask's. Loud when the method is gone: a mod that
    // quietly fails to load is indistinguishable from one that loaded and did nothing.
    val environmentProperty = javaClass.methods
        .firstOrNull { it.name == "getEnvironmentProperty" && it.parameterCount == 0 }
        ?: throw GradleException(
            "$path is a ${javaClass.name} with no getEnvironmentProperty(); ModDevGradle has moved "
                + "MOD_CLASSES and CrystalGraphics would launch as a library rather than a mod.")

    @Suppress("UNCHECKED_CAST")
    (environmentProperty.invoke(this) as MapProperty<String, String>)
        .put("MOD_CLASSES", modClassesValue)
}
