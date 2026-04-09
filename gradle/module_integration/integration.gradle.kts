import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails

apply(from = "gradle/module_integration/submodules.gradle.kts")

@Suppress("UNCHECKED_CAST")
val submoduleMods = extra["submoduleMods"] as List<Map<String, *>>

fun Map<String, *>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

abstract class AcceptMissingObfuscationAttribute : AttributeCompatibilityRule<ObfuscationAttribute> {
    override fun execute(details: CompatibilityCheckDetails<ObfuscationAttribute>) {
        if (details.producerValue == null) {
            details.compatible()
        }
    }
}

dependencies {
    attributesSchema {
        attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE) {
            compatibilityRules.add(AcceptMissingObfuscationAttribute::class.java)
        }
    }

    submoduleMods
        .flatMap { it.stringList("devDependencies") }
        .distinct()
        .forEach { add("devOnlyNonPublishable", it) }
}

fun RunMinecraftTask.injectCompositeSubmoduleBootstrap() {
    val configuredCoremods = submoduleMods.flatMap { it.stringList("coremods") }.distinct()
    if (configuredCoremods.isNotEmpty()) {
        val existingCoremods = ((systemProperties["fml.coreMods.load"] as? String)
            ?: System.getProperty("fml.coreMods.load"))
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val mergedCoremods = (existingCoremods + configuredCoremods).distinct().joinToString(",")
        systemProperty("fml.coreMods.load", mergedCoremods)
    }

    val tweakClasses = submoduleMods.flatMap { it.stringList("tweakClasses") }.distinct()
    val mixinConfigs = submoduleMods.flatMap { it.stringList("mixinConfigs") }.distinct()

    tweakClasses.forEach { getExtraArgs().addAll("--tweakClass", it) }
    mixinConfigs.forEach { getExtraArgs().addAll("--mixin", it) }
}

tasks.withType<RunMinecraftTask>().configureEach {
    injectCompositeSubmoduleBootstrap()
}
