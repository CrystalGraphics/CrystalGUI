package cgbuildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.bundling.Jar
import java.io.File

/**
 * Shared shadow JAR bundling — call from each mc1201 loader's build.gradle.kts.
 *
 * Wires assemble → shadowJar and puts into the jar everything a SHIPPED mod needs, which a dev run
 * never exercises: a dev run resolves from MOD_CLASSES and the library classpath and consults the jar's
 * contents for nothing at all.
 *
 * **Taffy, JOML and fastutil are not optional and their absence is silent.** `UIElement` and
 * `ElementStyle` hold a `NodeId` and a `Matrix4f` as *fields*, and a field descriptor resolves when the
 * class is DEFINED — so without them the UI classes do not load, and nothing reports a missing library.
 * plan/platform-mc1201.md 4.3.
 *
 * **The tree-sitter jars go in unrelocated.** Each carries the JNI natives for its grammar, and a JNI
 * symbol is named after the mangled package — renaming it renames the symbol the .dll does not export,
 * so the first parser built throws UnsatisfiedLinkError.
 *
 * NOTE: ShadowJar's `configurations` property is set via reflection because
 * ShadowJar is not on the build-logic compile classpath as a Kotlin-visible type.
 */
fun configureShadowJarBundling(project: Project) {
    with(project) {
        afterEvaluate {
            tasks.named("shadowJar").configure {
                dependsOn(":core:jar", ":mc1201:common:jar", ":language:jar", ":taffy:jar")
                dependsOn("bundleEngineBands", "writeEngineManifests")

                // ShadowJar.configurations = empty list (no runtime classpath shadowing).
                val configsSetter = (this as Any).javaClass.methods.first { m -> m.name == "setConfigurations" }
                configsSetter.invoke(this, emptyList<Any>())

                // TAFFY IS RELOCATED, and JOML and fastutil are NOT.
                //
                // Taffy's package stays dev.vfyjxf.taffy in this repo because mc1710 relocates it when
                // shipping and 165 call sites needed no edit -- so a stock dev.vfyjxf:taffy in another
                // mod cannot win a classloader race against ours. The target matches mc1710's byte for
                // byte (GTNH's relocateShadowedDependencies writes <group>.shadow), so the two loaders
                // agree about where it lives.
                //
                // The rule that decides what may move: no type of a relocated library may appear in a
                // signature another jar defines. Taffy passes. JOML does NOT -- CrystalGraphics' own
                // surface is full of Matrix4f -- so it ships unrelocated, and fastutil rides along
                // unrelocated because Taffy's public surface leaks it. plan/platform-mc1201.md 4.3.
                //
                // Reflection for the same reason setConfigurations above needs it: ShadowJar is not a
                // Kotlin-visible type on the build-logic compile classpath. Two String arguments picks
                // relocate(String, String) out of its four overloads.
                val relocate = (this as Any).javaClass.methods.first { m ->
                    m.name == "relocate" && m.parameterCount == 2
                        && m.parameterTypes.all { it == String::class.java }
                }
                relocate.invoke(this, "dev.vfyjxf.taffy", "com.crystalgui.shadow.dev.vfyjxf.taffy")

                val copy = this as AbstractCopyTask
                for (path in listOf(":core", ":mc1201:common", ":language", ":taffy")) {
                    val jar: File = project(path).tasks.named("jar", Jar::class.java)
                        .get().archiveFile.get().asFile
                    copy.from(zipTree(jar))
                }

                // JOML and fastutil, from the loader's own `shippedLibs` -- see its declaration for why
                // they cannot be read off :core or :taffy.
                configurations.getByName("shippedLibs").forEach { copy.from(zipTree(it)) }

                // Verbatim, never relocated -- see the JNI note above.
                rootProject.file("lib/tree-sitter").listFiles()
                    ?.filter { it.name.endsWith(".jar") }
                    ?.forEach { copy.from(zipTree(it)) }

                // The engine band this jar carries, and a manifest per band for the ones it does not.
                copy.from(tasks.named("bundleEngineBands"))
                copy.from(tasks.named("writeEngineManifests"))
            }
        }
        tasks.named("assemble").configure { dependsOn(tasks.named("shadowJar")) }
    }
}
