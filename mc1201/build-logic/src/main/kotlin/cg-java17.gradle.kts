plugins { `java-library` }

java {
    // JDK 21 to COMPILE, Java 17 bytecode OUT. :core emits Java 21 (v65) -- its Jabel processor is
    // commented out, so nothing desugars it -- and a JDK 17 javac cannot read a v65 class file at all:
    //
    //     bad class file: .../DesktopPresentation.class
    //       class file has wrong version 65.0, should be 61.0
    //
    // MC 1.20.1 ships a Java 17 runtime, so what we EMIT must stay at 17.
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    withSourcesJar()
    // Gradle would otherwise reject :core as incompatible with a 17 consumer.
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // source/target rather than release: --release 17 also refuses to READ anything newer, which is
    // the error above. The 21 toolchain's platform classes are the only thing lost, and nothing here
    // wants a post-17 API.
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
