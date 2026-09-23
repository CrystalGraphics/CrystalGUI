import cgbuildlogic.guardLoaderImports
import cgbuildlogic.sameVersionNodeCoordinate
import cgbuildlogic.useModernMinecraft
import cgbuildlogic.useNodeCoordinates

// ── A node of the `common` branch: vanilla Minecraft, and nothing from any loader ───────────────────
//
// Applied to every `:runtime:mc:modern:common:<version>`. How a node finds Minecraft, what its
// coordinates are and what it may not import are CrystalGraphics' answers, shared by every build laid
// out this way -- @see CrystalGraphics/singlejar-logic, ModernTree and ModernConventions.

plugins {
    id("cg-java17")
    `maven-publish`
}

useNodeCoordinates()

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    // ADHOC: Mojang meta and MC libraries repos that net.neoforged.moddev.repositories (settings
    // plugin) should provide, but must be re-declared at project level in Gradle 9 due to
    // DependencyResolutionManagement ordering (same workaround the neoforge branch needs).
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

useModernMinecraft()

dependencies {
    // implementation — core is an internal dependency consumed by common.
    "implementation"(project(":core"))

    // :core declares Taffy and JOML compileOnly, so they reach nobody transitively -- and UIElement
    // holds a Taffy NodeId and a JOML Matrix4f as FIELDS, which resolve at class load. Without these
    // javac reports "cannot access UIDocument" rather than a missing dependency. plan/platform-mc1201.md 4.3.
    "compileOnly"(project(":taffy"))
    // Mixin compileOnly — both loaders bundle it at runtime; never shade it.
    "compileOnly"("org.spongepowered:mixin:${property("modern.mixin")}")
    // NOTE: mixin annotationProcessor is intentionally omitted here — legacyForge configures
    // the Mixin AP with the correct SRG file automatically. Adding a second AP without SRG
    // causes duplicate-AP obfuscation-mapping errors for all @Inject targets.
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("modern.mixinextras")}")
    "annotationProcessor"("io.github.llamalad7:mixinextras-common:${property("modern.mixinextras")}")

    // CrystalGraphics' common node of THIS Minecraft, through the composite's per-node substitution.
    "compileOnly"(sameVersionNodeCoordinate("com.crystalgraphics", "common"))
    "runtimeOnly"(sameVersionNodeCoordinate("com.crystalgraphics", "common"))
}

// ── The language stack's host half (J8) ──────────────────────────────────────────────────────────
//
// A SOURCE SET, not a module of its own: a module per loader per era doubles the module count every
// time an era is added.
//
// `main` is on lang's compile classpath and NOT the reverse, so the main jar cannot name the language
// stack — the compiler enforces the split rather than an import guard noticing it afterwards. Its own
// package (`com.crystalgui.mc.modern.lang`) because the two source sets end up in two JARS, and two jars
// sharing a package is a split package that fails module resolution on Forge and NeoForge.
val lang: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}

dependencies {
    // :language reaches THIS source set and not `main`, which is what makes the rule above a compile
    // error rather than a convention.
    "langCompileOnly"(project(":language"))
}

/** The language host, for the loaders' lang thin jars to consume exactly as they consume `jar`. */
val langJar = tasks.register<Jar>("langJar") {
    group = "language jar"
    description = "The language stack's 1.20.x host — the language mod's shared half."
    archiveClassifier.set("lang")
    from(lang.output)
}

// Export compiled JAR so loader nodes can depend on it as a binary
configurations.create("commonOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonOutput", tasks.named("jar")) }

/** The same, for the language half: what each loader's own `lang` source set compiles against. */
configurations.create("commonLangOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonLangOutput", langJar) }

// A Forge import compiles on a legacyForge node and throws NoClassDefFoundError on the other two loaders.
guardLoaderImports()
