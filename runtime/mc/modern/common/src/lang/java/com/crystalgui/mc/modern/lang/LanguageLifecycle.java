package com.crystalgui.mc.modern.lang;

import com.crystalgui.language.LanguageHost;
import com.crystalgui.text.syntax.LanguageRegistry;

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
 *
 * <p>The order and the reports are {@link LanguageHost}'s, shared with both LaunchWrapper hosts.</p>
 */
public final class LanguageLifecycle {

    private static boolean installed;

    private LanguageLifecycle() {
    }

    /** Idempotent: four loaders share this and only one of them is ever the caller. */
    public static synchronized void bootstrapClient() {
        if (installed) return;
        installed = true;

        ScriptServiceModern service = ScriptServiceModern.forThisClient();
        if (service != null) LanguageHost.install(service);
        // Off unless the unattended run is, so it costs an ordinary launch one boolean. @see LanguageProbeModern
        LanguageProbeModern.register();
        LanguageHost.start(true);
    }
}
