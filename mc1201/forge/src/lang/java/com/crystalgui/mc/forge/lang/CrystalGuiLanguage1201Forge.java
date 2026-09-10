package com.crystalgui.mc.forge.lang;

import com.crystalgui.mc.lang.LanguageLifecycle1201;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * The language stack's Forge 1.20.1 entry — twenty lines, which is the whole point of the source-set
 * layout: a new era costs one of these per loader rather than a module per loader.
 *
 * <p>Everything it does is in {@link LanguageLifecycle1201}, shared with the other two 1.20.x loaders.
 * Ordered after {@code crystalgui} by {@code mods.toml}, so {@code CgPlatform} and the command registry
 * are already up.</p>
 */
@Mod(CrystalGuiLanguage1201Forge.MODID)
public final class CrystalGuiLanguage1201Forge {

    public static final String MODID = "crystalgui_language";

    public CrystalGuiLanguage1201Forge() {
        // CLIENT ONLY, and structurally so: the script service registers against a live Minecraft
        // instance and a dedicated server has none. FMLClientSetupEvent fires on no server at all,
        // which is a stronger guarantee than a Dist check somebody has to remember to write.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(LanguageLifecycle1201::bootstrapClient);
    }
}
