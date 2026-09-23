import cgbuildlogic.nodeJava

plugins { `java-library` }

// The Java a node EMITS is its Minecraft's: 17 up to 1.20.4, 21 from 1.20.5, where Minecraft's own classes
// are Java 21. @see cgbuildlogic.nodeJava

java {
    // JDK 21 (or the node's, if newer) to COMPILE, the node's Java OUT. :core emits Java 21 (v65) -- its Jabel processor is
    // commented out, so nothing desugars it -- and a JDK 17 javac cannot read a v65 class file at all:
    //
    //     bad class file: .../DesktopPresentation.class
    //       class file has wrong version 65.0, should be 61.0
    //
    // What we EMIT is the node's Minecraft's Java, never :core's.
    toolchain { languageVersion.set(JavaLanguageVersion.of(maxOf(21, nodeJava))) }
    withSourcesJar()
    // Gradle would otherwise reject :core as incompatible with a 17 consumer.
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // source/target rather than release: --release 17 also refuses to READ anything newer, which is
    // the error above. The 21 toolchain's platform classes are the only thing lost, and nothing here
    // wants a post-17 API.
    sourceCompatibility = nodeJava.toString()
    targetCompatibility = nodeJava.toString()
}
