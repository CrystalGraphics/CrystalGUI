// What every modern node shares -- ModLauncher and Knot, 1.13 to 1.21 -- where the code names no Minecraft
// class and no per-node one, and would otherwise ship once per node. launchwrapper's counterpart.
//
// The language half only, so far: merged once into the language jar and never relocated. Its package,
// `com.crystalgui.mc.shared.modern`, stays clear of `com.crystalgui.mc.modern`, which the node relocation
// matches as a string prefix. Java 8 out, the oldest modern node's.

plugins { `java-library` }

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-modern-shared") }

java {
    // Java 8 is also what Gradle then requests, so :language and :core resolve to their Java 8 copies.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

repositories { mavenCentral() }

dependencies {
    // compileOnly: every modern host supplies all four at run time -- Minecraft ships gson.
    compileOnly(project(":language"))
    compileOnly(project(":core"))
    compileOnly("com.google.code.gson:gson:2.2.4")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
