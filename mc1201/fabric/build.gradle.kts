@file:Suppress("UnstableApiUsage")

plugins {
    id("cg-mc1201-loader")
    // Upstream fabric-loom 1.15.x supports Gradle 9.x (1.16 requires 9.4+, 1.15 works on 9.0+).
    // Architectury-loom 1.14.473 was replaced because its Forge mode uses detachedConfiguration
    // resolution without an exclusive lock — a Gradle 9 hard error (not fixable via properties).
    // fabric-loom 1.16.x requires Gradle 9.4+ — that's where the runtimeClasspath
    // exclusive-lock fix lives (1.15.x still triggers it via the jvmArguments getter).
    id("fabric-loom") version "1.16.2"
    id("com.gradleup.shadow") // version pinned in settings.gradle.kts pluginManagement
}

group = property("modGroup").toString()
version = property("modVersion").toString()
base { archivesName.set("crystalgui-mc1201-fabric") }

// Adds CrystalGraphics compile-time deps (core, platform, mc1201-common) via composite substitution.
apply(from = rootProject.file("gradle/module_integration/integration.gradle.kts").toURI())

// The producing task is wired in below: a bare path serves whatever happens to sit on disk.
val crystalGraphicsBuild = gradle.includedBuild("CrystalGraphics")
val crystalGraphicsMod = fileTree(crystalGraphicsBuild.projectDir.resolve("mc1201/fabric/build/libs")) {
    include("crystalgraphics-mc1201-fabric-*.jar")
    exclude("*-sources.jar", "*-all.jar", "*-dev.jar", "*-java*.jar")
}

tasks.matching { it.name in setOf("runClient", "runServer") }.configureEach {
    dependsOn(crystalGraphicsBuild.task(":mc1201:fabric:remapJar"))
}

dependencies {
    minecraft("com.mojang:minecraft:${property("mc1201.minecraft")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${property("mc1201.parchment.mc")}:${property("mc1201.parchment")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${property("mc1201.fabric.loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("mc1201.fabric.api")}")

    // CRYSTALGRAPHICS, AS A MOD. It registers CgPlatform from its own entrypoint and nothing here
    // does it, so without this every class resolves and the desktop paints nothing. One dependency
    // covers everything: that jar already bundles platform, core and freetype the same way this
    // module bundles :core and :mc1201:common below, so there is no library half to add separately
    // and nothing is shipped twice.
    //
    // Named as a FILE, because the obvious form does not work. Adding a composite substitution for
    // com.crystalgraphics:crystalgraphics-mc1201-fabric and depending on that coordinate was tried:
    // Loom derives a remapped dependency's coordinate from the PROJECT NAME, so it went looking for
    // "com.crystalgraphics:fabric", which exists nowhere. Loom's remapJar output is what a mod
    // dependency must be -- intermediary namespace -- and modLocalRuntime is what maps it back to
    // named for a dev run; a plain runtimeOnly would put an intermediary mod on a named classpath and
    // fail at class load rather than at resolution. Local, because this is how a dev run finds
    // CrystalGraphics and not something a published POM should demand.
    modLocalRuntime(crystalGraphicsMod)
}

loom {
    runs {
        named("client") { runDir("runs/client") }
        named("server") { runDir("runs/server") }
    }
}

// `${version}` in fabric.mod.json is a GRADLE placeholder and nothing at runtime expands it.
//
// Forge's `${file.jarVersion}` is read by FML from the jar manifest; Fabric has no such thing, so an
// unexpanded fabric.mod.json ships the six literal characters. Fabric Loader does not reject it -- it
// falls back to a StringVersion -- so the mod loads and the failure is confined to what reads the
// version: the mod list shows `${version}`, and any dependency range another mod declares on this id
// can never be satisfied.
tasks.processResources {
    inputs.property("modVersion", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

// -- The SHIPPED jar is the SHADED SHADOW jar, remapped ------------------------------------------
//
// Loom remaps `jar` by convention, and `jar` is this module's two loader classes. That produced a
// 9.2 MB mod carrying core and language and NOT taffy, tree-sitter, the engine bands or jvmdg's
// stubs -- against 30 MB on Forge. It installs, Knot accepts it, and the first widget constructed
// dies on a NodeId field descriptor. The shadow pipeline is where all of that is assembled, so the
// shipped jar has to come off the END of it: shadowJar -> downgradeShadowJar -> shade -> remap.
//
// Remap LAST, for the reason forge reobfuscates last: a remapper rewrites names, so it has to run on
// the class files that will actually ship -- after jvmdg has rewritten Java 21 constructs and after
// its stubs have been shaded in.
//
// `inputFile` is a CONVENTION on Loom's task (RemapTaskConfiguration), so setting it replaces the
// default cleanly; this is the documented Loom+Shadow pattern with two stages in between. The
// dependency comes with the provider.
//
// `jar` keeps its own contents. It used to bundle core, common and language on the belief that
// "Loom uses the remapped JAR as the mod's classpath when running the dev client" -- it does not:
// `:mc1201:fabric:runClient --dry-run` builds neither `jar` nor `remapJar`. A dev run resolves from
// runtimeClasspath and the source-set outputs, so that copy served the shipped jar alone, and the
// shipped jar now comes from a pipeline that assembles far more than it did.
val shadedShadowJar = tasks.named<AbstractArchiveTask>("shadeDowngradedShadowJar")

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(shadedShadowJar.flatMap { it.archiveFile })
}

// -- The thin jar, remapped (J1) -------------------------------------------------------------------
//
// A SECOND remap task rather than a reconfiguration of the first: `remapJar` produces the fat jar
// this loader ships today, and both artifacts have to keep building until the root merge replaces
// the fat one. Remapping is what makes a thin jar production-shaped here, exactly as reobfuscation
// does on Forge -- intermediary is what a Fabric mod's class references must be.
val remapThinJar = tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapThinJar") {
    group = "build"
    description = "The thin jar at intermediary names -- the merge's input from this loader."
    inputFile.set(tasks.named<AbstractArchiveTask>("thinShadowJar").flatMap { it.archiveFile })
    archiveClassifier.set("thin")
}

// Registered by cg-mc1201-loader with what a CrystalGUI thin jar may contain; only the jar is ours.
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") {
    jar.set(remapThinJar.flatMap { it.archiveFile })
}
tasks.named("assemble") { dependsOn(remapThinJar) }

// Extracts Fabric MC 1.20.1 sources and resources into build/mc-src for local navigation.
// Sync (not Copy) removes stale files when jars change between toolchain version bumps.
val extractMcSources by tasks.registering(Sync::class) {
    description = "Extracts Fabric MC 1.20.1 sources and resources into build/mc-src for local navigation."
    group = "crystalgui"

    // genSourcesWithVineflower is Loom's decompile task. dependsOn ensures it runs before extraction.
    // Running this task triggers Vineflower decompilation — may take several minutes on first run.
    //
    // Use the typed GenerateSourcesTask so we can access sourcesOutputJar directly.
    // task.outputs.files.singleFile would throw because GenerateSourcesTask also declares a
    // @LocalState working directory, giving it more than one output file in total.
    val genSources = tasks.named(
        "genSourcesWithVineflower",
        net.fabricmc.loom.task.GenerateSourcesTask::class
    )
    dependsOn(genSources)

    // sourcesOutputJar is the @OutputFile declared by GenerateSourcesTask — the canonical way
    // to consume it without spelunking the loom cache path (which is hash-named).
    val sourcesJar = genSources.flatMap { it.sourcesOutputJar }
    from(zipTree(sourcesJar)) { into("java") }

    // Resources — filter non-class, non-META-INF content from the merged binary jar.
    // Loom publishes the named+merged jar to its local maven under "minecraft-merged" — it
    // lands on the compileClasspath. configurations["minecraft"] is Declarable-only in Gradle 9
    // (resolvedConfiguration() is not permitted on it), so we filter compileClasspath instead.
    // provider {} keeps the resolution lazy — executed only at task execution time.
    val mergedJar = provider {
        configurations["compileClasspath"].resolvedConfiguration.resolvedArtifacts
            .first { it.file.name.startsWith("minecraft-merged") }
            .file
    }
    from(zipTree(mergedJar)) {
        into("resources")
        exclude("**/*.class")
        exclude("META-INF/**")
    }

    into(layout.buildDirectory.dir("mc-src"))
}

// Wire into ideaSyncTask only — NOT classes.
// genSourcesWithVineflower (which extractMcSources depends on) is an optional dev task; forcing
// it on classes would add 2-5 minutes of Vineflower decompilation to every fresh-clone build.
// ideaSyncTask is Loom's dedicated IDE sync hook — the right moment for one-time source gen.
// CLI users who want sources without IDE sync: ./gradlew :mc1201:fabric:extractMcSources
tasks.named("ideaSyncTask") { dependsOn(extractMcSources) }

// CrystalGraphics ships as its OWN MOD on this loader, so its classes must not also be on the system
// classpath. Knot loads a mod jar's classes itself, so a class present in both places exists TWICE --
// and com.crystalgraphics.platform.CgPlatform holds the platform bundle in a static. CrystalGraphics
// registered into its copy and CrystalGUI read the other, so a dedicated server reported
// "CgPlatform not yet registered" one line after the log said it had registered.
//
// Only runtimeClasspath: compileOnly still needs them, and forge/neoforge take theirs from a classpath
// rather than a mod jar, so this is fabric's alone.
configurations.named("runtimeClasspath") { exclude(group = "com.crystalgraphics") }


// The per-loader `deployMods` is retired (J7): the root `deploySingleJars` installs the one artifact
// into all four instances.
