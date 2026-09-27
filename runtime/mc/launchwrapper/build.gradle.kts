// What the two LaunchWrapper hosts share -- 1.7.10 (runtime/mc/1710) and Forge 1.8 to 1.12.2
// (runtime/mc/legacy) -- where the code would otherwise be one copy per host. LaunchWrapper's API never
// changed across them.
//
// The language half only, so far: merged once into the language jar and never relocated. Java 8 out,
// since FML 1.7.10 reads every class in the jar with ASM 5 and refuses anything above major 52.

plugins { `java-library` }

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-launchwrapper") }

java {
    // A 21 javac to READ :language (Java 21 class files), source/target 8 to emit what FML can read.
    // --release 8 would refuse the first. The same arrangement as a legacy node's.
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net/") { name = "Mojang" }
}

dependencies {
    // compileOnly: every host supplies both at run time.
    compileOnly(project(":language"))
    compileOnly("net.minecraft:launchwrapper:1.12")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
