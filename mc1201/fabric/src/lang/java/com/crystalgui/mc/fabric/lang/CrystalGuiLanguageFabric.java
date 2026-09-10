package com.crystalgui.mc.fabric.lang;

import com.crystalgui.mc.modern.lang.LanguageLifecycle;

import net.fabricmc.api.ClientModInitializer;

/**
 * The language stack's Fabric 1.20.1 entry. Named as this mod's {@code client} entrypoint in
 * {@code fabric.mod.json}, which is what keeps it off a dedicated server.
 */
public final class CrystalGuiLanguageFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        LanguageLifecycle.bootstrapClient();
    }
}
