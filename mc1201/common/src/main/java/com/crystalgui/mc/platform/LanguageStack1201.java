package com.crystalgui.mc.platform;

import java.util.List;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * What the editor can do with a source file on this host, said out loud once at startup.
 *
 * <pre>{@code
 * LanguageStack1201.announce();   // from Lifecycle1201.bootstrapClient()
 * }</pre>
 *
 * <p>The stack degrades in tiers -- no engine leaves grammar colouring, no grammar leaves core's
 * word-list lexers, neither leaves plain text -- and <b>every tier opens a file perfectly</b>. The
 * configurations are indistinguishable on screen, so the only thing separating "this deployment ships
 * no grammars" from "a contributor failed to load" is a line saying which one this is.</p>
 *
 * <p><b>The {@code ScriptService} answers every question, and scripts run.</b>
 * {@link com.crystalgui.mc.client.ScriptService1201} gives the cache root a band is extracted into,
 * live bytes off the loader that will actually run the class, and a mapping chosen by READING which
 * namespace this runtime speaks rather than by naming a loader. This paragraph used to say the
 * opposite -- cacheRoot only, no live bytes, no mappings, nothing in the Run panel runs -- on the
 * belief that a transformed-bytes call needs LaunchWrapper. It does not: {@code MinecraftBytes1201}
 * reads the mod class loader, which is namespace-correct here in a way 1.7.10's never was.</p>
 */
public final class LanguageStack1201 {

    /** The {@code :language} jar's entry point, and the {@code LanguageKinds} service it registers. */
    private static final String STACK = "com.crystalgui.language.LanguageStack";

    private LanguageStack1201() {}

    private static boolean announced;

    /** Warms the registry and states the tier. Idempotent. */
    public static synchronized void announce() {
        if (announced) return;
        announced = true;

        // WARMING, not enabling. The registry finds its services on the first READ, so a host that never
        // called this still gets everything on the classpath -- this only moves the cost (443ms, measured
        // on a client) to where a loading screen is already covering it.
        List<String> contributors = LanguageRegistry.contributors();

        if (isPresent(STACK)) {
            CrystalGuiCore.LOGGER.info("[cgui-1201] language stack bundled; contributors: {}", contributors);
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-1201] language stack NOT bundled -- no grammars, no ECJ, no "
                    + "Rhino; source files colour from core's built-in lexers and are not analysed. "
                    + "Contributors: {}", contributors);
        }
        // THE TIER THIS DEPLOYMENT IS AT, measured rather than described. A wrong sentence here is
        // worse than none: this one claimed for a release that scripts could not run, while they were
        // running. @see PlatformMappings for the line that says which mapping was resolved.
        ScriptService scripts = CgPlatform.get(ScriptServices.SERVICE);
        if (scripts == ScriptService.NONE) {
            CrystalGuiCore.LOGGER.warn("[cgui-1201] no ScriptService registered -- the Run panel opens "
                    + "and ScriptRuntimes.open answers empty, so nothing in it runs");
        } else {
            CrystalGuiCore.LOGGER.info("[cgui-1201] scripts: {}, live bytes from {}", scripts,
                    scripts.liveBytes().getClass().getName());
        }
    }

    private static boolean isPresent(String className) {
        try {
            // Loaded and deliberately NOT initialised: asking whether a jar is here must not run its
            // static state, which for this one is the whole grammar set.
            Class.forName(className, false, LanguageStack1201.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
