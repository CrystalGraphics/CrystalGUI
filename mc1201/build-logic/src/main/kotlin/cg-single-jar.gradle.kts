import cgbuildlogic.SingleJarSpec
import cgbuildlogic.registerSingleJarPipeline

// ── One jar for every loader (J4) ────────────────────────────────────────────────────────────────
//
// Four thin jars, each this loader's own classes at its own production names, plus the engine and its
// libraries ONCE. Every loader reads its own descriptor, constructs its own entry class, and never
// defines a class from another variant -- so those classes' references to a Minecraft it is not
// running are never resolved.
//
// The PIPELINE is shared with every project on this build and lives in
// CrystalGraphics/singlejar-logic; what is here is the part that is CrystalGUI's own.

plugins {
    // The root coordinates rather than compiles, so it has no `assemble` of its own until this.
    // `java` rather than `base`, because jvmdg's DowngradeJar reads `sourceSets` when it is created
    // -- for a default classpath this task overrides anyway.
    java
    id("com.gradleup.shadow")
    id("xyz.wagyourtail.jvmdowngrader")
}

// Nothing compiles here. The java plugin's own jar would be an empty artifact beside the real one.
tasks.named<Jar>("jar") { enabled = false }

// The root carries no version of its own, and the artifact is named after it.
group = property("modGroup").toString()
version = property("modVersion").toString()

// jvmdg's own conventions are for a module that compiles; this project compiles nothing.
jvmdg.defaultShadeTask { enabled = false }
jvmdg.defaultTask { enabled = false }
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)

repositories {
    mavenCentral()
}

// Captured at SCRIPT scope: inside a task-configuration block `property(...)` resolves against the
// TASK, not the project, and fails with "unknown property 'modId'".
val singleJarModId = property("modId").toString()

registerSingleJarPipeline(SingleJarSpec(
    modId = singleJarModId,
    fileName = "$singleJarModId-${project.version}.jar",
    // NAMED, never defaulted: jvmdg's default is the archive base name, and a hyphenated one is not a
    // legal package identifier, so the module system rejects the jar before the early display.
    shadePath = "com/crystalgui/shadow",

    // Named per loader because each toolchain names its own production step: ModDevGradle's
    // `reobfuscate` derives `reobfThinShadowJar` from the task it consumes, Loom's remap task is the
    // one registered by name, NeoForge needs no mapping step at all, and 1.7.10's is registered here.
    thinJars = listOf(
        ":mc1710" to "reobfThinJar",
        ":mc1201:forge" to "reobfThinShadowJar",
        ":mc1201:neoforge" to "thinShadowJar",
        ":mc1201:fabric" to "remapThinJar",
    ),
    libraryProjects = listOf(":core", ":language", ":taffy", ":mc-shared"),

    // `core` and `language` each ship a WorkbenchExtension file, and a copy keeps whichever arrived
    // first while silently dropping the other -- the jar then carries eight extensions and loses the
    // Run panel, with nothing reporting it.
    serviceOwners = listOf(":core", ":language"),

    relocations = listOf(
        // fastutil is Taffy's and is RELOCATED: nothing outside this jar sees those types, and
        // Minecraft 1.20.x ships its own copy that a second unrelocated one would collide with as a
        // split package. 1.7.10 has neither library, so the union ships and relocation makes it safe.
        "dev.vfyjxf.taffy" to "com.crystalgui.shadow.dev.vfyjxf.taffy",
        "it.unimi.dsi.fastutil" to "com.crystalgui.shadow.it.unimi.dsi.fastutil",

        // JOML: THE SAME REWRITE CrystalGraphics APPLIES, over no classes of ours.
        //
        // CrystalGraphics ships the one copy, relocated, because a jar containing `org/joml` is a
        // split package against Minecraft's own module on Forge and NeoForge, and 1.7.10 has no JOML
        // at all. Its types cross the boundary between the two mods -- `UINode` and `ElementStyle`
        // hold `Matrix4f` FIELDS -- so this jar's references have to be rewritten to the same name or
        // they are two unrelated types. Safe because no loader host of ours names JOML, so nothing
        // hands a matrix to or from Minecraft. J9 replaces both with a vendored math package.
        "org.joml" to "com.crystalgraphics.shadow.org.joml",
    ),

    // What each loader reads out of the manifest, in one manifest.
    //
    // FML 1.7.10 needs all four of its lines: a jar carrying a TweakClass is a coremod unless
    // ForceLoadAsMod says otherwise, and MixinConfigs is its ONLY channel for a mixin config.
    // ModLauncher reads MixinConfigs too, which is exactly why the config plugin gates by loader.
    manifest = mapOf(
        "FMLCorePluginContainsFMLMod" to true,
        "ForceLoadAsMod" to true,
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.crystalgui.json",
        // mods.toml says ${file.jarVersion}, which FML reads from here.
        "Implementation-Version" to project.version.toString(),
        "Automatic-Module-Name" to singleJarModId,
    ),
    fabricThinJar = ":mc1201:fabric" to "remapThinJar",

    extraContent = {
        // The tree-sitter jars go in VERBATIM and are never relocated: each carries the JNI natives
        // for its grammar, and a JNI symbol is named after the mangled package -- renaming it renames
        // the symbol the .dll does not export, and the first parser built throws UnsatisfiedLinkError.
        project.rootProject.file("lib/tree-sitter").listFiles()
            ?.filter { it.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.forEach { from(project.zipTree(it)) }

        // Both bands: 8 is what a 1.7.10 client runs and 17 what 1.20.x does, and one jar serves
        // both. Taken from the two loaders that already bundle them rather than re-resolved here.
        listOf(":mc1710" to "bundleEngineBands", ":mc1201:forge" to "bundleEngineBands",
               ":mc1710" to "writeEngineManifests", ":mc1201:forge" to "writeEngineManifests")
            .forEach { (path, task) ->
                val producer = project.project(path).tasks.named(task)
                dependsOn(producer)
                from(producer)
            }
    },

    configureCheck = {
        forbiddenPrefixes.set(listOf(
            "META-INF/versions/",
            // Unrelocated, these are split packages against Minecraft's own modules on Forge and
            // NeoForge, which fails module resolution before a single mod class loads.
            "it/unimi/dsi/fastutil/", "dev/vfyjxf/taffy/",
        ))
        expectSingle.set(listOf("com/crystalgui/ui/", "com/crystalgui/language/"))
        relocatedClasses.set(mapOf(
            "com/crystalgui/mc/platform/Lifecycle1201.class" to 3,
            "com/crystalgui/mc/client/CgUiScreen1201.class" to 3,
        ))
        requiredEntries.set(listOf(
            "META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta",
            "mixins.crystalgui.json",
            "com/crystalgui/mc/shared/LoaderProbe.class",
            "com/crystalgui/mixins/CrystalGuiMixins.class",
        ))
        requiredManifest.set(mapOf(
            "FMLCorePluginContainsFMLMod" to "true",
            "ForceLoadAsMod" to "true",
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.crystalgui.json",
            "Fabric-Loom-Mixin-Remap-Type" to "",
        ))
        expectServices.set(mapOf(
            "com.crystalgui.workbench.extension.WorkbenchExtension" to "com.crystalgui.language",
            "com.crystalgui.text.syntax.LanguageKinds" to "com.crystalgui.language.LanguageStack",
        ))
    },
))

dependencies {
    "singleJarLibs"("it.unimi.dsi:fastutil:${property("fastutil_version")}")
}
