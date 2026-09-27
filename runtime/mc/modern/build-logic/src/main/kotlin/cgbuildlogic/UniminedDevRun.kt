package cgbuildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File

/**
 * Makes a Unimined Forge node's `runClient` and `runServer` real dev runs -- Forge below 1.17, on Java 8.
 *
 * ```kotlin
 * if (!stubMode && usesUniminedMinecraft) {
 *     apply(plugin = "xyz.wagyourtail.unimined")
 *     // ...the Unimined minecraft block...
 *     uniminedDevRun()
 * }
 * ```
 *
 * Unimined's own run names this project's source roots and nothing else, so it lacks both `@Mod` classes
 * (`runtime/mc/forge-bootstrap`) and CrystalGraphics as a mod. This groups each mod's roots in
 * `MOD_CLASSES`, as `crystalgraphics-run.gradle.kts` does for ModDevGradle -- the common nodes included,
 * since a class that names Minecraft must be FML's to define -- and swaps every class root of
 * ours newer than Java 8 -- the nodes compile to 17 -- for its Java 8 copy. @see DevRunDowngrade
 *
 * - Resources first for each mod: this FML reads a mod's `mods.toml` from the first root named for it.
 * - Every root is on the classpath too: below 1.17 there is no module layer, and `MOD_CLASSES` only says
 *   which classpath entries make up which mod.
 * - Runs in `runs/<side>`, the directory `serverSmoke` writes its EULA and port into on every loader.
 */
fun Project.uniminedDevRun() {
    val graphics = gradle.includedBuild("CrystalGraphics")
    val graphicsLoaderPath = sameVersionNodePath(modernLoader)
    val graphicsLoaderDir = sameVersionNodeDir(graphics.projectDir, modernLoader)
    val graphicsCommonDir = sameVersionNodeDir(graphics.projectDir, "common")
    val bootstrap = project(":runtime:mc:forge-bootstrap")
    val common = commonNode
    val withLanguage = !providers.gradleProperty("cgNoLanguage").isPresent
    val sourceSets = extensions.getByType<SourceSetContainer>()

    val mods = linkedMapOf(
        "crystalgui" to roots(sourceSets["main"]) + roots(sourceSetsOf(common)["main"]) +
            sourceSetsOf(bootstrap)["main"].output.classesDirs.files,
        "crystalgraphics" to nodeRoots(graphicsLoaderDir) + nodeRoots(graphicsCommonDir) +
            File(graphics.projectDir, "runtime/mc/forge-bootstrap/build/classes/java/main"),
    )
    if (withLanguage) {
        mods["crystalgui_language"] = roots(sourceSets["lang"]) + roots(sourceSetsOf(common)["lang"]) +
            sourceSetsOf(bootstrap)["lang"].output.classesDirs.files
    }
    val modRoots = mods.values.flatten()
    dependencies.add("runtimeOnly", files(modRoots.filter { !it.startsWith(projectDir) }))
    // Minecraft ships JOML from 1.19.3 and every node here is below 1.17; the shipped jar's companion
    // supplies it, and a dev run takes it as a library, as the other loaders' runs do.
    dependencies.add("runtimeOnly", "org.joml:joml-jdk8:1.10.1")

    val runtime = files(sourceSets["main"].runtimeClasspath, sourceSets["lang"].runtimeClasspath, modRoots)
    val downgrade = tasks.register<DevRunDowngrade>("downgradeDevRun") {
        roots.from(runtime)
        classpath.from(runtime)
        // The roots named as plain directories carry no producer of their own.
        dependsOn("${bootstrap.path}:classes", "${common.path}:langClasses")
        if (withLanguage) dependsOn("${bootstrap.path}:langClasses")
        dependsOn(graphics.task("$graphicsLoaderPath:classes"), graphics.task(":runtime:mc:forge-bootstrap:classes"),
            graphics.task("${sameVersionNodePath("common")}:classes"), "${common.path}:classes")
    }

    tasks.withType<JavaExec>().matching { it.name in setOf("runClient", "runServer") }.configureEach {
        workingDir = file(if (name == "runServer") "runs/server" else "runs/client")
        dependsOn(downgrade)
        doFirst {
            val copies = downgrade.get()
            // The common nodes' JARS go: they are in their mods' roots, and a copy left as a library is
            // defined by the app loader, which then loads its own Minecraft beside FML's.
            val commonJars = listOf(File(common.projectDir, "build/libs"), File(graphicsCommonDir, "build/libs"))
            classpath = files(classpath.files.filterNot { jar -> commonJars.any { jar.startsWith(it) } }
                .map(copies::copyOf) + copies.api())
            // FML splits MOD_CLASSES on the PLATFORM's path separator.
            environment("MOD_CLASSES", mods.flatMap { (id, dirs) -> dirs.map { "$id%%${copies.copyOf(it)}" } }
                .joinToString(File.pathSeparator))
        }
    }
}

private fun sourceSetsOf(owner: Project) = owner.extensions.getByType<SourceSetContainer>()

/** A node's resources and classes in another build, where only its output LAYOUT is reachable. */
private fun nodeRoots(nodeDir: File) = listOf(File(nodeDir, "build/resources/main"), File(nodeDir, "build/classes/java/main"))

private fun roots(sourceSet: SourceSet): List<File> =
    listOfNotNull(sourceSet.output.resourcesDir) + sourceSet.output.classesDirs.files
