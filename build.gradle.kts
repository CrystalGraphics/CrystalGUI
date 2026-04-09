
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

apply(from = "gradle/module_integration/integration.gradle.kts")

// GTNH Gradle is forcing multirelease for 25,21 even if its set to empty in gradle.properties
// I spent an hour trying to fix this shit - and if not that it's trying to release the original
// no matter. this fixes it.
// :p
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)
