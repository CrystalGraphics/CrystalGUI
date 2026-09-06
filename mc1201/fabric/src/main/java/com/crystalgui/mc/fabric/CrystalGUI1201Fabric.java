package com.crystalgui.mc.fabric;

import net.fabricmc.api.ClientModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.NAME;

/**
 * The client half. Everything shared lives in {@link CrystalGUI1201FabricCommon}, which a dedicated
 * server also runs — this only adds what must not exist there.
 */
public final class CrystalGUI1201Fabric implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CrystalGUI1201FabricCommon.Events.registerClient();
        LOGGER.info("[CrystalGUI] Fabric 1.20.1 client registered");
    }
}
