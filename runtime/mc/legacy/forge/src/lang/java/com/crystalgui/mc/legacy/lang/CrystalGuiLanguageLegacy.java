package com.crystalgui.mc.legacy.lang;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.language.LanguageHost;
import com.crystalgui.mc.launchwrapper.LaunchWrapperLanguageProbe;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The language mod on Forge 1.8–1.12.2: installs the {@link ScriptServiceLegacy} and starts the stack,
 * in {@link LanguageHost}'s order. Nothing calls into this jar; the host runs without it.
 */
public final class CrystalGuiLanguageLegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> preInit((FMLPreInitializationEvent) event));
        events.on("FMLInitializationEvent", event -> LanguageHost.start(isClient()));
    }

    private static void preInit(FMLPreInitializationEvent event) {
        // Forge's config directory's parent is the installation, on both sides.
        LanguageHost.install(new ScriptServiceLegacy(event.getModConfigurationDirectory().getParentFile()));
        if (isClient()) LaunchWrapperLanguageProbe.register();
    }

    private static boolean isClient() {
        return FMLCommonHandler.instance().getSide().isClient();
    }
}
