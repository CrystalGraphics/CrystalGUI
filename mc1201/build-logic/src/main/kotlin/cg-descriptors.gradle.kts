import cgbuildlogic.Dependency
import cgbuildlogic.ModDescriptor
import cgbuildlogic.Ordering
import cgbuildlogic.Side
import cgbuildlogic.Variant
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
    variants = listOf(
        Variant(
            loader = "fml1710", minecraft = "[1.7.10]", era = "1710",
            commonEntry = "com.crystalgui.CrystalGUI",
            mixinConfigs = listOf("mixins.crystalgui.json"),
            packFormat = 1,
        ),
        Variant(
            loader = "forge", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgui.mc.forge.CrystalGUI1201Forge",
            packFormat = 15,
        ),
        Variant(
            loader = "neoforge", minecraft = "[1.20.4,1.21)", era = "modern",
            commonEntry = "com.crystalgui.mc.neoforge.CrystalGUI1201NeoForge",
            packFormat = 22,
        ),
        Variant(
            loader = "fabric", minecraft = "[1.20.1,1.21)", era = "modern",
            commonEntry = "com.crystalgui.mc.fabric.CrystalGUI1201FabricCommon",
            clientEntry = "com.crystalgui.mc.fabric.CrystalGUI1201Fabric",
            fabricDepends = linkedMapOf(
                "fabricloader" to ">=0.15.0",
                "minecraft" to "~1.20.1",
                "fabric-api" to "*",
            ),
            packFormat = 15,
        ),
    ),
)

registerDescriptorTasks(cgDescriptor, "cgui")
