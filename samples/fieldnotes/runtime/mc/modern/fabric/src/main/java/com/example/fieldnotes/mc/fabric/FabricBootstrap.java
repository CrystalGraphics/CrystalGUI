package com.example.fieldnotes.mc.fabric;

import com.crystalgraphics.mc.shared.VariantBootstrap;
import com.example.fieldnotes.FieldNotes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What {@code fabric.mod.json} names, whatever Minecraft is running. Fabric constructs every entry point
 * its descriptor lists, so the descriptor lists this alone and it picks the variant.
 */
public final class FabricBootstrap implements ModInitializer {

    @Override
    public void onInitialize() {
        String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow(() -> new IllegalStateException("Fabric reports no `minecraft` mod container"))
                .getMetadata().getVersion().getFriendlyString();
        VariantBootstrap.startCommon(FabricBootstrap.class, FieldNotes.MODID, "fabric", minecraft, null);
    }
}
