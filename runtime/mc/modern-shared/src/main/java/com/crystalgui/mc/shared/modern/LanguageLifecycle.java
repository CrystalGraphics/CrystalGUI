package com.crystalgui.mc.shared.modern;

import com.crystalgui.language.LanguageHost;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.util.function.Supplier;

/**
 * Everything the language mod installs into the host, on every modern loader.
 *
 * <pre>{@code
 * // from the language mod's own entry point, per loader
 * event.enqueueWork(() -> LanguageLifecycle.bootstrapClient(ScriptServiceModern::forThisClient));
 * }</pre>
 *
 * <p>The language mod installs itself, through seams {@code core} owns, and a host without this jar calls
 * nothing and is missing nothing. The main jar reports the stack's absence by reading
 * {@link LanguageRegistry#contributors()}, which names nothing here.</p>
 *
 * <p>The service comes from the caller because it names Minecraft and so is the node's; this class names
 * neither and ships once. The order and the reports are {@link LanguageHost}'s, shared with both
 * LaunchWrapper hosts.</p>
 */
public final class LanguageLifecycle {

    private static boolean installed;

    private LanguageLifecycle() {
    }

    /**
     * Idempotent: three loaders share this and only one of them is ever the caller.
     *
     * @param service this node's platform service; may answer null when the client cannot run scripts
     */
    public static synchronized void bootstrapClient(Supplier<? extends ScriptService> service) {
        if (installed) return;
        installed = true;

        ScriptService found = service.get();
        if (found != null) LanguageHost.install(found);
        // Off unless the unattended run is, so it costs an ordinary launch one boolean. @see LanguageProbeModern
        LanguageProbeModern.register();
        LanguageHost.start(true);
    }
}
