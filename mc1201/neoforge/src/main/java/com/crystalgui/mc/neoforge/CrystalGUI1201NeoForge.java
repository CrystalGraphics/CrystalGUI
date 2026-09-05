package com.crystalgui.mc.neoforge;

import com.crystalgui.mc.platform.CgPlatformService1201;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

@Mod(MODID)
public final class CrystalGUI1201NeoForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public CrystalGUI1201NeoForge(net.neoforged.bus.api.IEventBus modBus) {
        CgPlatformService1201.getInstance();
        CgEngineNeoForgeEvents.register();
        CgDemoNeoForgeEvents.register();
        CgUiNeoForgeEvents.register(modBus);
        LOGGER.info("[CrystalGUI] NeoForge 1.20.4 platform registered");
    }
}
