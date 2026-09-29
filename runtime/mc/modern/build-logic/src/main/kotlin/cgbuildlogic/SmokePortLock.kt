package cgbuildlogic

import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * One smoke server at a time in a build: every node's `serverSmoke` binds the same port.
 *
 * ```kotlin
 * tasks.named<JavaExec>("runServer") { usesService(SmokePortLock.register(gradle)) }
 * // a build script that cannot see this class finds it by name:
 * gradle.sharedServices.registrations.findByName(SmokePortLock.NAME)?.let { usesService(it.service) }
 * ```
 *
 * Without it, `org.gradle.parallel` ran three at once and two reported no result.
 */
abstract class SmokePortLock : BuildService<BuildServiceParameters.None> {

    companion object {
        const val NAME = "cgServerSmokePort"

        fun register(gradle: Gradle): Provider<SmokePortLock> =
            gradle.sharedServices.registerIfAbsent(NAME, SmokePortLock::class.java) {
                maxParallelUsages.set(1)
            }
    }
}
