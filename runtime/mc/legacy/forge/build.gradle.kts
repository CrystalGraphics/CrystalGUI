// The legacy tree's one branch -- Forge 1.8 to 1.12.2, a node per SRG plateau. Real mode compiles
// through Unimined's FG2 support at MCP names; stub mode against the database. @see cgbuildlogic.LegacyTree

import cgbuildlogic.registerMcpReobf
import cgbuildlogic.registerThinRename
import cgbuildlogic.stubMode
import xyz.wagyourtail.unimined.api.UniminedExtension

plugins {
    id("cg-legacy-loader")
    id("com.gradleup.shadow")
    // Applied on a real node only: a stub build must not resolve Minecraft.
    id("xyz.wagyourtail.unimined") version "1.4.1" apply false
}

if (!stubMode) {
    apply(plugin = "xyz.wagyourtail.unimined")
    val (channel, mappings) = property("mcp.mappings").toString().split(':', limit = 2)
    the<UniminedExtension>().minecraft {
        version(property("mc.version").toString())
        mappings {
            searge()
            mcp(channel, mappings)
        }
        minecraftForge { loader(property("forge.version").toString()) }
        defaultRemapJar = false
    }
    // Unimined attaches Minecraft to `main` alone; the language mod's source set needs it too.
    the<UniminedExtension>().minecraft(sourceSets["lang"]) { combineWith(sourceSets.main.get()) }
}

// Each merge's input from this node: its classes at the SRG members FML runs, class names kept.
val main = sourceSets.main.get()
registerThinRename("langThinShadowJar", "lang-thin") {
    registerMcpReobf("langThinShadowJar", "lang-thin", main.compileClasspath)
}
val thinJar = registerThinRename("thinShadowJar", "thin") {
    registerMcpReobf("thinShadowJar", "thin", main.compileClasspath)
}
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") { jar.set(thinJar.flatMap { it.archiveFile }) }
tasks.named("assemble") { dependsOn(thinJar, "reobfLangThinShadowJar") }
