package com.crystalgui.mc.lang;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;

import java.io.File;
import java.util.List;

/**
 * The language stack's 1.7.10 mod container — grammars, analysis and scripting, as their own download.
 *
 * <p>Nothing calls into this jar: it installs itself, through seams {@code core} owns. A host without
 * it registers no {@code ScriptService}, colours from the built-in lexers and says so in the log.</p>
 *
 * <p><b>{@code required-after:crystalgui}</b> — the host's {@code CgPlatform} and its command registry
 * have to exist before anything here is provided, and {@code after} is what orders two FML mods.</p>
 */
@Mod(
    modid = CrystalGuiLanguage.MODID,
    name = CrystalGuiLanguage.NAME,
    version = CrystalGuiLanguage.VERSION,
    dependencies = "required-after:crystalgui",
    acceptedMinecraftVersions = "[1.7.10]"
)
public class CrystalGuiLanguage {

    public static final String MODID = "crystalgui_language";

    public static final String NAME = "CrystalGUI Language";

    /** The host's, deliberately: the two ship from one build and a version skew is a bug, not a state. */
    public static final String VERSION = com.crystalgui.CrystalGUI.VERSION;

    /**
     * {@code .minecraft} on a client, {@code <serverdir>} on a dedicated server — where the engine
     * cache and the mapping cache go. @see ScriptService1710#cacheRoot()
     */
    private File gameDirectory;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Forge hands over its own config directory; ours is its sibling. This is the only event that
        // carries either, which is why it is captured rather than asked for later.
        this.gameDirectory = event.getModConfigurationDirectory().getParentFile();

        // ASKED BEFORE INSTALLING, because after it the answer is always yes. If anything read
        // LanguageRegistry first, the engines it built captured "no ScriptService" and kept it for the
        // life of the process -- scripts then report every Minecraft type unresolvable while the byte
        // source behind them is healthy. @see LanguageRegistry#isBootstrapped
        boolean registryAlreadyRead = LanguageRegistry.isBootstrapped();
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1710(gameDirectory));
        if (registryAlreadyRead) {
            CrystalGuiCore.LOGGER.error("[cgui-lang] the language registry was read BEFORE this mod "
                    + "installed its ScriptService, so the engines built during that read captured no "
                    + "platform. Scripts will not resolve Minecraft types this run.");
        }
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            LanguageAutoTest1710.register();
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // WARMING, NOT WIRING. `language/` declares its grammars and engines as a LanguageKinds service,
        // so the registry finds them on its own first read -- this only decides WHEN that is paid.
        // Measured at 443ms, and the first read is otherwise the keystroke that opens an editor.
        LanguageRegistry.bootstrap();

        // CLIENT ONLY, and not a style choice: the fetch is handed to ScriptService1710.runInBackground,
        // whose job only UIDocument.frame drains -- so a dedicated server that started one would wait
        // for a mapping for ever with nothing to say why.
        //
        // Started here rather than left to the first caller of PlatformMappings.current(): that caller
        // is the first Java analysis, which then starts the download and reads the identity in the same
        // breath. On 1.7.10 every name is obfuscated, so losing that race is the difference between
        // getMinecraft() and func_71410_x() in a freshly opened file.
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            PlatformMappings.start();
        }

        announce();
    }

    /** The tier this deployment is at, measured rather than described. */
    private void announce() {
        List<String> contributors = LanguageRegistry.contributors();
        CrystalGuiCore.LOGGER.info("[cgui-lang] language stack installed; contributors: {}", contributors);

        ScriptService scripts = CgPlatform.get(ScriptServices.SERVICE);
        if (scripts == ScriptService.NONE) {
            CrystalGuiCore.LOGGER.warn("[cgui-lang] no ScriptService registered -- the Run panel opens "
                    + "and ScriptRuntimes.open answers empty, so nothing in it runs");
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-lang] scripts: {}, live bytes from {}", scripts,
                    scripts.liveBytes().getClass().getName());
        }
    }
}
