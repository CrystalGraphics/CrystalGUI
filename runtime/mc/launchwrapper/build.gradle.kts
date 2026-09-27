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
    // Java 8 is also what Gradle then requests, so :language resolves to its Java 8 copy.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
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
