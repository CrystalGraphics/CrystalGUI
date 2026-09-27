import cgbuildlogic.nodeJava

plugins { `java-library` }

// The Java a node EMITS is its Minecraft's: 17 up to 1.20.4, 21 from 1.20.5, where Minecraft's own classes
// are Java 21. @see cgbuildlogic.nodeJava

java {
    // The dev runs' JVM; every compile uses the one compiler (root build). 21 at least, which the dev
    // launchers of the 17 nodes have always run on.
    toolchain { languageVersion.set(JavaLanguageVersion.of(maxOf(21, nodeJava))) }
    withSourcesJar()
    // What Gradle requests of a dependency, so the abstract modules resolve to their Java 8 copies.
    // @see cgbuildlogic.abstractModule
    sourceCompatibility = JavaVersion.toVersion(nodeJava)
    targetCompatibility = JavaVersion.toVersion(nodeJava)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
