package com.crystalgui.mc.v1710.lang;

import com.crystalgui.mc.v1710.CrystalGUI;

import cpw.mods.fml.common.Mod;

/**
 * The language mod's 1.7.10 declaration, and nothing else: the stack starts from its CrystalGraphics lifecycle
 * listener, as on every other loader ({@code docs/CGUI_LANGUAGE_STACK.md} § 1a).
 */
@Mod(
    modid = CrystalGuiLanguage.MODID,
    name = CrystalGuiLanguage.NAME,
    version = CrystalGuiLanguage.VERSION,
    dependencies = "required-after:crystalgui",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*"
)
public class CrystalGuiLanguage {

    public static final String MODID = "crystalgui_language";

    public static final String NAME = "CrystalGUI Language";

    /** The host's, deliberately: the two ship from one build and a version skew is a bug, not a state. */
    public static final String VERSION = CrystalGUI.VERSION;
}
