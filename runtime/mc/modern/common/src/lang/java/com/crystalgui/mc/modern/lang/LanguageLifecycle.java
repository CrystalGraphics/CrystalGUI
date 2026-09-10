package com.crystalgui.mc.modern.lang;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.PlatformTypeBytes;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.util.List;

/**
 * Everything the language mod installs into the host, on 1.20.x.
 *
 * <pre>{@code
 * LanguageLifecycle.bootstrapClient();   // from the language mod's own entry point, per loader
 * }</pre>
 *
 * <p><b>The direction of registration inverted at J8.</b> The host used to call
 * {@code ScriptServiceModern.install()} and {@code PlatformMappings.start()} from its own client
 * bootstrap, which meant the host named the language stack — the thing the split exists to prevent.
 * Now the language mod installs itself, through seams {@code core} owns, and a host without this jar
 * calls nothing and is missing nothing.</p>
 *
 * <p>What replaced the old "is the stack bundled?" probe is the jar being here at all: this class only
 * runs when the language mod loaded, so the branch that reported an absent stack was optionality
 * asserted by hand. The main jar reports the absence instead, by reading
 * {@link LanguageRegistry#contributors()} — which is {@code core}'s and names nothing here.</p>
 */
public final class LanguageLifecycle {

    private static boolean installed;

    private LanguageLifecycle() {
    }

    /** Idempotent: four loaders share this and only one of them is ever the caller. */
    public static synchronized void bootstrapClient() {
        if (installed) return;
        installed = true;

        // ASKED BEFORE INSTALLING, because after it the answer is always yes. If anything read
        // LanguageRegistry first, the engines it built captured "no ScriptService" and kept it for the
        // life of the process -- scripts then report every Minecraft type unresolvable while the byte
        // source behind them is healthy. It is silent, it survives a world reload, and it cost a
        // session once. @see LanguageRegistry#isBootstrapped
        boolean registryAlreadyRead = LanguageRegistry.isBootstrapped();

        // FIRST: the engine source asks this service where it may write, so a band bundled in the jar
        // or fetched for this host has nowhere to go until it is registered.
        ScriptServiceModern.install();

        if (registryAlreadyRead) {
            CrystalGuiCore.LOGGER.error("[cgui-lang] the language registry was read BEFORE this mod "
                    + "installed its ScriptService, so the engines built during that read captured no "
                    + "platform. Scripts will not resolve Minecraft types this run. Something in the "
                    + "host read LanguageRegistry during mod setup -- it must wait until every mod has "
                    + "set up.");
        }

        // AND THE MAPPING IS STARTED HERE, not left to whoever asks first. The first asker is the first
        // Java analysis, which then cannot win its own race -- it starts the download and reads the
        // identity in the same breath, and nothing re-analyses when the mapping lands. Started at mod
        // init it has the whole world load to arrive in. @see PlatformMappings#start
        PlatformMappings.start();

        announce();
    }

    /**
     * The tier this deployment is at, measured rather than described.
     *
     * <p>A wrong sentence here is worse than none: an earlier one claimed for a release that scripts
     * could not run, while they were running.</p>
     */
    private static void announce() {
        // WARMING, not enabling. The registry finds its services on the first READ, so a host that
        // never called this still gets everything on the classpath -- this only moves the cost (443ms,
        // measured on a client) to where a loading screen is already covering it.
        List<String> contributors = LanguageRegistry.contributors();
        CrystalGuiCore.LOGGER.info("[cgui-lang] language stack installed; contributors: {}", contributors);

        com.crystalgui.language.platform.ScriptService scripts = CgPlatform.get(ScriptServices.SERVICE);
        if (scripts == com.crystalgui.language.platform.ScriptService.NONE) {
            CrystalGuiCore.LOGGER.warn("[cgui-lang] no ScriptService registered -- the Run panel opens "
                    + "and ScriptRuntimes.open answers empty, so nothing in it runs");
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-lang] scripts: {}, live bytes from {}", scripts,
                    scripts.liveBytes().getClass().getName());
            reportResolution(scripts);
        }
    }

    /**
     * Whether a script can actually see Minecraft, and by which of the two routes.
     *
     * <p>One line, at install, because "the type does not resolve" has two completely different causes
     * and the editor shows the same red squiggle for both: the CLASSPATH index (what Go to File
     * searches, from {@code HostClasspath}) and the LIVE tier (what the compiler's name environment
     * reads). Since the language stack became its own jar, both are reached from a classloader in that
     * jar rather than the host's, which is exactly the kind of change that moves one and not the other.</p>
     *
     * <p><b>Asked through {@link TypeBytes#readable}, not the raw byte source.</b> The raw source speaks
     * whatever namespace the runtime does — intermediary on Fabric — so asking it for
     * {@code net/minecraft/client/Minecraft} answers null there and looks like a fault when nothing is
     * wrong. {@code readable} is the composition of the source WITH the mapping, and it is what the
     * compiler resolves against, so it is the only layer whose answer means what this line claims.</p>
     */
    private static void reportResolution(com.crystalgui.language.platform.ScriptService scripts) {
        // The SIZE only. A "does any entry look like Minecraft" count was tried and is worthless: every
        // path on a Prism install contains `.minecraft`, so it matched all of them and read as healthy.
        CrystalGuiCore.LOGGER.info("[cgui-lang] classpath: {} entries", HostClasspath.detect().size());

        // The one type every script names, and the exact call the compiler makes for it. `NONE` here is
        // the failure this probe exists for: it means the engines were built before the ScriptService
        // was registered, and no amount of healthy classpath will resolve a Minecraft type.
        String probe = "net/minecraft/client/Minecraft";
        try {
            TypeBytes types = PlatformTypeBytes.of();
            if (types == TypeBytes.NONE) {
                CrystalGuiCore.LOGGER.error("[cgui-lang] the live tier is OFF -- no ScriptService was "
                        + "registered when the engines were built. Scripts cannot resolve Minecraft.");
                return;
            }
            byte[] bytes = types.readable(probe);
            CrystalGuiCore.LOGGER.info("[cgui-lang] {} resolves to {}", probe,
                    bytes == null ? "NULL -- scripts cannot resolve it" : bytes.length + " bytes");
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.warn("[cgui-lang] resolving {} FAILED: {}", probe, failed.toString());
        }
    }
}
