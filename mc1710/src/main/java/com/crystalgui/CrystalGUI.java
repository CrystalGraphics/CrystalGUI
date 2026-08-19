package com.crystalgui;

import com.crystalgui.mc.CommonProxy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * The CrystalGUI mod container.
 *
 * <p>Depends on {@code crystalgraphics} and does <b>no platform registration of its own</b>. That is
 * not an omission: CrystalGraphics is the parent mod, is always present, and owns every
 * {@code CgPlatformService} implementation — input, sound, cursor, clipboard, GL, resources, lifecycle
 * and reload are all registered by its {@code PlatformService1710.onPreInit()}. CrystalGUI reads them
 * through {@code CgPlatform} and never holds a registry of its own; a second registry is how a loader
 * ends up with a working GL backend and a dead keyboard.</p>
 */
@Mod(
    modid = CrystalGUI.MODID,
    name = CrystalGUI.NAME,
    version = CrystalGUI.VERSION,
    dependencies = "required-after:crystalgraphics",
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

    /**
     * Client-only work lives behind this.
     *
     * <p>{@code CgUiScreen} imports {@code GuiScreen}, so naming it from a common class is enough to
     * break a dedicated server at class load. @see CommonProxy</p>
     */
    @SidedProxy(
        clientSide = "com.crystalgui.mc.ClientProxy",
        serverSide = "com.crystalgui.mc.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{}: preInit", NAME);
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
        proxy.init();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("{}: postInit", NAME);
    }
}
