package com.crystalgui.language;

import javax.annotation.Nullable;

import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.storage.CrystalGuiDownloads;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.PlatformTypeBytes;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.probe.LanguageProbe;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * Starts the language stack on every loader: points downloads at the game's cache, registers the languages, starts the
 * mapping fetch on a client and logs what came up. Declared {@code common} in {@code META-INF/crystalgraphics/listeners}.
 *
 * <ul>
 *   <li>A client starts it behind the loading screen, a dedicated server as its first server starts.</li>
 *   <li>Registering on the render thread, never a worker: it writes {@code Commands}, which the UI reads
 *       unsynchronised. What it costs that touches no registry runs first on a worker ({@link LanguageStack#prepare}).</li>
 * </ul>
 */
public final class CgLanguageLifecycle implements CgLifecycleListener {

    /** The one listener; what the engine's discovery registers. */
    public static final CgLanguageLifecycle INSTANCE = new CgLanguageLifecycle();

    private static final String PROBE_TYPE = "net/minecraft/client/Minecraft";

    private boolean started;

    private CgLanguageLifecycle() {
    }

    @Override
    public void onLoad(CgLoadPlan plan) {
        if (!claim()) return;
        LanguageProbe.register();
        // Under the loading screen rather than on the first editor open. The engine band, the tree-sitter native and the
        // classpath on a worker: on the render thread they held the screen ~400 ms
        plan.task("crystalgui_language:start").work(() -> prepare(true)).render(task -> {
            register();
            return true;
        }).start();
    }

    @Override
    public void onServerStarting(@Nullable Object server) {
        if (!claim()) return;
        prepare(false);
        register();
    }

    private synchronized boolean claim() {
        if (started) return false;
        started = true;
        return true;
    }

    /** What touches no registry, on any thread. */
    private static void prepare(boolean client) {
        CrystalGuiDownloads.LOCATIONS.useCacheRoot(ScriptService.cacheRoot());
        LanguageStack.prepare();
        // Before registering, and so before any analysis, which would otherwise read identity names and not hear when
        // the mapping lands
        if (client) PlatformMappings.start();
        if (!ScriptService.live()) {
            CrystalGuiCore.LOGGER.info("[cgui-lang] no game: scripts resolve against the classpath alone");
            return;
        }
        CrystalGuiCore.LOGGER.info("[cgui-lang] live bytes from {}, cache {}",
                ScriptService.runningNodeClass().getName(), ScriptService.cacheRoot());
        if (client) reportResolution();
    }

    /** The registries: on the render thread on a client. */
    private static void register() {
        LanguageRegistry.bootstrap();
        CrystalGuiCore.LOGGER.info("[cgui-lang] language stack started; contributors: {}",
                LanguageRegistry.contributors());
    }

    private static void reportResolution() {
        CrystalGuiCore.LOGGER.info("[cgui-lang] classpath: {} entries", HostClasspath.detect().size());
        try {
            TypeBytes types = PlatformTypeBytes.of();
            if (types == TypeBytes.NONE) {
                CrystalGuiCore.LOGGER.error("[cgui-lang] the live tier is OFF (-Dcrystalgui.language.noLiveBytes?)."
                        + " Scripts cannot resolve Minecraft.");
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
