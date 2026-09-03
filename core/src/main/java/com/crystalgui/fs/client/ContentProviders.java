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
 * <h3>The inversion, not a registry</h3>
 *
 * <p>{@code plan_fs_rewrite.md} D24. A language module registers its providers from a static
 * {@code register()} that runs at mod init — long before any world is joined, so there is no
 * {@link Workspace} to hand it. And it must not name one either: {@code core/} may never depend on
 * {@code language/}, which is the same reason {@code ProjectSourcesRegistry} and
 * {@code TypeSearchRegistry} exist and point the same way.</p>
 *
 * <p>So this holds contributions and every {@link Workspace} <b>drains it into its own table</b> at
 * construction and stays subscribed. The table stays per workspace, which is the property that matters:
 * two servers in one client are two workspaces, and one shared provider table would mean one server's
 * library scheme answering the other's requests.</p>
 *
 * <p>This is what {@code ResourceRegistry} could not be. It was a static map <em>and</em> the thing
 * every reader asked, so its contents were the process's rather than a workspace's — and its
 * {@code onSymbolResolved} was a static signal every workbench in the process stayed subscribed to.</p>
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
