package com.crystalgui.mc.forge;

import com.crystalgui.mc.platform.CgPlatformService1201;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

@Mod(MODID)
public final class CrystalGUI1201Forge {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGUI");

    public CrystalGUI1201Forge() {
        CgPlatformService1201.getInstance();
        LOGGER.info("[CrystalGUI] Forge 1.20.1 platform registered");
    }
}
