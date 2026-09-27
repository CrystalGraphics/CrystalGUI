import cgbuildlogic.ModDescriptor
import cgbuildlogic.configureStubs
import cgbuildlogic.legacyNodePackage
import cgbuildlogic.nodeJava
import cgbuildlogic.registerNodeVariants
import cgbuildlogic.useNodeCoordinates

plugins { id("cg-java") }

// ── A legacy node: `:runtime:mc:legacy:forge:<version>` ──────────────────────────────────────────
//
// Forge on LaunchWrapper, 1.8 to 1.12.2, at MCP names. The branch script puts Minecraft on the classpath
// (Unimined, real mode only) and renames the thin jars; this is what every legacy node does alike.
// @see cgbuildlogic.LegacyTree
useNodeCoordinates()
base { archivesName.set("crystalgui-legacy-${project.name}") }

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

// The language mod's entry, a source set as on the modern tree: `main` is on its classpath and not the
// reverse, so the host cannot name it.
val lang: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}

dependencies {
    // compileOnly: the merge adds each of these once, at the root.
    "compileOnly"(project(":core"))
    // UIDocument holds Taffy fields, and a field's type is read when a class using it compiles.
    "compileOnly"(project(":taffy"))
    // CrystalGraphics' modules, through the composite -- what `modernCompileDeps` gives a modern node.
    "compileOnly"("com.crystalgraphics:core:1.0.0")
    "compileOnly"("com.crystalgraphics:platform:1.0.0")
    "compileOnly"("com.crystalgraphics:mc-shared:1.0.0")
    // @Nullable: legacy Minecraft brings no jsr305 with its libraries.
    "compileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    // The language stack, on `lang` and never `main`: the host jar may not name it.
    "langCompileOnly"(project(":language"))
    // What this host shares with 1.7.10's; merged once into the language jar.
    "langCompileOnly"(project(":runtime:mc:launchwrapper"))
}

// ── The thin jars ────────────────────────────────────────────────────────────────────────────────
//
// This node's classes and nothing else, moved from `com.crystalgui.mc.legacy` to the node's own
// `com.crystalgui.mc.v<digits>`. One per mod: the host's (`main`) and the language mod's (`lang`).
val sourcePackage = "com.crystalgui.mc.legacy"

fun registerThin(name: String, classifier: String, sourceSet: SourceSet) =
    tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>(name) {
        group = "build"
        description = "This legacy node's ${sourceSet.name} classes, relocated -- a merge's input, before the SRG rename."
        archiveClassifier.set(classifier)
        configurations = emptyList()
        from(sourceSet.output)
        // The dev run's tables: each merge writes its shipped one once.
        exclude("META-INF/*/variants.json")
        relocate(sourcePackage, legacyNodePackage(sourcePackage, project.name))
    }
registerThin("thinShadowJar", "thin-dev", sourceSets["main"])
registerThin("langThinShadowJar", "lang-thin-dev", lang)

tasks.register<cgbuildlogic.CheckThinJar>("checkThinJar") {
    allowedPrefixes.set(listOf("com/crystalgui/mc/"))
    maxClassMajor.set(nodeJava + 44)
    forbiddenPrefixes.set(listOf(
        "com/crystalgui/ui/", "com/crystalgui/widget/", "com/crystalgui/style/",
        "com/crystalgui/workbench/", "com/crystalgui/language/", "com/crystalgraphics/core/",
        "dev/vfyjxf/", "org/joml/", "it/unimi/", "org/treesitter/", "com/fasterxml/",
        "de/javagl/", "assets/crystalgui/engines/",
    ))
    logTag.set("cgui")
}
tasks.named("check") { dependsOn("checkThinJar") }

@Suppress("UNCHECKED_CAST")
val modDescriptors = rootProject.extra["cgModDescriptors"] as Map<String, ModDescriptor>
registerNodeVariants(modDescriptors.getValue("main"))
registerNodeVariants(modDescriptors.getValue("lang"), "lang")

// Last, once every source set exists: the stub on the classpath, or the tasks that write and check it.
configureStubs()
