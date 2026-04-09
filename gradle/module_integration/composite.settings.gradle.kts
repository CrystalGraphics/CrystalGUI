apply(from = "submodules.gradle.kts")

@Suppress("UNCHECKED_CAST")
val submoduleMods = extra["submoduleMods"] as List<Map<String, *>>

fun Map<String, *>.string(key: String): String = this[key] as String

@Suppress("UNCHECKED_CAST")
fun Map<String, *>.mapList(key: String): List<Map<String, String>> =
    this[key] as? List<Map<String, String>> ?: emptyList()

submoduleMods.forEach { mod ->
    includeBuild(mod.string("buildPath")) {
        dependencySubstitution {
            mod.mapList("substitutions").forEach { substitution ->
                substitute(module(substitution.getValue("module"))).using(project(substitution.getValue("projectPath")))
            }
        }
    }
}
