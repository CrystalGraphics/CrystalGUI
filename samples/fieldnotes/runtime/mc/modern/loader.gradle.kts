// What every loader node does alike, applied by each loader branch after its toolchain: what it compiles
// against, its thin jar and what that may hold, and its dev run. The branch then renames the thin jar to
// the names its loader runs, and sets `checkThinJar.jar` to the result.
import cgbuildlogic.CheckThinJar
import cgbuildlogic.DEV_DESCRIPTORS
import cgbuildlogic.ModDescriptor
import cgbuildlogic.commonNode
import cgbuildlogic.configureStubs
import cgbuildlogic.modernLoader
import cgbuildlogic.nodeJava
import cgbuildlogic.nodeLibrary
import cgbuildlogic.nodePackage
import cgbuildlogic.registerNodeDevRun
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/** The common node of THIS node's Minecraft. */
val common: Project = project.commonNode
evaluationDependsOn(common.path)

dependencies {
    "compileOnly"(project(common.path))
    "compileOnly"(project(":core"))
}
// VariantBootstrap and ForgeStart: CrystalGraphics' jar carries them at run time, for every mod. A node
// library, so the stub check keeps it rather than taking it for the toolchain's.
nodeLibrary("com.crystalgraphics:mc-shared:1.0.0")

// Everything a node ships lives under its own package, so every node of every loader fits in one jar:
// common goes to `<node>.common`, the loader's classes to `<node>`. Except the bootstrapper, the one class
// its loader constructs whatever version runs -- one name for all nodes, and the merge keeps one copy.
val loaderPackage = "com.example.fieldnotes.mc.$modernLoader"
val nodeRoot = nodePackage(loaderPackage, project.name)
val bootstrapper = "$loaderPackage." + mapOf("forge" to "Forge", "neoforge" to "NeoForge", "fabric" to "Fabric")
    .getValue(modernLoader) + "Bootstrap"

val main = the<SourceSetContainer>()["main"]
tasks.register<ShadowJar>("thinShadowJar") {
    group = "build"
    description = "This loader plus its common node, relocated into this node's package: the merge's input, at dev names."
    archiveClassifier.set("thin-dev")
    configurations = emptyList()
    from(main.output)
    exclude(DEV_DESCRIPTORS)                            // the merge writes those once
    val commonJar = common.tasks.named<Jar>("jar")
    from(commonJar.map { zipTree(it.archiveFile) })
    relocate("com.example.fieldnotes.mc.modern", "$nodeRoot.common")
    relocate(loaderPackage, nodeRoot) { exclude(bootstrapper) }
}

tasks.register<CheckThinJar>("checkThinJar") {
    allowedPrefixes.set(listOf("com/example/fieldnotes/mc/"))
    // The core, CrystalGUI and CrystalGraphics enter the merge once, or not at all.
    forbiddenPrefixes.set(listOf("com/crystalgui/", "com/crystalgraphics/"))
    maxClassMajor.set(nodeJava + 44)
    logTag.set("fieldnotes")
}
tasks.named("check") { dependsOn("checkThinJar") }

// `runClient` and `runServer`, on a node whose toolchain has them: this node, its common node and the core
// as one mod. CrystalGUI's and CrystalGraphics' jars are put on the run by `com.crystalgui`.
registerNodeDevRun(rootProject.extra["fieldnotesDescriptor"] as ModDescriptor, bundled = listOf(project(":core")))

configureStubs()
