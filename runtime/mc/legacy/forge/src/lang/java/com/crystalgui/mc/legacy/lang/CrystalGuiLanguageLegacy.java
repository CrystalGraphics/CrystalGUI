package com.crystalgui.mc.legacy.lang;

import java.io.File;
import java.util.List;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.cache.DownloadLocations;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The language mod on Forge 1.8–1.12.2: installs the {@link ScriptServiceLegacy}, warms the registry and
 * starts the mapping fetch. Nothing calls into this jar; it installs itself through seams {@code core} owns,
 * and the host runs without it.
 */
public final class CrystalGuiLanguageLegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> preInit((FMLPreInitializationEvent) event));
        events.on("FMLInitializationEvent", event -> init());
    }

    private static void preInit(FMLPreInitializationEvent event) {
        // Forge's config directory's parent is the installation, on both sides.
        File gameDirectory = event.getModConfigurationDirectory().getParentFile();

        // Asked before installing: engines built by an earlier read keep "no ScriptService" for good.
        boolean registryAlreadyRead = LanguageRegistry.isBootstrapped();
        ScriptServiceLegacy service = new ScriptServiceLegacy(gameDirectory);
        CgPlatform.provide(ScriptServices.SERVICE, service);
        DownloadLocations.useCacheRoot(service.cacheRoot());
        if (registryAlreadyRead) {
            CrystalGuiCore.LOGGER.error("[cgui-lang] the language registry was read BEFORE this mod "
                    + "installed its ScriptService, so the engines built during that read captured no "
                    + "platform. Scripts will not resolve Minecraft types this run.");
        }
        if (FMLCommonHandler.instance().getSide().isClient()) LanguageAutoTestLegacy.register();
    }

    private static void init() {
        // Warming, not wiring: the registry finds the engines on its own first read (443 ms measured).
        LanguageRegistry.bootstrap();
        // Client only: the fetch runs as a job, which only UIDocument.frame drains. Started now so the first
        // analysis does not race it -- every name here is SRG until the mapping lands.
        if (FMLCommonHandler.instance().getSide().isClient()) PlatformMappings.start();

        List<String> contributors = LanguageRegistry.contributors();
        CrystalGuiCore.LOGGER.info("[cgui-lang] language stack installed; contributors: {}", contributors);
        ScriptService scripts = CgPlatform.get(ScriptServices.SERVICE);
        CrystalGuiCore.LOGGER.info("[cgui-lang] scripts: {}, Minecraft {}, MCP {}", scripts,
                ScriptServiceLegacy.minecraftVersion(),
                ScriptServiceLegacy.mcpVersion(ScriptServiceLegacy.minecraftVersion()));
    }
}
