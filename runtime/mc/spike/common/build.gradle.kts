// J10 / E-A1 SPIKE — the vanilla-facing branch, and the one that actually tests the preprocessor.
//
// The loader branches name `NAME`, `LifecycleCrystalGUI` and `CgUiKeybinds` from here, so a 1.19.4
// loader node cannot borrow `:runtime:mc:modern:common`: that module is pinned to 1.20.1. Giving the
// tree its own versioned common branch is the branched layout Stonecutter documents, and it puts the
// break lines (`GuiGraphics`, `Optional<Resource>`) in the one place they belong.
//
// Per-node properties, not the root's `mc1201.*` keys: a node IS a version, so its pins live in
// `versions/<node>/gradle.properties`.

plugins {
    id("cg-java17")
    id("net.neoforged.moddev.legacyforge")
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

// legacyForge supplies Minecraft ITSELF here, exactly as the shipping common module does -- the
// Forge classes are compileOnly and never reach the output. It is the only ModDevGradle path that
// resolves 1.20.1, and it resolves 1.19.4 the same way.
legacyForge {
    version = "${property("mc.version")}-${property("forge.version")}"

    // Parchment is per node and OPTIONAL: 1.20.1 has a pin we already trust, and rather than guess a
    // 1.19.4 mappings version the node simply goes without. Official names either way.
    val parchmentMc = findProperty("parchment.mc")?.toString()
    val parchmentVersion = findProperty("parchment.version")?.toString()
    if (parchmentMc != null && parchmentVersion != null) {
        parchment {
            minecraftVersion = parchmentMc
            mappingsVersion = parchmentVersion
        }
    }
}

@Suppress("UNCHECKED_CAST")
val submoduleMods = rootProject.extra["submoduleMods"] as List<Map<String, Any>>
val crystalGraphicsDeps: List<String> = submoduleMods
    .flatMap { (it["mc1201CompileDeps"] as? List<*>).orEmpty().filterIsInstance<String>() }
    .distinct()

dependencies {
    implementation(project(":core"))
    compileOnly(project(":taffy"))
    compileOnly(project(":runtime:mc:shared"))

    compileOnly("org.spongepowered:mixin:${property("mc1201.mixin")}")
    compileOnly("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")

    crystalGraphicsDeps.forEach { compileOnly(it) }
}

// What the loader branches compile against, one artifact per node.
configurations.create("commonOutput") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts { add("commonOutput", tasks.named("jar")) }
