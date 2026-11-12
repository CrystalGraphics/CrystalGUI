package com.crystalgui.fs.client;

import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;
import com.crystalgui.core.dispose.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a module that owns a scheme <b>contributes</b> its provider, without holding a workspace.
 *
 * <p>For a module that has no {@link Workspace} to hand: a language stack registers its providers at
 * mod init, long before any world is joined, and {@code core/} may never name {@code language/} — the
 * same inversion {@code ProjectSourcesRegistry} and {@code TypeSearchRegistry} use.</p>
 *
 * <p>Every workspace <b>drains this into its own table</b> at construction and stays subscribed, so the
 * table is still per workspace: two servers in one client keep separate ones, and a scheme registered
 * directly on a workspace wins over a contribution here.</p>
 */
public final class ContentProviders {

    private static final Contributions<Contribution> PROVIDERS = Contributions.of("content-providers", Contribution::scheme);

    /** A provider was contributed or withdrawn: what a live {@link Workspace} re-reads. */
    public static final Broadcast<?> onDidChange = PROVIDERS.published().changes();

    private ContentProviders() {
    }

    /**
     * Contributes a provider for one scheme.
     *
     * <p>Last registration wins, which is right for a hot reload and is why this answers a handle rather
     * than refusing. Every workspace alive at the time is told.</p>
     */
    public static Disposable contribute(String scheme, ContentProvider provider) {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(provider, "provider");
        return PROVIDERS.add(Owner.PROCESS, new Contribution(scheme, provider));
    }

    /** Every contribution, as {@code (scheme, provider)} pairs. What a workspace drains. */
    public static List<Contribution> all() {
        return new ArrayList<>(PROVIDERS.all());
    }

    /** For a test, which needs a clean slate and shares statics with every other test in the run. */
    public static void resetForTesting() {
        PROVIDERS.clearForTesting();
    }

    public record Contribution(String scheme, ContentProvider provider) {
    }
}
