// J10 / E-A1 SPIKE — the Forge branch of the Stonecutter tree.
//
// A SPIKE, not a product: it answers A1 ("does the multi-version preprocessor run under
// ModDevGradle and Loom, in THIS Gradle 9 build") and nothing else. It deliberately carries none of
// `cg-mc1201-loader`'s jar pipeline -- no thin jar, no relocation, no jvmdg downgrade, no
// serverSmoke -- because a failure in any of those would be read as a failure of the preprocessor.
// What it does carry is the real loader sources and the real dependencies, so a green compileJava
// means something.
//
// SPLIT BUILD SCRIPT, which is what Stonecutter's own multi-loader guide recommends: each loader
// keeps its own moddev toolkit rather than both being forced through one script.
//
// ONE NODE TODAY (1.20.1) and nothing here assumes it: every pin comes from the node's own
// `versions/<node>/gradle.properties`, exactly as the other two branches do, so a second Forge
// version is a line in settings and a properties file.

plugins {
    id("cg-java17")
    id("net.neoforged.moddev.legacyforge")
}

// THE SAME LIST `cg-mc1201-loader` DECLARES, and the spike needs its own copy: that convention
// plugin carries the whole jar pipeline, so the spike applies `cg-java17` instead -- which declares
// no repositories at all. Without this, `org.spongepowered:mixin` is searched for on Fabric's and
// Mojang's mavens and nowhere that has it, and the failure reads as a toolchain fault rather than a
// missing repository.
repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

val mcVersion = property("mc.version").toString()

// The matching common NODE, never `:runtime:mc:modern:common`: that module is compiled against
// 1.20.1 alone, so a loader node at any other version built on it would be testing nothing. Named
// once here because three places below need it.
val commonNode = ":runtime:mc:spike:common:$mcVersion"

// Resolved OUTSIDE the extension block: inside it `project` is the deprecated accessor, which warns
// on every configuration.
val clientRunDir = layout.projectDirectory.dir("runs/client").asFile

legacyForge {
    version = "$mcVersion-${property("forge.version")}"

    // Parchment is per node and OPTIONAL -- a node without a pin uses official names rather than a
    // guessed mappings version.
    val parchmentMc = findProperty("parchment.mc")?.toString()
    val parchmentVersion = findProperty("parchment.version")?.toString()
    if (parchmentMc != null && parchmentVersion != null) {
        parchment {
            minecraftVersion = parchmentMc
            mappingsVersion = parchmentVersion
        }
    }

    // E-A1 DAY 2: a run has to EXIST before it can be green. The game directory sits under the node
    // (versions/<node>/runs), so two nodes never share one world.
    runs {
        create("client") {
            client()
            gameDirectory = clientRunDir
        }
    }

    // ModDevGradle dev runs see ONLY what this block names -- not runtimeClasspath, which is the trap
    // `cg-mc1201-loader` documents at length. Without :core and the vanilla host here, the mod class
    // loads and dies on the first engine type with a NoClassDefFoundError for a class plainly on disk.
    mods {
        create("crystalgui") {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":core").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(commonNode).extensions.getByType<SourceSetContainer>()["main"])
        }
    }
}

// A dev run must BUILD what mods{} makes visible: naming a source set writes its output directory
// into the run and adds no task dependency of its own.
tasks.matching { it.name.startsWith("run") || it.name.startsWith("prepare") }.configureEach {
    dependsOn(":core:classes", "$commonNode:classes")
}

// CrystalGraphics' APIs, which `integration.gradle.kts` supplies to the shipping modules. It cannot
// be applied here: it keys on a path starting `:runtime:mc:modern`, so on this tree it would add
// nothing at all and `CrashVariant` would not resolve -- a compile failure that says nothing about
// A1. The same data, read the same way.
@Suppress("UNCHECKED_CAST")
val submoduleMods = rootProject.extra["submoduleMods"] as List<Map<String, Any>>
val crystalGraphicsDeps: List<String> = submoduleMods
    .flatMap { (it["mc1201CompileDeps"] as? List<*>).orEmpty().filterIsInstance<String>() }
    .distinct()

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(commonNode))
    compileOnly(project(":runtime:mc:shared"))
    // UIElement holds a Taffy NodeId and a JOML Matrix4f as FIELDS, which resolve at class load, so
    // javac needs them to read :core at all.
    compileOnly(project(":taffy"))

    compileOnly("org.spongepowered:mixin:${property("mc1201.mixin")}")
    compileOnly("io.github.llamalad7:mixinextras-common:${property("mc1201.mixinextras")}")

    crystalGraphicsDeps.forEach { compileOnly(it) }
}
