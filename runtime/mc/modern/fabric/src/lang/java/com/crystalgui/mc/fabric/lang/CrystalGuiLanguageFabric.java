package com.crystalgui.mc.fabric.lang;

import com.crystalgui.mc.modern.lang.LanguageLifecycle;

import com.crystalgraphics.mc.shared.VariantEntry;

/**
 * The language stack's Fabric 1.20.1 entry. Named as this mod's {@code client} entrypoint in
 * {@code fabric.mod.json}, which is what keeps it off a dedicated server.
 */
public final class CrystalGuiLanguageFabric implements VariantEntry {

    /** @param context null — Fabric hands an entry point nothing. */
    @Override
    public void start(Object context) {
        LanguageLifecycle.bootstrapClient();
    }
}
