package com.crystalgui.mc.fabric;

import com.crystalgui.mc.platform.CgPlatformService1201;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.NAME;

public final class CrystalGUI1201Fabric implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(NAME);

    @Override
    public void onInitializeClient() {
        CgPlatformService1201.getInstance();
        CgEngineFabricEvents.register();
        CgDemoFabricEvents.register();
        LOGGER.info("[CrystalGUI] Fabric 1.20.1 platform registered");
    }
}
