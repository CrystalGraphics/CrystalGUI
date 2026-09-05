package com.crystalgui.mc.forge;

import com.crystalgui.mc.platform.Lifecycle1201;

import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgui.mc.platform.CrystalGUI1201.MODID;

@Mod(MODID)
public final class CrystalGUI1201Forge {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGUI");

    public CrystalGUI1201Forge() {
        NetworkChannel1201.register();
        Lifecycle1201.bootstrap(NetworkChannel1201.get());
        LOGGER.info("[CrystalGUI] Forge 1.20.1 platform registered");
    }
}
