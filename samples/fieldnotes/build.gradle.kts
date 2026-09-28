// The root: what the mod says about itself, and the merge of every node's thin jar with the core into the
// one jar every loader installs. Nothing here names a node: they are read off the tree, so a version added
// to `targets {}` is described, merged and checked with no edit to this file.
import cgbuildlogic.Dependency
import cgbuildlogic.LoaderEntries
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Ordering
import cgbuildlogic.SingleJarSpec
import cgbuildlogic.modernLoaderNodes
import cgbuildlogic.modernNodes
import cgbuildlogic.modernVariants
import cgbuildlogic.registerCheckAllTargets
import java.util.Properties
import cgbuildlogic.registerDescriptorTasks
import cgbuildlogic.registerSingleJarPipeline
import cgbuildlogic.shippedEntryPaths
import cgbuildlogic.thinJarTask

plugins {
    java                                        // not `base`: jvmdg's downgrade reads sourceSets
    id("com.gradleup.shadow")
    id("xyz.wagyourtail.jvmdowngrader")
}

tasks.named<Jar>("jar") { enabled = false }     // nothing compiles at the root
group = property("modGroup").toString()
version = property("modVersion").toString()

jvmdg.defaultShadeTask { enabled = false }      // jvmdg's conventions are for a module that compiles
jvmdg.defaultTask { enabled = false }
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

repositories { mavenCentral() }

val modId = property("modId").toString()

/** The clone this sample sits in; a project of its own writes the CrystalGUI release it requires. */
val crystalGuiVersion: String = Properties()
    .apply { rootDir.resolve("../../gradle.properties").reader().use(::load) }
    .getProperty("modVersion")

val descriptor = ModDescriptor(
    id = modId,
    name = "Field Notes",
    version = project.version.toString(),
    description = "A CrystalGUI window, from one jar on every loader and version it targets.",
    license = "MIT",
    dependencies = listOf(Dependency("crystalgui", "[$crystalGuiVersion,)", ordering = Ordering.AFTER)),
    // One variant per loader node, its range and pack format from the node's catalog pins. Entries at
    // their SOURCE names; each node ships them relocated into its own package.
    variants = modernVariants(project, mapOf(
        "forge" to LoaderEntries("com.example.fieldnotes.mc.forge",
            common = "com.example.fieldnotes.mc.forge.FieldNotesForge"),
        "neoforge" to LoaderEntries("com.example.fieldnotes.mc.neoforge",
            common = "com.example.fieldnotes.mc.neoforge.FieldNotesNeoForge"),
        "fabric" to LoaderEntries("com.example.fieldnotes.mc.fabric",
            common = "com.example.fieldnotes.mc.fabric.FieldNotesFabric",
            // Fabric reads no `dependencies`; its depends are its own.
            fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "crystalgui" to "*")),
    )),
    // Fabric constructs every entry its descriptor names; Forge and NeoForge find their @Mod by scanning.
    bootstrappers = mapOf("fabric" to "com.example.fieldnotes.mc.fabric.FabricBootstrap"),
)

// No per-loader descriptors to hold it to: every node takes the merged ones.
registerDescriptorTasks(descriptor, modId, checkShipped = false)
// Each node's dev run reads its own variant from it.
extra["fieldnotesDescriptor"] = descriptor

val bootstrappers = listOf(
    "com/example/fieldnotes/mc/forge/ForgeBootstrap.class",
    "com/example/fieldnotes/mc/neoforge/NeoForgeBootstrap.class",
    "com/example/fieldnotes/mc/fabric/FabricBootstrap.class",
)

registerSingleJarPipeline(SingleJarSpec(
    modId = modId,
    fileName = "$modId-${project.version}.jar",
    shadePath = "com/example/fieldnotes/shadow",
    thinJars = modernLoaderNodes(project).map { it.path to thinJarTask(it, "thinShadowJar") },
    libraryProjects = listOf(":core"),
    serviceOwners = listOf(":core"),
    manifest = mapOf(
        "Implementation-Version" to project.version.toString(),
        "Automatic-Module-Name" to modId,
    ),
    fabricThinJar = modernNodes(project, "fabric").firstOrNull()?.let { it.path to thinJarTask(it, "thinShadowJar") },
    configureCheck = {
        // CrystalGUI and CrystalGraphics are mods of their own; a copy here is a split package.
        forbiddenPrefixes.set(listOf("META-INF/versions/", "com/crystalgui/", "com/crystalgraphics/"))
        expectSingle.set(listOf("com/example/fieldnotes/FieldNotes"))
        relocatedClasses.set(mapOf("com/example/fieldnotes/mc/modern/Game.class" to modernLoaderNodes(project).size))
        requiredEntries.set(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "fabric.mod.json",
            "pack.mcmeta", "META-INF/$modId/variants.json") + bootstrappers + descriptor.shippedEntryPaths())
        expectServices.set(mapOf("com.crystalgui.desktop.app.ApplicationKinds" to "com.example.fieldnotes.FieldNotesKinds"))
    },
))

// Every node compiled: `./gradlew checkAllTargets` before a commit.
registerCheckAllTargets()
