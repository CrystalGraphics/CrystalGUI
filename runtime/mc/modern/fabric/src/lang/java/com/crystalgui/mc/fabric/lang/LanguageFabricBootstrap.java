package com.crystalgui.mc.fabric.lang;

import com.crystalgraphics.mc.shared.VariantBootstrap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What the language jar's {@code fabric.mod.json} names, whatever version it runs on.
 *
 * <p><b>Client only</b>, like the entry it constructs: the language stack declares no {@code main}
 * entrypoint at all, because the script service registers against a live Minecraft instance and a
 * dedicated server has none.</p>
 */
public final class LanguageFabricBootstrap implements ClientModInitializer {

    private static final String MODID = "crystalgui_language";

    @Override
    public void onInitializeClient() {
        VariantBootstrap.startClient(LanguageFabricBootstrap.class, MODID, "fabric",
                minecraftVersion(), null);
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow(() -> new IllegalStateException("Fabric reports no `minecraft` mod container"))
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }
}
