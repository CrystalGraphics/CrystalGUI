package com.crystalgui.mc.forge.lang;

import com.crystalgraphics.mc.shared.ForgeStart;

import net.minecraftforge.fml.common.Mod;

/**
 * The language mod's one {@code @Mod} class for every Forge, FML 1.7.10 included, as
 * {@code com.crystalgui.mc.forge.CrystalGUIForge} is the host's: its own mod id and variant table, and the same
 * selector. It runs nothing itself: being a mod Forge lists is what lets CrystalGraphics read the jar's lifecycle
 * listener ({@code docs/CGUI_LANGUAGE_STACK.md} § 1a). No {@code @Mod.EventHandler}, for the reason
 * {@code CrystalGUIForge} gives.
 */
// The two annotations share a simple name, so 1.7.10's is spelled out.
@cpw.mods.fml.common.Mod(modid = CrystalGUILangForge.MODID, name = CrystalGUILangForge.NAME,
        dependencies = "required-after:crystalgui", acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
@Mod(value = CrystalGUILangForge.MODID, modid = CrystalGUILangForge.MODID, dependencies = "required-after:crystalgui",
        acceptableRemoteVersions = "*")
public final class CrystalGUILangForge {

    public static final String MODID = "crystalgui_language";

    public static final String NAME = "CrystalGUI Language";

    public CrystalGUILangForge() {
        ForgeStart.start(CrystalGUILangForge.class, MODID);
    }
}
