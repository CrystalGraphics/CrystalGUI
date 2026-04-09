package com.crystalgui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = CrystalGUI.MODID,
    name = CrystalGUI.NAME,
    version = CrystalGUI.VERSION,
    acceptedMinecraftVersions = "[1.7.10]"
)
public class CrystalGUI {

    /** The mod ID used for Forge dependency resolution. */
    public static final String MODID = "crystalgui";

    /** Human-readable mod name. */
    public static final String NAME = "CrystalGUI";

    /** Mod version string (kept in sync with gradle.properties). */
    public static final String VERSION = Tags.VERSION;

    /** Logger for mod lifecycle messages. */
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{}: preInit (coremod + mixins should already be active)", NAME);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("{}: postInit", NAME);
    }

}
