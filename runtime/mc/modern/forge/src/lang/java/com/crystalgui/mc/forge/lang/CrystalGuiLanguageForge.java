package com.crystalgui.mc.forge.lang;

import com.crystalgui.mc.modern.lang.LanguageLifecycle;

import com.crystalgraphics.mc.shared.VariantEntry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * The language stack's Forge 1.20.1 entry — twenty lines, which is the whole point of the source-set
 * layout: a new era costs one of these per loader rather than a module per loader.
 *
 * <p>Everything it does is in {@link LanguageLifecycle}, shared with the other two 1.20.x loaders.
 * Ordered after {@code crystalgui} by {@code mods.toml}, so {@code CgPlatform} and the command registry
 * are already up.</p>
 */
public final class CrystalGuiLanguageForge implements VariantEntry {

    public static final String MODID = "crystalgui_language";

    /** @param context Forge's {@link FMLJavaModLoadingContext}, from {@code LanguageForgeBootstrap}. */
    @Override
    public void start(Object context) {
        // CLIENT ONLY, and structurally so: the script service registers against a live Minecraft
        // instance and a dedicated server has none. FMLClientSetupEvent fires on no server at all,
        // which is a stronger guarantee than a Dist check somebody has to remember to write.
        // Forge 56's EventBus 7 hands a mod-bus event's bus out per bus group.
        //? if >=1.21.6 {
        /*FMLClientSetupEvent.getBus(((FMLJavaModLoadingContext) context).getModBusGroup()).addListener(this::clientSetup);
        *///?} else {
        ((FMLJavaModLoadingContext) context).getModEventBus().addListener(this::clientSetup);
        //?}
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(LanguageLifecycle::bootstrapClient);
    }
}
