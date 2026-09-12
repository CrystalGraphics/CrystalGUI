package com.crystalgui.mc.fabric;

import com.crystalgraphics.mc.shared.VariantEntry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * The client half. Everything shared lives in {@link CrystalGUIFabricCommon}, which a dedicated
 * server also runs — this only adds what must not exist there.
 *
 * <p>Named as a variant's {@code client} entry in {@code variants.json}; {@code FabricBootstrap} is
 * what {@code fabric.mod.json} names, and it constructs this only on a client.</p>
 */
public final class CrystalGUIFabric implements VariantEntry {

    private static final Logger LOGGER = LogManager.getLogger(NAME);

    /** @param context null — Fabric hands an entry point nothing. */
    @Override
    public void start(Object context) {
        CrystalGUIFabricCommon.Events.registerClient();
        LOGGER.info("[CrystalGUI] Fabric 1.20.1 client registered");
    }
}
