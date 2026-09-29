// What every node of the tree does alike, common and loader: its coordinates, its Java, its repositories.
// Applied first by each branch script.
import cgbuildlogic.nodeJava
import cgbuildlogic.useNodeCoordinates

useNodeCoordinates()

configure<JavaPluginExtension> {
    toolchain { languageVersion.set(JavaLanguageVersion.of(nodeJava)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(nodeJava)
}

repositories {
    mavenLocal()                                        // com.crystalgui, com.crystalgraphics
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}
