// The Minecraft-free half: the application itself. It names CrystalGUI's API and no loader or game type,
// so it compiles once and ships once, beside every loader's thin jar.
plugins {
    `java-library`
    id("com.crystalgui")                      // CrystalGUI's API on compileOnly, and checkCrystalGuiApi
}

group = property("modGroup").toString()
version = property("modVersion").toString()

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)                  // the oldest Java a target runs; the merge takes it to 8
}

repositories {
    mavenLocal()
    mavenCentral()
}
