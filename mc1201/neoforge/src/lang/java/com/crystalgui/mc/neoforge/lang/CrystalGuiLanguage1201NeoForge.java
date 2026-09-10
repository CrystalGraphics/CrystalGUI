package com.crystalgui.mc.neoforge.lang;

import com.crystalgui.mc.lang.LanguageLifecycle1201;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The language stack's NeoForge 1.20.4 entry. @see CrystalGuiLanguage1201Forge for the shape; the only
 * difference is that NeoForge hands the mod bus to the constructor.
 */
@Mod(CrystalGuiLanguage1201NeoForge.MODID)
public final class CrystalGuiLanguage1201NeoForge {

    public static final String MODID = "crystalgui_language";

    public CrystalGuiLanguage1201NeoForge(IEventBus modBus) {
        // CLIENT ONLY, and structurally so: the script service registers against a live Minecraft
        // instance and a dedicated server has none. FMLClientSetupEvent fires on no server at all.
        modBus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(LanguageLifecycle1201::bootstrapClient);
    }
}
