package com.example.fieldnotes.mc.neoforge;

import com.crystalgraphics.mc.shared.FmlSide;
import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgraphics.mc.shared.VariantBootstrap;
import com.example.fieldnotes.FieldNotes;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;

/**
 * What NeoForge constructs, whatever Minecraft is running: the jar's one NeoForge {@code @Mod}, which picks
 * the variant for the running version and hands it the mod event bus. Names no Minecraft class.
 */
@Mod(FieldNotes.MODID)
public final class NeoForgeBootstrap {

    public NeoForgeBootstrap(IEventBus modBus) {
        String minecraft = FmlVersion.of(FMLLoader.class);
        VariantBootstrap.startCommon(NeoForgeBootstrap.class, FieldNotes.MODID, "neoforge", minecraft, modBus);
        if (FmlSide.isClient(FMLLoader.class)) {
            VariantBootstrap.startClient(NeoForgeBootstrap.class, FieldNotes.MODID, "neoforge", minecraft, modBus);
        }
    }
}
