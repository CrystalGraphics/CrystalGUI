package com.crystalgui.mc.platform;

import java.util.List;

import com.crystalgui.core.CrystalGuiCore;
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
 * <p><b>No {@code ScriptService} is registered here.</b> The 1.7.10 one answers three
 * LaunchWrapper-shaped questions -- the live bytes of a runtime class, the runtime name for an on-disk
 * one, and the mapping coordinates -- and 1.20.x has ModLauncher or Knot instead, neither of which
 * exposes a transformed-bytes call. So {@code ScriptRuntimes.open} answers empty and the Run panel is
 * absent: a supported configuration rather than a fault, and live resolution is its own follow-up.
 * {@code plan/platform-mc1201.md} §3.7.</p>
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
        CrystalGuiCore.LOGGER.info("[cgui-1201] no ScriptService: ModLauncher and Knot expose no "
                + "transformed-bytes call, so ScriptRuntimes.open answers empty and the Run panel is absent");
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
