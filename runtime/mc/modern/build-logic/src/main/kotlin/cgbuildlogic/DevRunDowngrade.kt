package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.jvmdg.ClassDowngrader
import xyz.wagyourtail.jvmdg.compile.PathDowngrader
import xyz.wagyourtail.jvmdg.compile.ZipDowngrader
import xyz.wagyourtail.jvmdg.gradle.flags.DowngradeFlags
import xyz.wagyourtail.jvmdg.gradle.flags.convention
import xyz.wagyourtail.jvmdg.gradle.flags.toFlags
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Java 8 copies of a Java 8 dev run's class roots: every root of this build in [roots] holding a class newer
 * than Java 8 is downgraded into a directory of its own, and the run is pointed at the copy.
 *
 * ```kotlin
 * val downgrade = tasks.register<DevRunDowngrade>("downgradeDevRun") {
 *     roots.from(sourceSets["main"].runtimeClasspath)   // anything outside the build is left alone
 *     classpath.from(sourceSets["main"].runtimeClasspath)
 * }
 * tasks.named<JavaExec>("runServer") {
 *     dependsOn(downgrade)
 *     doFirst { classpath = files(classpath.files.map(downgrade.get()::copyOf) + downgrade.get().api()) }
 * }
 * ```
 *
 * - [copyOf] and [api] answer only after the task has run: read them in a `doFirst`, never while configuring.
 * - The copies reference jvmdg's runtime stubs unshaded, so [api] goes on the run too.
 */
abstract class DevRunDowngrade : DefaultTask(), DowngradeFlags {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val roots: ConfigurableFileCollection

    /** What the downgrade resolves supertypes against. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Only roots under this directory are ours to downgrade; libraries arrive at the Java they need. */
    @get:Internal
    val ownRoot: File = project.rootDir

    init {
        convention(this, project.gradle.sharedServices.registrations
            .getByName("${project.path}:jvmdgDefaultFlags").parameters as DowngradeFlags)
        downgradeTo.set(JavaVersion.VERSION_1_8)
        outputDirectory.convention(project.layout.buildDirectory.dir("devRunDowngrade"))
    }

    @TaskAction
    fun downgrade() {
        val out = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        val newer = roots.files.distinct().filter { it.exists() && it.startsWith(ownRoot) && newestClass(it) > JAVA_8 }
        val copies = newer.mapIndexed { i, root -> File(out, "$i-${root.nameWithoutExtension}") }
        val mapping = Properties()
        if (newer.isNotEmpty()) {
            val zips = newer.filter { it.isFile }.associateWith { FileSystems.newFileSystem(it.toPath(), null as ClassLoader?) }
            try {
                val inputs: List<Path> = newer.map { zips[it]?.getPath("/") ?: it.toPath() }
                ClassDowngrader.downgradeTo(toFlags()).use {
                    PathDowngrader.downgradePaths(it, inputs, copies.map(File::toPath), classpath.files.map { f -> f.toURI().toURL() }.toSet())
                }
            } finally {
                zips.values.forEach { it.close() }
            }
            newer.zip(copies).forEach { (root, copy) -> mapping[root.absolutePath] = copy.absolutePath }
        }
        downgradedApis(out).forEachIndexed { i, jar -> mapping["api.$i"] = jar.absolutePath }
        File(out, MAPPING).outputStream().use { mapping.store(it, "root -> its Java 8 copy") }
    }

    /** [root]'s Java 8 copy, or [root] itself when it needed none. */
    fun copyOf(root: File): File = mapping().getProperty(root.absolutePath)?.let(::File) ?: root

    /** jvmdg's runtime stubs at Java 8, which the copies reference. */
    fun api(): List<File> = mapping().stringPropertyNames().filter { it.startsWith("api.") }.sorted()
        .map { File(mapping().getProperty(it)) }

    private fun mapping(): Properties = Properties().apply {
        File(outputDirectory.get().asFile, MAPPING).inputStream().use { load(it) }
    }

    /** jvmdg's API jars downgraded to 8, beside the copies. The same stage DowngradeShadeJar runs. */
    private fun downgradedApis(out: File): List<File> = apiJar.get().mapIndexed { i, api ->
        val downgraded = File(out, "api-$i-${ShadeJar::class.java.`package`.implementationVersion}.jar")
        ClassDowngrader.downgradeTo(toFlags()).use { ZipDowngrader.downgradeZip(it, api.toPath(), emptySet(), downgraded.toPath()) }
        downgraded
    }

    private companion object {
        const val JAVA_8 = 52
        const val MAPPING = "mapping.properties"

        /** The highest class-file major version under [root], a directory or a jar. */
        fun newestClass(root: File): Int = if (root.isDirectory) {
            root.walkTopDown().filter { it.name.endsWith(".class") }.maxOfOrNull { f -> f.inputStream().use(::major) } ?: 0
        } else if (root.extension == "jar") {
            ZipFile(root).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .maxOfOrNull { e -> zip.getInputStream(e).use(::major) } ?: 0
            }
        } else 0

        fun major(stream: InputStream): Int = DataInputStream(stream).run { readInt(); readUnsignedShort(); readUnsignedShort() }
    }
}
