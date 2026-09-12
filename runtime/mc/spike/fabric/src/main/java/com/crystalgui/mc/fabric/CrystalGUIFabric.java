package com.crystalgui.mc.fabric;

import net.fabricmc.api.ClientModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * The client half. Everything shared lives in {@link CrystalGUIFabricCommon}, which a dedicated
 * server also runs — this only adds what must not exist there.
 */
public final class CrystalGUIFabric implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CrystalGUIFabricCommon.Events.registerClient();
        LOGGER.info("[CrystalGUI] Fabric 1.20.1 client registered");
    }
}
