plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    // core/ uses a JDK 21 toolchain (modern syntax via Jabel) but its bytecode targets
    // Java 8. Without disableAutoTargetJvm(), Gradle rejects core as incompatible with
    // this JVM-17 consumer. Disabling the check allows any bytecode version here.
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
