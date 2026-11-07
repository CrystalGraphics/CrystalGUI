package com.crystalgui.mc.forge.lang;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.ForgeStart;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The language mod's one {@code @Mod} class for every Forge, as {@code com.crystalgui.mc.forge.ForgeBootstrap}
 * is the host's: its own mod id and variant table, and the same selector. It runs nothing itself: being a mod Forge
 * lists is what lets CrystalGraphics read the jar's lifecycle listener ({@code docs/CGUI_LANGUAGE_STACK.md} § 1a).
 */
@Mod(value = LanguageForgeBootstrap.MODID, modid = LanguageForgeBootstrap.MODID, dependencies = "required-after:crystalgui",
        acceptableRemoteVersions = "*")
public final class LanguageForgeBootstrap {

    public static final String MODID = "crystalgui_language";

    private final FmlEvents legacy = ForgeStart.start(LanguageForgeBootstrap.class, MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ForgeStart.fire(legacy, event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ForgeStart.fire(legacy, event);
    }
}
