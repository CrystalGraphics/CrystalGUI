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
 * <p><b>A {@code ScriptService} IS registered, and it answers one question.</b>
 * {@link com.crystalgui.mc.client.ScriptService1201} exists for {@code cacheRoot()}, which is what lets
 * a band bundled in the jar be extracted and a missing one be fetched. Its other three members are
 * LaunchWrapper-shaped and 1.20.x has ModLauncher or Knot, neither of which exposes a transformed-bytes
 * call -- so it answers {@code ByteSource.NONE} for live bytes, and the engine reads that as no live
 * tier at all. There are no mappings. The Run panel still opens; {@code ScriptRuntimes.open} answers
 * empty, so nothing in it runs. The plan recommended registering no service at all, which would also
 * have cost the engine bands.</p>
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
        CrystalGuiCore.LOGGER.info("[cgui-1201] ScriptService answers cacheRoot only: 1.20.x has "
                + "ModLauncher or Knot rather than LaunchWrapper, so there are no live bytes and no "
                + "mappings. The Run panel opens and ScriptRuntimes.open answers empty, so nothing runs");
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
