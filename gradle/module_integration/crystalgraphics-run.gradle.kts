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
/** tree-sitter, which :language needs and which must live in the same module it does. */
val treeSitterJars: List<File> = rootProject.file("lib/tree-sitter").listFiles()
    ?.filter { it.name.endsWith(".jar") }?.sorted() ?: emptyList()

val modClassesValue = (
    listOf(mainSourceSet(project), mainSourceSet(project(":core")), mainSourceSet(project(":mc1201:common")),
           mainSourceSet(project(":language")))
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
