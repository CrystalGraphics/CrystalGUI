import java.util.Properties

// Java, not Kotlin: a plugin built with this Gradle's kotlin-dsl carries Kotlin metadata an older
// consumer Gradle cannot read, and consumers pin their own Gradle.
plugins {
    `java-gradle-plugin`
    `maven-publish`
}

fun modVersion(propertiesFile: String): String = propertyIn(propertiesFile, "modVersion")

fun propertyIn(propertiesFile: String, key: String): String =
    Properties().apply { file(propertiesFile).reader().use(::load) }.getProperty(key)

/** `<owner>/<repository>` on Cloudsmith, where both repositories publish. */
val cloudsmithRepository = propertyIn("../gradle.properties", "cloudsmith.repository")

group = "com.crystalgui"
version = modVersion("../gradle.properties")

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withJavadocJar()
}

// Gradle 9 runs on Java 17 at the least, so no consumer's daemon is older.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// The versions of the artifacts this plugin puts on a consumer, which are the versions it was built with.
val generateVersions by tasks.registering(WriteProperties::class) {
    destinationFile.set(layout.buildDirectory.file("generated/versions/com/crystalgui/gradle/versions.properties"))
    property("crystalgui", project.version.toString())
    property("crystalgraphics", modVersion("../CrystalGraphics/gradle.properties"))
    property("repository", "https://dl.cloudsmith.io/public/$cloudsmithRepository/maven/")
}
sourceSets.main {
    resources.srcDir(generateVersions.map { layout.buildDirectory.dir("generated/versions").get() })
}

gradlePlugin {
    plugins {
        create("crystalgui") {
            id = "com.crystalgui"
            implementationClass = "com.crystalgui.gradle.CrystalGuiPlugin"
            displayName = "CrystalGUI"
            description = "CrystalGUI's API on the compile classpath and its mods on the dev run, for any loader."
        }
        create("crystalguiSettings") {
            id = "com.crystalgui.settings"
            implementationClass = "com.crystalgui.gradle.CrystalGuiSettingsPlugin"
            displayName = "CrystalGUI settings"
            description = "Builds CrystalGUI from a local checkout in place of the published artifacts."
        }
    }
}

// Where `./gradlew publish` uploads, as for every published module: Cloudsmith with CLOUDSMITH_USERNAME and
// CLOUDSMITH_PASSWORD set, Maven local otherwise. @see cgbuildlogic.publishingRepository
publishing {
    repositories {
        val username = providers.environmentVariable("CLOUDSMITH_USERNAME")
        val password = providers.environmentVariable("CLOUDSMITH_PASSWORD")
        if (!username.isPresent || !password.isPresent) mavenLocal()
        else maven {
            name = "Cloudsmith"
            url = uri("https://maven.cloudsmith.io/$cloudsmithRepository/")
            credentials {
                this.username = username.get()
                this.password = password.get()
            }
        }
    }
}
