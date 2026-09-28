package com.crystalgui.language;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.cache.DownloadLocations;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.PlatformTypeBytes;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * <b>What every loader's language mod does, in order</b>: install its {@link ScriptService} before
 * anything reads the registry, then warm the registry, start the mapping fetch and say what came up.
 *
 * <pre>{@code
 * // mod setup -- preInit on LaunchWrapper, client setup on 1.20.x
 * LanguageHost.install(new ScriptService1710(gameDirectory));
 *
 * // once every mod has set up -- init on LaunchWrapper; 1.20.x calls both back to back
 * LanguageHost.start(isClient);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #install} must come first. Engines built by an earlier read of {@link LanguageRegistry}
 *       keep "no ScriptService" for the life of the process, and every Minecraft type then fails to
 *       resolve while the byte source behind it is healthy. It is reported, not repaired.</li>
 *   <li>{@link #start} on a dedicated server warms the registry and reports nothing about Minecraft
 *       types: there is no client class to resolve and no mapping to fetch.</li>
 * </ul>
 */
public final class LanguageHost {

    /** The type every script names, and what the resolution report asks for. */
    private static final String PROBE_TYPE = "net/minecraft/client/Minecraft";

    private LanguageHost() {
    }

    /** Registers {@code service} and gives the download cache its root. Call it before anything reads the registry. */
    public static void install(ScriptService service) {
        // Asked before installing, because after it the answer is always yes.
        boolean registryAlreadyRead = LanguageRegistry.isBootstrapped();
        // First: the engine source asks this service where it may write.
        CgPlatform.provide(ScriptServices.SERVICE, service);
        DownloadLocations.useCacheRoot(service.cacheRoot());
        if (registryAlreadyRead) {
            CrystalGuiCore.LOGGER.error("[cgui-lang] the language registry was read BEFORE this mod "
                    + "installed its ScriptService, so the engines built during that read captured no "
                    + "platform. Scripts will not resolve Minecraft types this run. Something in the "
                    + "host read LanguageRegistry during mod setup -- it must wait until every mod has "
                    + "set up. The read:", LanguageRegistry.firstRead());
        }
    }

    /**
     * Warms the registry (443 ms measured on a client, better under a loading screen than on the first
     * editor open) and, on a client, starts the mapping fetch and reports whether a Minecraft type resolves.
     */
    public static void start(boolean client) {
        LanguageRegistry.bootstrap();
        // Started now rather than by the first analysis, which would read the identity in the same breath
        // as starting the download and never be told when the mapping lands. Client only: the fetch is a
        // job that only UIDocument.frame drains, so a server would wait on it for ever.
        if (client) PlatformMappings.start();

        CrystalGuiCore.LOGGER.info("[cgui-lang] language stack installed; contributors: {}",
                LanguageRegistry.contributors());
        ScriptService scripts = CgPlatform.get(ScriptServices.SERVICE);
        if (scripts == ScriptService.NONE) {
            CrystalGuiCore.LOGGER.warn("[cgui-lang] no ScriptService registered -- the Run panel opens "
                    + "and ScriptRuntimes.open answers empty, so nothing in it runs");
            return;
        }
        CrystalGuiCore.LOGGER.info("[cgui-lang] scripts: {}, live bytes from {}", scripts,
                scripts.liveBytes().getClass().getName());
        if (client) reportResolution();
    }

    private static void reportResolution() {
        // The size only: every path on a Prism install contains `.minecraft`, so a "looks like Minecraft"
        // count matched all of them.
        CrystalGuiCore.LOGGER.info("[cgui-lang] classpath: {} entries", HostClasspath.detect().size());
        try {
            TypeBytes types = PlatformTypeBytes.of();
            if (types == TypeBytes.NONE) {
                CrystalGuiCore.LOGGER.error("[cgui-lang] the live tier is OFF -- no ScriptService was "
                        + "registered when the engines were built. Scripts cannot resolve Minecraft.");
                return;
            }
            byte[] bytes = types.readable(PROBE_TYPE);
            CrystalGuiCore.LOGGER.info("[cgui-lang] {} resolves to {}", PROBE_TYPE,
                    bytes == null ? "NULL -- scripts cannot resolve it" : bytes.length + " bytes");
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.warn("[cgui-lang] resolving {} FAILED: {}", PROBE_TYPE, failed.toString());
        }
    }
}
