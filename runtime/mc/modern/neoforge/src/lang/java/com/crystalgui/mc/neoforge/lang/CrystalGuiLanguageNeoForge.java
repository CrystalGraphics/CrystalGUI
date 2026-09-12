package com.crystalgui.mc.neoforge.lang;

import com.crystalgui.mc.modern.lang.LanguageLifecycle;

import net.neoforged.bus.api.IEventBus;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The language stack's NeoForge 1.20.4 entry. @see CrystalGuiLanguageForge for the shape; the only
 * difference is that NeoForge hands the mod bus to the constructor.
 */
public final class CrystalGuiLanguageNeoForge implements VariantEntry {

    public static final String MODID = "crystalgui_language";

    /** @param context the {@code IEventBus} from {@code LanguageNeoForgeBootstrap}. */
    @Override
    public void start(Object context) {
        IEventBus modBus = (IEventBus) context;
        // CLIENT ONLY, and structurally so: the script service registers against a live Minecraft
        // instance and a dedicated server has none. FMLClientSetupEvent fires on no server at all.
        modBus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(LanguageLifecycle::bootstrapClient);
    }
}
