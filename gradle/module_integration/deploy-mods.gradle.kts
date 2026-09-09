// `deployMods` — build this module's shippable jars and drop them into a real client.
//
// A production defect is only reachable from an INSTALLED jar: a dev run is deobfuscated, so it shows
// neither a reobfuscation fault nor the mapping stack doing anything at all. Reobfuscation, a split
// package, missing downgrade stubs and a bad shade path were each found that way, and each cost a
// build, a hunt for two jars and two copies by hand.
//
// ONE script, applied by every module that can be installed somewhere -- 1.20.x's three loaders, 1.7.10,
// and whatever comes next. What differs per module is only WHICH jars ship and WHICH directory they go
// to, so those are the inputs; the destination rule, the fallback and the reporting are shared.
//
// Applied the way this repo applies its other integrations, with the conventions set first:
//
//   extra["cgDeployKey"] = "prismLauncher1201ForgeDir"
//   extra["cgDeployJars"] = listOf(ourJar, theirJar)
//   extra["cgDeployDependsOn"] = listOf(ourTask, otherBuild.task(":path:task"))
//   apply(from = rootProject.file("gradle/module_integration/deploy-mods.gradle.kts").toURI())
//
// The key names a PrismLauncher instance directory -- the one holding `instance.cfg` and `.minecraft`
// -- and `.minecraft/mods` is derived here rather than configured, so the same setting keeps `logs/`
// and `crash-reports/` reachable for whoever reads a failure back.
// @see gradle/local-settings.gradle.kts

@Suppress("UNCHECKED_CAST")
val deployJars = (extra["cgDeployJars"] as? List<Any>).orEmpty()

@Suppress("UNCHECKED_CAST")
val deployDependsOn = (extra["cgDeployDependsOn"] as? List<Any>).orEmpty()

val deployKey = extra["cgDeployKey"] as? String
    ?: throw GradleException("deploy-mods.gradle.kts applied to $path without extra[\"cgDeployKey\"]")

// ASKED FOR, not assumed: local settings are free-form, so a key nobody set is ABSENT from `extra`
// rather than null, and reading it outright throws. Unset is a supported state. @see local-settings
val instanceDir: String? = if (extra.has(deployKey)) extra[deployKey] as? String else null

tasks.register<Copy>("deployMods") {
    group = "crystalgui"
    description = "Builds this module's jars and copies them into the client named by $deployKey."

    dependsOn(deployDependsOn)
    from(deployJars)

    // UNSET IS NORMAL, not an error: a fresh clone has no local.properties and must still build. The
    // jars go somewhere findable and the task says where.
    into(instanceDir?.let { File(File(it, ".minecraft"), "mods") }
            ?: layout.buildDirectory.dir("ship"))

    // OUR OWN JARS ARE REMOVED FIRST, and a plain Copy is why this is needed: it adds and never
    // takes away, so a rename or a loader switch leaves the previous jar in place. Two crystalgui
    // jars is two mods of one id, and the stale one may be built for a different loader entirely --
    // a Forge-reobfuscated jar sat in the NeoForge instance exactly that way. Scoped to the two
    // artifacts this build produces; nothing else in mods/ is touched.
    doFirst {
        val into = destinationDir
        into.listFiles { _, name ->
            (name.startsWith("crystalgui-") || name.startsWith("crystalgraphics-"))
                    && name.endsWith(".jar")
        }?.forEach {
            logger.lifecycle("[cgui] removing previous {}", it.name)
            it.delete()
        }
    }

    doFirst {
        if (instanceDir == null) {
            logger.lifecycle("[cgui] {} is not set in local.properties -- writing to {}",
                    deployKey, destinationDir)
        } else {
            logger.lifecycle("[cgui] deploying {} jar(s) to {}", deployJars.size, destinationDir)
        }
    }
}
