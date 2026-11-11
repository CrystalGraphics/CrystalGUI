// What declares CrystalGUI to a loader: the @Mod classes for every Forge (one class per mod, carrying FML 1.7.10's
// annotation and the one 1.8 onward scans for) and every NeoForge, the host's (`main`) and the language mod's (`lang`).
// Java 8, compiled once against CrystalGraphics' forge-stubs, merged once into each single jar and never relocated.
// Fabric needs none. @see com.crystalgraphics.mc.shared.ForgeStart, NeoForgeStart
plugins { `java-library` }

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-forge-stubs") }

val lang: SourceSet by sourceSets.creating

dependencies {
    for (configuration in listOf("compileOnly", "langCompileOnly")) {
        add(configuration, "com.crystalgraphics:forge-stubs:1.0.0")
        add(configuration, "com.crystalgraphics:mc-shared:1.0.0")
    }
}

/** The language mod's half, merged into the language jar alone. */
tasks.register<Jar>("langJar") {
    archiveClassifier.set("lang")
    from(lang.output)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
