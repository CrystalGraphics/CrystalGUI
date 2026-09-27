package com.crystalgui.mc.v1710.lang;

import com.crystalgui.language.LanguageHost;
import com.crystalgui.mc.launchwrapper.LaunchWrapperLanguageProbe;
import com.crystalgui.mc.v1710.CrystalGUI;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * The language stack's 1.7.10 mod container — grammars, analysis and scripting, as their own download.
 *
 * <p>Nothing calls into this jar: it installs itself, through seams {@code core} owns, in
 * {@link LanguageHost}'s order. A host without it registers no {@code ScriptService}, colours from the
 * built-in lexers and says so in the log.</p>
 *
 * <p><b>{@code required-after:crystalgui}</b> — the host's {@code CgPlatform} and its command registry
 * have to exist before anything here is provided, and {@code after} is what orders two FML mods.</p>
 */
@Mod(
    modid = CrystalGuiLanguage.MODID,
    name = CrystalGuiLanguage.NAME,
    version = CrystalGuiLanguage.VERSION,
    dependencies = "required-after:crystalgui",
    acceptedMinecraftVersions = "[1.7.10]"
)
public class CrystalGuiLanguage {

    public static final String MODID = "crystalgui_language";

    public static final String NAME = "CrystalGUI Language";

    /** The host's, deliberately: the two ship from one build and a version skew is a bug, not a state. */
    public static final String VERSION = CrystalGUI.VERSION;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Forge hands over its own config directory; the game directory is its parent, on both sides.
        // This is the only event that carries it.
        LanguageHost.install(new ScriptService1710(event.getModConfigurationDirectory().getParentFile()));
        if (isClient()) LaunchWrapperLanguageProbe.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LanguageHost.start(isClient());
    }

    private static boolean isClient() {
        return FMLCommonHandler.instance().getSide() == Side.CLIENT;
    }
}
