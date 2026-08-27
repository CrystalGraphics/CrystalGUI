package com.crystalgui;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.language.LanguageStack;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.mc.ClientProxy;
import com.crystalgui.mc.CommonProxy;

import com.crystalgui.mc.platform.service.script.ScriptService1710;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.mc.net.CgUiServerSmoke;

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

    private void scriptInit() {
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1710(configDirectory));
        LanguageStack.registerAll();

        // The service itself is now SIDE-AGNOSTIC (Phase 4 A5): cacheRoot() takes the config
        // directory Forge hands over at preInit instead of reading Minecraft.getMinecraft(), so a
        // dedicated server can hold one. The four other members were always installation-level facts.
        //
        // What is still client-shaped is BELOW, not above: the mappings fetch is submitted as a job
        // so it reports into a status bar. A server wanting mappings would acquire them without one.

        // DECIDED HERE, FETCHED LATER -- and the split is the whole point.
        //
        // This used to put BOTH halves in the job, on the reasoning that a claim made here is always
        // honoured because the job is already submitted. The second half of that sentence is the part that
        // was not true: a job only starts when something calls `JobScheduler.shared().drain()`, and the
        // only thing that does is `UIWindow.advanceFrame`. So the acquisition was owed to a frame, which is
        // a promise a mod's init has no business making -- a dedicated server never paints one, and even a
        // client owes it to a window that may not exist yet.
        //
        // What made it costly is that `decide()` needs no frame and no network. It reads an already
        // downloaded mapping off disk, which is a parse. On `runObfClient` with mcp_stable/12 complete in
        // the config directory, the claim was taken, the job was submitted, and nothing ever ran: no
        // mapping line in any log of any run, every compiled script cached under a key ending
        // `-identity-8`, and `Minecraft.getMinecraft()` reaching a runtime that has only `func_71410_x`.
        // The data was on the disk the whole time.
        //
        // So the cache is applied on this thread, and only a genuine download is handed to a job -- which
        // is what gives it a progress bar, and is the one half worth deferring.
        if (PlatformMappings.claim()) {
            ScriptService needsFetch = PlatformMappings.decideClaimed();
            if (needsFetch != null) {
                JobScheduler.shared().job(JobKey.of(PlatformMappings.class, "mappings"),
                        JobLane.BACKGROUND, context -> {
                            PlatformMappings.fetchClaimed(needsFetch, context.progress(),
                                    context::isCancelled);
                            return null;
                        }).submit();
            }
        }
    }
}
