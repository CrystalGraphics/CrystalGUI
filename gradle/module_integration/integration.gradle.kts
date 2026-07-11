// Kotlin script — applied from mc1710/build.gradle.kts and mc1201/*/build.gradle.kts.
// Replaces integration.gradle (Groovy) and mc1201-integration.gradle (Groovy).
//
// Reads submoduleMods data from rootProject.extra (set at settings time by composite.settings.gradle.kts).
// Applies context-specific logic:
//   - :mc1710            → dev dependencies + bootstrap args injection into RunMinecraftTask
//   - :mc1201:*          → compileOnly + runtimeOnly dependencies for CrystalGraphics APIs

@Suppress("UNCHECKED_CAST")
val submoduleMods = rootProject.extra["submoduleMods"] as List<Map<String, Any>>

fun stringList(m: Map<String, Any>, key: String): List<String> {
    val v = m[key]
    return if (v is List<*>) v.filterIsInstance<String>() else emptyList()
}

if (project.path == ":mc1710") {
    // --- mc1710: dev dependencies ---
    dependencies {
        submoduleMods.flatMap { stringList(it, "devDependencies") }
            .distinct()
            .forEach { dep -> add("devOnlyNonPublishable", dep) }
    }

    // --- mc1710: inject composite submodule bootstrap args into RunMinecraftTask ---
    // All task API calls use reflection because RunMinecraftTask's extraArgs and
    // systemProperty methods are not visible on the Task interface in applied .gradle.kts.
    tasks.configureEach {
        val task = this
        if (task.javaClass.simpleName == "RunMinecraftTask") {
            val configuredCoremods = submoduleMods.flatMap { m -> stringList(m, "coremods") }.distinct()
            if (configuredCoremods.isNotEmpty()) {
                // Read existing fml.coreMods.load via reflection
                @Suppress("UNCHECKED_CAST")
                val sysProps = task.javaClass.getMethod("getSystemProperties").invoke(task) as MutableMap<String, Any>
                val existing = (sysProps["fml.coreMods.load"] as? String ?: "")
                    .split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                val merged = (existing + configuredCoremods).distinct().joinToString(",")
                // Write via systemProperty(String, Object) on ProcessForkOptions
                task.javaClass.methods
                    .first { m -> m.name == "systemProperty" && m.parameterCount == 2 }
                    .invoke(task, "fml.coreMods.load", merged)
            }
            // extraArgs is a GTNH ForgeGradle property — access via reflection
            val extraArgsMethod = task.javaClass.methods.find { m -> m.name == "getExtraArgs" && m.parameterCount == 0 }
            if (extraArgsMethod != null) {
                @Suppress("UNCHECKED_CAST")
                val extraArgs = extraArgsMethod.invoke(task) as MutableList<String>
                submoduleMods.flatMap { m -> stringList(m, "tweakClasses") }.distinct()
                    .forEach { tweakClass -> extraArgs.addAll(listOf("--tweakClass", tweakClass)) }
                submoduleMods.flatMap { m -> stringList(m, "mixinConfigs") }.distinct()
                    .forEach { mixinConfig -> extraArgs.addAll(listOf("--mixin", mixinConfig)) }
            }
        }
    }
} else if (project.path.startsWith(":mc1201")) {
    // --- mc1201: compile + runtime dependencies ---
    dependencies {
        submoduleMods.flatMap { stringList(it, "mc1201CompileDeps") }
            .distinct()
            .forEach { dep ->
                // compileOnly: loader sources can import CrystalGraphics APIs.
                add("compileOnly", dep)
                // runtimeOnly: Fabric/Loom dev runs pick these up from runtimeClasspath.
                // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath.
                add("runtimeOnly", dep)
            }
    }
}
