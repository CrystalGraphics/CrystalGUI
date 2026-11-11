import cgbuildlogic.Dependency
import cgbuildlogic.LegacyEntries
import cgbuildlogic.LoaderEntries
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Ordering
import cgbuildlogic.Side
import cgbuildlogic.Variant
import cgbuildlogic.crystalGraphicsVersion
import cgbuildlogic.has1710
import cgbuildlogic.legacyVariants
import cgbuildlogic.modernVariants
import cgbuildlogic.registerDescriptorTasks

// ── What this mod says about itself, once (J3) ───────────────────────────────────────────────────
//
// The merged jar carries one fabric.mod.json, one mods.toml and one mcmod.info between them
// describing four loaders. Hand-writing four files that have to agree is how a version gets bumped in
// three of them; these are printed from the declaration below instead.
//
// No loader module ships a descriptor of its own: every one is generated, so nothing is checked against this
// (`checkShipped = false`, as the language mod's).

val cgDescriptor = ModDescriptor(
    id = "crystalgui",
    name = "CrystalGUI",
    version = property("modVersion").toString(),
    description = "UI engine library for Minecraft mods.",
    license = "LGPL-3.0-or-later",
    dependencies = listOf(
        // AFTER, not merely required: CrystalGraphics registers the platform bundle that every
        // CrystalGUI service reads, and a UI that loads first finds no backend at all.
        // At least the version built against. A written number here outlived a release and refused
        // CrystalGraphics 0.0.1 on every Forge and NeoForge.
        Dependency("crystalgraphics", "[${crystalGraphicsVersion()},)", ordering = Ordering.AFTER),
    ),
    // 1.7.10 by hand; every other variant is a NODE of a tree, whose range and pack format are its
    // own pins (`variant.minecraft`, `variant.packFormat`) -- so adding a version adds its variant.
    variants = listOfNotNull(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgui.mc.forge.CrystalGUIForge",
            packFormat = 1,
        ).takeIf { has1710(project) },
    ) + legacyVariants(project, LegacyEntries("com.crystalgui.mc.legacy")) + modernVariants(project, mapOf(
        // No entry class: everything starts from the jar's lifecycle listeners, as the language mod's does. Each
        // loader only needs to see a mod: 1.7.10's @Mod, CrystalGUIForge, CrystalGUINeoForge, and on Fabric the
        // descriptor alone.
        "forge" to LoaderEntries("com.crystalgui.mc.forge"),
        "neoforge" to LoaderEntries("com.crystalgui.mc.neoforge"),
        // No Fabric API entry: CrystalGraphics requires it, under whichever id the version uses.
        "fabric" to LoaderEntries("com.crystalgui.mc.fabric", fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0")),
    )),
)

registerDescriptorTasks(cgDescriptor, "cgui", checkShipped = false)

// ── And what the LANGUAGE mod says about itself (J8) ─────────────────────────────────────────────
//
// A second mod from the same source tree: the grammars, the analysis engines and the scripting
// runtime, ~50 MB that a player who never writes a script does not download. It ships no per-loader
// descriptor of its own -- the fat jars are retired and only the merged jar exists -- so this
// registers the generator alone.
//
// No entry class runs anything: the stack starts from its CrystalGraphics lifecycle listener
// (META-INF/crystalgraphics/listeners), on a client behind the loading screen and on a dedicated server
// as it starts. Each loader only needs to see a mod: 1.7.10's @Mod class, the Forge and NeoForge
// bootstrappers, and on Fabric the descriptor alone. docs/CGUI_LANGUAGE_STACK.md § 1a has the whole path.
val cgLangDescriptor = ModDescriptor(
    id = "crystalgui_language",
    name = "CrystalGUI Language",
    version = property("modVersion").toString(),
    description = "Grammars, code analysis and scripting for CrystalGUI's editor.",
    license = "LGPL-3.0-or-later",
    environment = Side.CLIENT,
    dependencies = listOf(
        // Shipped together, so exactly this version's host or later.
        Dependency("crystalgui", "[${property("modVersion")},)", ordering = Ordering.AFTER),
    ),
    variants = listOfNotNull(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgui.mc.forge.lang.CrystalGUILangForge",
            packFormat = 1,
        ).takeIf { has1710(project) },
    ) + legacyVariants(project, LegacyEntries("com.crystalgui.mc.legacy")) + modernVariants(project, mapOf(
        // The host's node mixins are the host's: naming them here too is a duplicate config to Fabric.
        "forge" to LoaderEntries("com.crystalgui.mc.forge", mixins = false),
        "neoforge" to LoaderEntries("com.crystalgui.mc.neoforge", mixins = false),
        "fabric" to LoaderEntries("com.crystalgui.mc.fabric",
            fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "crystalgui" to "*"), mixins = false),
    )),
)

registerDescriptorTasks(cgLangDescriptor, "cgui-lang", name = "language", checkShipped = false)

// Read by every loader node (its dev run's own variant table) and by cg-single-jar (the entries the
// merged jars must carry) -- neither can see this script's vals.
extra["cgModDescriptors"] = mapOf("main" to cgDescriptor, "lang" to cgLangDescriptor)
