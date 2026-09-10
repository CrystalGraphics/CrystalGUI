package com.crystalgui;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.mc.ClientProxy;
import com.crystalgui.mc.CommonProxy;
import com.crystalgui.mc.shared.CrashVariant;

import com.crystalgui.mc.platform.service.script.ScriptService1710;
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
     * {@code .minecraft} on a client, {@code <serverdir>} on a dedicated server — where
     * {@code crystalgui/} goes.
     *
     * <p>Read off {@code FMLPreInitializationEvent}'s config directory, which is the only event that
     * carries either, and taken one level up: the config directory is Forge's, and CrystalGUI's own
     * root is beside it rather than inside it. @see ScriptService1710#cacheRoot()</p>
     */
    private File gameDirectory;

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
        // Captured here because this is the only event that carries it, and it is the side-agnostic
        // answer to "where does crystalgui/ go" -- .minecraft on a client, <serverdir> on a dedicated
        // server. Forge hands over its own config directory; ours is its sibling.
        this.gameDirectory = event.getModConfigurationDirectory().getParentFile();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
        proxy.init();
        scriptInit();
        // AFTER scriptInit, which registers the ScriptService this reads. Started before it, the
        // decision finds no platform, says so, and KEEPS THE CLAIM -- identity names for the life of
        // the process. @see CommonProxy#startMappings
        proxy.startMappings();
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
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1710(gameDirectory));
        // WARMING, NOT WIRING. `language/` declares its grammars and engines as a LanguageKinds
        // service, so the registry finds them on its own first read -- this only decides WHEN that is
        // paid. Measured at 443ms, and the first read is otherwise the keystroke that opens an editor;
        // here a loading screen is already up and nobody is waiting. Dropping this line costs the stall
        // and not the languages, which is the whole point of the service.
        LanguageRegistry.bootstrap();

        // NOTHING DRIVES THE MAPPING FROM HERE, and that is the correction. `language/` decides a
        // fetch is owed and asks this service HOW to run it; the loader's answer is
        // `ScriptService1710.runInBackground`. A platform states the what, the where and the how.
        //
        // It also fixes a real defect: this ran from `init`, which fires on BOTH sides, so a dedicated
        // server submitted a job that only `UIDocument.frame` could ever drain. An obfuscated server
        // with no cached mapping waited for one for ever with nothing to say why.
    }
}
