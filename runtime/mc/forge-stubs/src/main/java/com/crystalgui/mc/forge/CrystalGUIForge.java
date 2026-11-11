package com.crystalgui.mc.forge;

import com.crystalgraphics.mc.shared.ForgeStart;

import net.minecraftforge.fml.common.Mod;

/**
 * CrystalGUI's one {@code @Mod} class for every Forge the jar runs on, FML 1.7.10, legacy FML 1.8–1.12.2 and modern
 * Forge alike: what makes it a mod Forge lists, so CrystalGraphics reads its lifecycle listeners
 * ({@code META-INF/crystalgraphics/listeners}), which start everything. It runs nothing itself; {@link ForgeStart}
 * refuses a version the jar does not support, and on 1.7.10 {@code acceptedMinecraftVersions} does.
 *
 * <ul>
 *   <li>Two annotations: FML 1.7.10 scans for {@code cpw.mods.fml.common.Mod}, every later Forge for
 *       {@code net.minecraftforge.fml.common.Mod}. Each reads its own and ignores the other.</li>
 *   <li>No {@code @Mod.EventHandler}: FML 1.7.10 lists this class's methods, and legacy FML's event types do not exist
 *       there.</li>
 *   <li>A second {@code @Mod} of this id anywhere in the jar is a second mod to Forge's scanner.</li>
 * </ul>
 */
// The two annotations share a simple name, so 1.7.10's is spelled out.
@cpw.mods.fml.common.Mod(modid = CrystalGUIForge.MODID, name = CrystalGUIForge.NAME,
        dependencies = "required-after:crystalgraphics", acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
@Mod(value = CrystalGUIForge.MODID, modid = CrystalGUIForge.MODID, dependencies = "required-after:crystalgraphics",
        acceptableRemoteVersions = "*")
public final class CrystalGUIForge {

    public static final String MODID = "crystalgui";

    public static final String NAME = "CrystalGUI";

    public CrystalGUIForge() {
        ForgeStart.start(CrystalGUIForge.class, MODID);
    }
}
