package com.crystalgui.mc.fabric;

import net.fabricmc.api.ClientModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.NAME;

public final class CrystalGUI1201Fabric implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CgUiFabricEvents.registerClient();
        LOGGER.info("[CrystalGUI] Fabric 1.20.1 client registered");
    }
}
