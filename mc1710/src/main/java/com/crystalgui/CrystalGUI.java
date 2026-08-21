package com.crystalgui;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.language.LanguageStack;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.mc.ClientProxy;
import com.crystalgui.mc.CommonProxy;

import com.crystalgui.mc.platform.service.script.ScriptService1710;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

import com.crystalgui.mc.net.CgUiConnections;

import java.io.File;
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

    /**
     * {@code .minecraft/config} on a client, {@code <serverdir>/config} on a dedicated server.
     *
     * <p>Only {@code FMLPreInitializationEvent} carries it, so it is captured there and handed to
     * whatever needs a place for derived state. @see ScriptService1710#cacheRoot()</p>
     */
    private File configDirectory;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{}: preInit", NAME);
        // Captured here because this is the only event that carries it, and it is the side-agnostic
        // answer to "where does derived state go" -- .minecraft/config on a client, <serverdir>/config
        // on a dedicated server. @see ScriptService1710#cacheRoot()
        this.configDirectory = event.getModConfigurationDirectory();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
        proxy.init();
        scriptInit();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("{}: postInit", NAME);
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

    private void scriptInit() {
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1710(configDirectory));
        LanguageStack.registerAll();

        // The service itself is now SIDE-AGNOSTIC (Phase 4 A5): cacheRoot() takes the config
        // directory Forge hands over at preInit instead of reading Minecraft.getMinecraft(), so a
        // dedicated server can hold one. The four other members were always installation-level facts.
        //
        // What is still client-shaped is BELOW, not above: the mappings fetch is submitted as a job
        // so it reports into a status bar. A server wanting mappings would acquire them without one.

        // MAPPINGS ACQUIRED INSIDE A JOB, so the fetch reports into the status bar instead of being a
        // silent stall on first launch. Threading is the caller's decision, and this caller has a UI.
        // CLAIMED NOW, DONE LATER, and the order is the whole point.
        // Safe to defer because a claim made here is always honoured: the job is already submitted, and a
        // client that never opens the editor never needs a mapping.
        if (PlatformMappings.claim()) {
            JobScheduler.shared().job(JobKey.of(PlatformMappings.class, "mappings"), JobLane.BACKGROUND,
                    context -> {
                        PlatformMappings.acquireClaimed(context.progress(), context::isCancelled);
                        return null;
                    }).submit();
        }
    }
}
