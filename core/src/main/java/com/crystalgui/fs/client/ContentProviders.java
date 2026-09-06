package com.crystalgui.fs.client;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Map<String, ContentProvider> BY_SCHEME = new LinkedHashMap<>();

    /** A provider was contributed or withdrawn — what a live {@link Workspace} re-reads. */
    public static final Signal.Action onDidChange = new Signal.Action();

    private ContentProviders() {
    }

    /**
     * Contributes a provider for one scheme.
     *
     * <p>Last registration wins, which is right for a hot reload and is why this answers a handle rather
     * than refusing. Every workspace alive at the time is told.</p>
     */
    public static synchronized Disposable contribute(String scheme, ContentProvider provider) {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(provider, "provider");
        BY_SCHEME.put(scheme, provider);
        onDidChange.emit();
        return () -> {
            synchronized (ContentProviders.class) {
                if (BY_SCHEME.remove(scheme, provider)) onDidChange.emit();
            }
        };
    }

    /** Every contribution, as {@code (scheme, provider)} pairs. What a workspace drains. */
    public static synchronized List<Contribution> all() {
        List<Contribution> out = new ArrayList<>(BY_SCHEME.size());
        BY_SCHEME.forEach((scheme, provider) -> out.add(new Contribution(scheme, provider)));
        return out;
    }

    /** For a test, which needs a clean slate and shares statics with every other test in the run. */
    public static synchronized void resetForTesting() {
        BY_SCHEME.clear();
        onDidChange.emit();
    }

    public record Contribution(String scheme, ContentProvider provider) {
    }
}
