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
        ":runtime:mc:1710" to "reobfThinJar",
        ":runtime:mc:modern:forge" to "reobfThinShadowJar",
        ":runtime:mc:modern:neoforge" to "thinShadowJar",
        ":runtime:mc:modern:fabric" to "remapThinJar",
    ),
    // NO `:language` SINCE J8 -- it and everything under it ship as `crystalgui_language`, the second
    // pipeline registered below. That is 36 MB of the 68 this jar used to be, downloaded by everyone
    // and used by whoever writes a script.
    libraryProjects = listOf(":core", ":taffy", ":runtime:mc:shared"),

    // One owner today, and the union is still the mechanism: the language jar ships its own
    // META-INF/services, and a second jar's providers never merge into this one's file.
    serviceOwners = listOf(":core"),

    relocations = listOf(
        // Taffy is RELOCATED so this fork cannot lose a classloader race to a stock copy in another
        // mod -- our measure fix would go with it. `dev.vfyjxf.taffy.collection` rides the same rule,
        // which is why the seven vendored collections needed no relocation of their own.
        //
        // fastutil is GONE, and that was 19.65 MB of a 31.20 MB jar. @see taffy/build.gradle.kts
        "dev.vfyjxf.taffy" to "com.crystalgui.shadow.dev.vfyjxf.taffy",

        // NO JOML ROW. It used to mirror CrystalGraphics' relocation so the two mods named one
        // type; CrystalGraphics does not relocate it any more, so neither may we -- our `UINode` and
        // `ElementStyle` hold `Matrix4f` FIELDS, and a rewrite here against a plain `org.joml` there
        // would make them two unrelated types at class load.
        //
        // Neither jar carries JOML now: the game supplies it on 1.19.3+, and 1.7.10 takes
        // CrystalGraphics' `crystalgraphics-joml` companion. See D2 and its measurement.
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
    fabricThinJar = ":runtime:mc:modern:fabric" to "remapThinJar",

    // THE NOTICE TRAVELS WITH THE BINARY (G7). MIT, Apache 2.0 and the OFL each require it to reach
    // whoever receives the jar, and a file in the source repository does not. J8 moved code between
    // jars, so there is a file per jar naming what THAT jar carries.
    extraContent = {
        from(project.rootProject.file("notices/crystalgui.md")) {
            into("META-INF")
            rename { "NOTICE.md" }
        }
    },

    configureCheck = {
        forbiddenPrefixes.set(listOf(
            "META-INF/versions/",
            // Taffy unrelocated would be a split package against Minecraft's own module on Forge and
            // NeoForge, which fails module resolution before a single mod class loads.
            "dev/vfyjxf/taffy/",
            // fastutil is not relocated here, it is GONE -- vendored into dev.vfyjxf.taffy.collection.
            // Kept as a prefix so a transitive dependency cannot quietly put 19.65 MB back.
            "it/unimi/dsi/fastutil/",
            // THE J8 SPLIT, asserted rather than assumed. The whole point of the second jar is that a
            // player who never writes a script does not download the engines, so one of these
            // reappearing here is the step silently undone.
            "com/crystalgui/language/", "org/treesitter/", "assets/crystalgui/engines/",
        ))
        expectSingle.set(listOf("com/crystalgui/ui/"))
        // COUNTED BY SIMPLE NAME, so a probe class must not have a twin in another tree. J9's suffix
        // strip gave `mc1710` and `mc/modern` six pairs sharing a name -- CgUiScreen among them -- and
        // this asked for 3 copies of CgUiScreen.class and found 4. `CgUiKeybinds` has no 1.7.10
        // counterpart, so it counts the relocation and nothing else.
        relocatedClasses.set(mapOf(
            "com/crystalgui/mc/modern/platform/LifecycleCrystalGUI.class" to 3,
            "com/crystalgui/mc/modern/client/CgUiKeybinds.class" to 3,
        ))
        requiredEntries.set(listOf(
            "META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta",
            "mixins.crystalgui.json",
            "com/crystalgui/mc/shared/LoaderProbe.class",
            "com/crystalgui/mixins/CrystalGuiMixins.class",
            // Every entry point the descriptors name -- see the language jar's list for what this
            // catches. These four are each loader's own package, which no relocation touches.
            "com/crystalgui/CrystalGUI.class",
            "com/crystalgui/mc/forge/CrystalGUIForge.class",
            "com/crystalgui/mc/neoforge/CrystalGUINeoForge.class",
            "com/crystalgui/mc/fabric/CrystalGUIFabric.class",
            // G7: the notice for what THIS jar carries, in the jar.
            "META-INF/NOTICE.md",
        ))
        requiredManifest.set(mapOf(
            "FMLCorePluginContainsFMLMod" to "true",
            "ForceLoadAsMod" to "true",
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.crystalgui.json",
            "Fabric-Loom-Mixin-Remap-Type" to "",
        ))
        // core's own eight, which is the whole of what this jar contributes since J8. The language
        // stack's two providers are asserted on the LANGUAGE jar, below.
        expectServices.set(mapOf(
            "com.crystalgui.workbench.extension.WorkbenchExtension" to "com.crystalgui.workbench",
        ))
    },
))

// ── The language stack, as its own jar (J8) ─────────────────────────────────────────────────────
//
// The same pipeline, registered a second time. What it carries is what a player who never writes a
// script does not have to download: :language's classes, the six tree-sitter grammars with their JNI
// natives, and all THREE engine bands with their manifests.
//
// Three bands is ~40 MB and is the right call for THIS jar: it is downloaded for what the bands
// provide, and a band already on disk is the difference between an editor that analyses on first open
// and one that shows a progress bar.
registerSingleJarPipeline(SingleJarSpec(
    name = "language",
    modId = "crystalgui_language",
    fileName = "crystalgui-language-${project.version}.jar",
    shadePath = "com/crystalgui/lang/shadow",

    thinJars = listOf(
        ":runtime:mc:1710" to "reobfLangThinJar",
        ":runtime:mc:modern:forge" to "reobfLangThinShadowJar",
        ":runtime:mc:modern:neoforge" to "langThinShadowJar",
        ":runtime:mc:modern:fabric" to "remapLangThinJar",
    ),
    libraryProjects = listOf(":language"),
    serviceOwners = listOf(":language"),

    // ASM, AND ONLY ASM. Taffy and JOML are the host jar's; tree-sitter must NOT be relocated,
    // because a JNI symbol is named after the mangled package.
    //
    // Unrelocated ASM takes down three of the four loaders and each says something different: Fabric
    // reports a Knot/app loader constraint violation on ClassNode, and Forge and NeoForge simply die
    // after "Initialized transformers" with nothing in any log -- ModLauncher itself runs on ASM, so a
    // game-layer jar exporting org.objectweb.asm is a split package against the boot layer and the
    // module graph is refused before a mod class loads. Only 1.7.10 survives it, having no modules.
    //
    // The nested engine jars under assets/ are untouched: shadow rewrites .class entries and copies
    // everything else verbatim, which is what keeps ECJ's own string-keyed reflection working.
    relocations = listOf(
        "org.objectweb.asm" to "com.crystalgui.lang.shadow.org.objectweb.asm",
    ),

    manifest = mapOf(
        // No TweakClass and no MixinConfigs: this mod has no mixin and is not a coremod. FML 1.7.10
        // still needs to be told it holds an @Mod class, since the jar carries no mcmod.info route of
        // its own that it would find first.
        "FMLCorePluginContainsFMLMod" to true,
        "ForceLoadAsMod" to true,
        "Implementation-Version" to project.version.toString(),
        "Automatic-Module-Name" to "crystalgui_language",
    ),
    fabricThinJar = ":runtime:mc:modern:fabric" to "remapLangThinJar",
    descriptorsTask = "generateLanguageDescriptors",

    extraContent = {
        // THE NOTICE, in the binary (G7). Most of this jar by weight is somebody else's work, and
        // EPL-2.0 and MPL-2.0 both require the notice to reach whoever receives it.
        from(project.rootProject.file("notices/crystalgui-language.md")) {
            into("META-INF")
            rename { "NOTICE.md" }
        }

        // The tree-sitter jars go in VERBATIM and are never relocated: each carries the JNI natives
        // for its grammar, and a JNI symbol is named after the mangled package -- renaming it renames
        // the symbol the .dll does not export, and the first parser built throws UnsatisfiedLinkError.
        project.rootProject.file("lib/tree-sitter").listFiles()
            ?.filter { it.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.forEach { from(project.zipTree(it)) }

        // ALL THREE BANDS and their manifests. 8 is what a 1.7.10 client runs, 17 what 1.20.x does,
        // and 11 what a 1.7.10 client on lwjgl3ify may. Taken from the two loaders that already
        // resolve them rather than re-resolved here.
        listOf(":runtime:mc:1710" to "bundleEngineBands", ":runtime:mc:modern:forge" to "bundleEngineBands",
               ":runtime:mc:1710" to "writeEngineManifests", ":runtime:mc:modern:forge" to "writeEngineManifests")
            .forEach { (path, task) ->
                val producer = project.project(path).tasks.named(task)
                dependsOn(producer)
                from(producer)
            }
    },

    configureCheck = {
        forbiddenPrefixes.set(listOf(
            "META-INF/versions/",
            // THE SPLIT, from the other side: the engine and the workbench are the host jar's, and a
            // copy here is two definitions of every UI class across two mods.
            "com/crystalgui/ui/", "com/crystalgui/widget/", "com/crystalgui/style/",
            "com/crystalgui/workbench/", "com/crystalgui/desktop/",
            "it/unimi/dsi/fastutil/", "dev/vfyjxf/taffy/", "org/joml/",
            // Unrelocated ASM is a split package against ModLauncher's own, and three of the four
            // loaders die before a mod class loads -- two of them with nothing in any log.
            "org/objectweb/asm/",
        ))
        expectSingle.set(listOf("com/crystalgui/language/"))
        // Counted by simple name, as the host jar's own note explains. NOT `ScriptService`: J9's
        // suffix strip renamed the 1.20.x installer `ScriptService1201` -> `ScriptService`, which is
        // the simple name of the SPI it installs (`com.crystalgui.language.platform.ScriptService`),
        // so this counted 4. `LanguageLifecycle` is unique. The name clash itself is a readability
        // defect rather than a functional one -- the two are in different packages -- but the host
        // half wants a name of its own; `ModernScriptService` is what the plan asked for.
        relocatedClasses.set(mapOf(
            "com/crystalgui/mc/modern/lang/LanguageLifecycle.class" to 3,
        ))
        requiredEntries.set(listOf(
            "META-INF/mods.toml", "fabric.mod.json", "mcmod.info", "pack.mcmeta",
            // EVERY ENTRY POINT THE DESCRIPTORS NAME. A descriptor naming a class that is not here is
            // a crash at mod construction on Forge and a hard loader error on Fabric, and nothing else
            // in this build looks: a rename that updated the classes and mangled the descriptor
            // strings passed every other check and produced a jar whose Fabric entrypoint did not
            // exist. Only the `com.crystalgui.mc.lang` half is relocated, so the three loader entries
            // keep their own package.
            "com/crystalgui/mc/lang/CrystalGuiLanguage.class",
            "com/crystalgui/mc/forge/lang/CrystalGuiLanguageForge.class",
            "com/crystalgui/mc/neoforge/lang/CrystalGuiLanguageNeoForge.class",
            "com/crystalgui/mc/fabric/lang/CrystalGuiLanguageFabric.class",
            // G7: the notice for what THIS jar carries, in the jar.
            "META-INF/NOTICE.md",
            "assets/crystalgui/engines/8/index.txt",
            "assets/crystalgui/engines/11/index.txt",
            "assets/crystalgui/engines/17/index.txt",
        ))
        requiredManifest.set(mapOf(
            "FMLCorePluginContainsFMLMod" to "true",
            "ForceLoadAsMod" to "true",
            "Fabric-Loom-Mixin-Remap-Type" to "",
        ))
        expectServices.set(mapOf(
            "com.crystalgui.text.syntax.LanguageKinds" to "com.crystalgui.language.LanguageStack",
            "com.crystalgui.workbench.extension.WorkbenchExtension" to "com.crystalgui.language",
        ))
    },
))

dependencies {
    // ASM, which :language's `.map` reads class bytes with and which reached the merged jar through no
    // route at all before J8: the fat 1.7.10 jar declared it `shadowImplementation` and the merge takes
    // thin jars, which carry no dependencies. It belongs to this jar because every reference to it is
    // inside :language.
    "languageJarLibs"("org.ow2.asm:asm:${property("asmVersion")}")
    "languageJarLibs"("org.ow2.asm:asm-commons:${property("asmVersion")}")
    "languageJarLibs"("org.ow2.asm:asm-tree:${property("asmVersion")}")
}

// `singleJarLibs` is deliberately EMPTY. It held fastutil until 2026-09-10 -- 19.65 MB of a 31.20 MB
// jar, 12,808 of 15,892 entries, for the seven collections Taffy names. Those are vendored into
// `dev.vfyjxf.taffy.collection` now, so the host jar carries no third-party library at all beyond the
// engine's own code. @see taffy/build.gradle.kts
