// CrystalGUI's @Mod classes for every Forge -- 1.13+ and legacy FML 1.8-1.12.2 alike, which scan for the
// same annotation: the host's (`main`) and the language mod's (`lang`). Java 8, merged once into each
// single jar and never relocated. @see com.crystalgraphics.mc.shared.ForgeStart
plugins { `java-library` }

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-forge-bootstrap") }

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
