package com.crystalgui;

import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.mc.ClientProxy;
import com.crystalgui.mc.CommonProxy;
import com.crystalgui.mc.shared.CrashVariant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ICrashCallable;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.mc.net.CgUiServerSmoke;

import java.util.List;
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
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgui.mc.fml1710.common.* is the only thing
        // that says which one ran. @see CrashVariant
        FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
            @Override
            public String getLabel() {
                return CrashVariant.LABEL;
            }

            @Override
            public String call() {
                return CrashVariant.report(CrystalGUI.class);
            }
        });
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
        proxy.init();
        // WHAT THIS DEPLOYMENT CAN DO WITH A SOURCE FILE, read from core and naming no language class:
        // the language stack is a separate mod since J8, and this host must work without it.
        announceLanguageTier();
    }

    /**
     * Says which tier of the language stack this deployment has, without naming it.
     *
     * <p>{@code LanguageRegistry} is {@code core}'s engineless tier, so this compiles and runs with the
     * language mod absent. An empty contributor list IS the absent case; {@code crystalgui_lang}
     * announces its own arrival.</p>
     */
    private void announceLanguageTier() {
        List<String> contributors = LanguageRegistry.contributors();
        if (contributors.isEmpty()) {
            LOGGER.info("{}: no language stack installed -- source files colour from core's built-in "
                    + "lexers and are not analysed. Install crystalgui_lang for grammars, analysis "
                    + "and scripting.", NAME);
        } else {
            LOGGER.info("{}: language contributors: {}", NAME, contributors);
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("{}: postInit", NAME);
    }

    /**
     * The dedicated-server smoke check, when {@code -PcgServerSmoke} asked for it.
     *
     * <p><b>Started, not Starting.</b> {@code FMLServerStartedEvent} is the first moment the server is
     * genuinely up — world loaded, ticking — which is what makes "it booted" a real claim rather than
     * "it got as far as init". It is also late enough that a mod which failed to construct has already
     * taken the process down with it, so arriving here is itself most of the assertion.</p>
     *
     * <p>Costs one {@code Boolean.getBoolean} on every normal server start. @see CgUiServerSmoke</p>
     */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (CgUiServerSmoke.enabled()) CgUiServerSmoke.run();
    }

    /**
     * Phase 4 A4 — every connection is closed before the server goes away.
     *
     * <p>A mod-lifecycle event rather than something {@code CgUiConnections} subscribes itself, because
     * it arrives on a different bus. Without it a stop leaves every pending call unanswered and every
     * {@code onError} unrun; on a reload-in-place that reads as the next session inheriting ghosts.</p>
     */
    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        CgUiConnections.closeAll("server stopping");
    }
}
