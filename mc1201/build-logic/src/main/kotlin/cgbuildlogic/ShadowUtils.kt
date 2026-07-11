package cgbuildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.bundling.Jar
import java.io.File

/**
 * Shared shadow JAR bundling — call from each mc1201 loader's build.gradle.kts.
 *
 * Bundles [:core] and [:mc1201:common] JARs into the shadowJar output,
 * then wires assemble → shadowJar.
 *
 * Fabric keeps its own `tasks.jar` bundling for Loom dev runs (separate concern).
 *
 * NOTE: ShadowJar's `configurations` property is set via reflection because
 * ShadowJar is not on the build-logic compile classpath as a Kotlin-visible type.
 */
fun configureShadowJarBundling(project: Project) {
    with(project) {
        afterEvaluate {
            tasks.named("shadowJar").configure {
                dependsOn(":core:jar", ":mc1201:common:jar")

                // ShadowJar.configurations = empty list (no runtime classpath shadowing).
                val configsSetter = (this as Any).javaClass.methods.first { m -> m.name == "setConfigurations" }
                configsSetter.invoke(this, emptyList<Any>())

                // Bundle core and common JARs via AbstractCopyTask.from().
                val coreJar: File = project(":core").tasks.named("jar", Jar::class.java)
                    .get().archiveFile.get().asFile
                val commonJar: File = project(":mc1201:common").tasks.named("jar", Jar::class.java)
                    .get().archiveFile.get().asFile
                (this as AbstractCopyTask).from(zipTree(coreJar))
                (this as AbstractCopyTask).from(zipTree(commonJar))
            }
        }
        tasks.named("assemble").configure { dependsOn(tasks.named("shadowJar")) }
    }
}
