package cgbuildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The Stonecutter tree at `:runtime:mc:modern` — one BRANCH per loader, one NODE per Minecraft version
 * that loader targets — and the only place in the build that knows how to find a node.
 *
 * A node is `:runtime:mc:modern:<loader>:<version>`, so on a node `project.name` is the VERSION and the
 * branch is the loader. Everything that needs a node asks here, which is what makes adding a version a
 * `versions(...)` line in settings.gradle.kts plus that node's `gradle.properties`, and nothing else.
 *
 * ```kotlin
 * project.modernLoader              // "forge", on :runtime:mc:modern:forge:1.20.1
 * project.commonNode                // :runtime:mc:modern:common:1.20.1, for any 1.20.1 node
 * modernNodes(project, "fabric")    // every fabric node, oldest version first
 * modernLoaderNodes(project)        // every node that ships a thin jar
 * ```
 *
 * - `commonNode` throws for a version `common` has no node for. A loader node must never borrow a
 *   common compiled against another Minecraft: it compiles, and tests nothing.
 * - Reading a node's TASKS from another project needs that project evaluated first —
 *   `evaluationDependsOn(commonNode.path)`. Listing nodes does not: the hierarchy exists before any
 *   project is configured.
 */
const val MODERN_TREE = ":runtime:mc:modern"

/** The loaders that ship a thin jar, in merge order. `common` is a branch and not a loader. */
val MODERN_LOADERS = listOf("forge", "neoforge", "fabric")

/** The branch this node belongs to: its loader, or `common`. */
val Project.modernLoader: String
    get() = parent?.takeIf { it.parent?.path == MODERN_TREE }?.name
        ?: throw GradleException("$path is not a node of $MODERN_TREE")

/** The common node built against this node's Minecraft. */
val Project.commonNode: Project
    get() = rootProject.findProject("$MODERN_TREE:common:$name")
        ?: throw GradleException(
            "$path has no common node: add \"$name\" to the common branch in settings.gradle.kts. "
                + "A loader node may not compile against a common built for another Minecraft.")

/** Every node of one branch, oldest Minecraft first. Empty when the branch is absent (embedded builds). */
fun modernNodes(project: Project, branch: String): List<Project> =
    project.rootProject.findProject("$MODERN_TREE:$branch")?.childProjects?.values
        ?.sortedWith(compareBy(MinecraftVersionOrder) { it.name })
        .orEmpty()

/** Every node that ships a thin jar: each loader's nodes, in [MODERN_LOADERS] order. */
fun modernLoaderNodes(project: Project): List<Project> = MODERN_LOADERS.flatMap { modernNodes(project, it) }

/**
 * A node's Maven coordinates: group per BRANCH, name per version.
 *
 * Load-bearing, not tidy. Every node of a version shares a project name, so with one group for the
 * whole tree `forge:1.20.1` and `common:1.20.1` are both `com.crystalgui:1.20.1`, Gradle resolves the
 * loader's dependency on common to the loader itself, and the build fails on `compileJava` depending
 * on `compileJava`. That is what reverted J11.1a's first attempt.
 */
fun Project.useNodeCoordinates() {
    group = "${property("modGroup")}.mc.modern.$modernLoader"
    version = property("modVersion").toString()
}

/** `1.20.1` < `1.20.4` < `1.21`: numeric per component, a missing component counting as zero. */
object MinecraftVersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val c = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
