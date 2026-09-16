// J10 / E-A1 SPIKE — the Fabric branch of the Stonecutter tree. See the forge branch's header for
// what this is and, more importantly, what it deliberately is not.
//
// Loom rather than ModDevGradle, in its own script: that is the "split build script" shape, and it
// is half of what A1 actually asks -- whether the preprocessor tolerates TWO different moddev
// toolkits in one Gradle 9 build.
//
// TWO NODES since day 2: 1.20.1 and 1.19.4. Every pin is read from the node's own
// `versions/<node>/gradle.properties`, because a node IS a version and a root-level `mc1201.*` key
// cannot describe two of them.

plugins {
    id("cg-java17")
    // The version the shipping module pins; the repository for it is in the root pluginManagement.
    id("fabric-loom") version "1.16.2"
}

// THE SAME LIST `cg-mc1201-loader` DECLARES -- see the forge branch for why the spike needs its own
// copy. Loom adds Fabric's and Mojang's mavens; Mixin lives on Sponge's and nothing else declares it.
repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

@Suppress("UNCHECKED_CAST")
val submoduleMods = rootProject.extra["submoduleMods"] as List<Map<String, Any>>
val crystalGraphicsDeps: List<String> = submoduleMods
    .flatMap { (it["mc1201CompileDeps"] as? List<*>).orEmpty().filterIsInstance<String>() }
    .distinct()

val mcVersion = property("mc.version").toString()

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.layered {
        officialMojangMappings()
        // Parchment is per node and optional; 1.19.4 goes without rather than on a guessed pin.
        val parchmentMc = findProperty("parchment.mc")?.toString()
        val parchmentVersion = findProperty("parchment.version")?.toString()
        if (parchmentMc != null && parchmentVersion != null) {
            parchment("org.parchmentmc.data:parchment-$parchmentMc:$parchmentVersion@zip")
        }
    })
    modImplementation("net.fabricmc:fabric-loader:${property("fabric.loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric.api")}")

    compileOnly(project(":core"))
    compileOnly(project(":taffy"))
    compileOnly(project(":runtime:mc:shared"))

    // THE MATCHING COMMON NODE, not the shipping module: `:runtime:mc:modern:common` is compiled
    // against 1.20.1 alone, so a 1.19.4 loader node built on it would be testing nothing.
    compileOnly(project(":runtime:mc:spike:common:$mcVersion"))

    compileOnly("org.spongepowered:mixin:${property("mc1201.mixin")}")
    compileOnly("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")

    crystalGraphicsDeps.forEach { compileOnly(it) }

    // E-A1 DAY 2: Loom's dev run resolves from runtimeClasspath, unlike ModDevGradle's -- so what the
    // forge branch declares in `mods {}`, this declares here.
    runtimeOnly(project(":core")) {
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.gson")
    }
    runtimeOnly(project(":taffy"))
}

loom {
    runs {
        named("client") { runDir("runs/client") }
    }
}
