import cgbuildlogic.Dependency
import cgbuildlogic.LoaderEntries
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Ordering
import cgbuildlogic.Side
import cgbuildlogic.Variant
import cgbuildlogic.modernVariants
import cgbuildlogic.registerDescriptorTasks

// ── What this mod says about itself, once (J3) ───────────────────────────────────────────────────
//
// The merged jar carries one fabric.mod.json, one mods.toml and one mcmod.info between them
// describing four loaders. Hand-writing four files that have to agree is how a version gets bumped in
// three of them; these are printed from the declaration below instead.
//
// The per-loader descriptors under each module's src/main/resources are still what those modules
// ship, and `checkDescriptorsAgree` is what stops them drifting from this. They are replaced by
// generated ones when the fat jars retire (J5) -- until then two jars per loader exist and both have
// to keep working.

val cgDescriptor = ModDescriptor(
    id = "crystalgui",
    name = "CrystalGUI",
    version = property("modVersion").toString(),
    description = "UI engine library for Minecraft mods.",
    license = "LGPL-3.0-or-later",
    dependencies = listOf(
        // AFTER, not merely required: CrystalGraphics registers the platform bundle that every
        // CrystalGUI service reads, and a UI that loads first finds no backend at all.
        Dependency("crystalgraphics", "[1.0.0,)", ordering = Ordering.AFTER),
    ),
    // 1.7.10 by hand; every 1.20.x variant is a NODE of the tree, whose range and pack format are its
    // own pins (`variant.minecraft`, `variant.packFormat`) -- so adding a version adds its variant.
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgui.mc.v1710.CrystalGUI",
            mixinConfigs = listOf("mixins.crystalgui.json"),
            packFormat = 1,
        ),
    ) + modernVariants(project, mapOf(
        "forge" to LoaderEntries("com.crystalgui.mc.forge", common = "com.crystalgui.mc.forge.CrystalGUIForge"),
        "neoforge" to LoaderEntries("com.crystalgui.mc.neoforge",
            common = "com.crystalgui.mc.neoforge.CrystalGUINeoForge"),
        "fabric" to LoaderEntries("com.crystalgui.mc.fabric",
            common = "com.crystalgui.mc.fabric.CrystalGUIFabricCommon",
            client = "com.crystalgui.mc.fabric.CrystalGUIFabric",
            fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "fabric-api" to "*")),
    )),
    // WHAT THE LOADER CONSTRUCTS, where that is not the variant itself. Fabric's descriptor names
    // entry points and Fabric constructs EVERY one it names, so with more than one variant it would
    // construct them all -- including the one compiled against a Minecraft that is not running. The
    // bootstrapper reads variants.json and picks. Forge, NeoForge and FML need no entry here: they
    // find their entry by scanning for @Mod, so moving the annotation is the whole of the change.
    bootstrappers = mapOf("fabric" to "com.crystalgui.mc.fabric.FabricBootstrap"),
)

registerDescriptorTasks(cgDescriptor, "cgui")

// ── And what the LANGUAGE mod says about itself (J8) ─────────────────────────────────────────────
//
// A second mod from the same source tree: the grammars, the analysis engines and the scripting
// runtime, ~50 MB that a player who never writes a script does not download. It ships no per-loader
// descriptor of its own -- the fat jars are retired and only the merged jar exists -- so this
// registers the generator alone.
//
// CLIENT, and structurally: every entry point installs against a live Minecraft instance. A dedicated
// server that finds it in `mods/` still loads the jar; nothing in it registers.
val cgLangDescriptor = ModDescriptor(
    id = "crystalgui_language",
    name = "CrystalGUI Language",
    version = property("modVersion").toString(),
    description = "Grammars, code analysis and scripting for CrystalGUI's editor.",
    license = "LGPL-3.0-or-later",
    environment = Side.CLIENT,
    dependencies = listOf(
        // AFTER, and required: this mod installs itself into seams the host owns -- CgPlatform's
        // ScriptService slot and core's command registry -- so a language stack that loads first
        // registers into nothing and reports success.
        Dependency("crystalgui", "[1.0.0,)", ordering = Ordering.AFTER),
    ),
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgui.mc.v1710.lang.CrystalGuiLanguage",
            packFormat = 1,
        ),
    ) + modernVariants(project, mapOf(
        "forge" to LoaderEntries("com.crystalgui.mc.forge",
            common = "com.crystalgui.mc.forge.lang.CrystalGuiLanguageForge"),
        "neoforge" to LoaderEntries("com.crystalgui.mc.neoforge",
            common = "com.crystalgui.mc.neoforge.lang.CrystalGuiLanguageNeoForge"),
        "fabric" to LoaderEntries("com.crystalgui.mc.fabric",
            client = "com.crystalgui.mc.fabric.lang.CrystalGuiLanguageFabric",
            fabricDepends = linkedMapOf("fabricloader" to ">=0.15.0", "crystalgui" to "*")),
    )),
    // Its own mod, its own table: the language stack selects a variant exactly as the host does and
    // shares nothing but the selector. Fabric alone needs a name here -- see the host's note above.
    bootstrappers = mapOf("fabric" to "com.crystalgui.mc.fabric.lang.LanguageFabricBootstrap"),
)

registerDescriptorTasks(cgLangDescriptor, "cgui-lang", name = "language", checkShipped = false)

// Read by every loader node (its dev run's own variant table) and by cg-single-jar (the entries the
// merged jars must carry) -- neither can see this script's vals.
extra["cgModDescriptors"] = mapOf("main" to cgDescriptor, "lang" to cgLangDescriptor)
