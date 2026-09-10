package com.crystalgui.mc.lang;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.util.List;

/**
 * Everything the language mod installs into the host, on 1.20.x.
 *
 * <pre>{@code
 * LanguageLifecycle1201.bootstrapClient();   // from the language mod's own entry point, per loader
 * }</pre>
 *
 * <p><b>The direction of registration inverted at J8.</b> The host used to call
 * {@code ScriptService1201.install()} and {@code PlatformMappings.start()} from its own client
 * bootstrap, which meant the host named the language stack — the thing the split exists to prevent.
 * Now the language mod installs itself, through seams {@code core} owns, and a host without this jar
 * calls nothing and is missing nothing.</p>
 *
 * <p>What replaced the old "is the stack bundled?" probe is the jar being here at all: this class only
 * runs when the language mod loaded, so the branch that reported an absent stack was optionality
 * asserted by hand. The main jar reports the absence instead, by reading
 * {@link LanguageRegistry#contributors()} — which is {@code core}'s and names nothing here.</p>
 */
public final class LanguageLifecycle1201 {

    private static boolean installed;

    private LanguageLifecycle1201() {
    }

    /** Idempotent: four loaders share this and only one of them is ever the caller. */
    public static synchronized void bootstrapClient() {
        if (installed) return;
        installed = true;

        // FIRST: the engine source asks this service where it may write, so a band bundled in the jar
        // or fetched for this host has nowhere to go until it is registered.
        ScriptService1201.install();

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
