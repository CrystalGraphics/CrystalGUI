package com.crystalgui.fs;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * scheme → who can read it.
 *
 * <p>The seam that lets a feature open its own kind of document without the workbench learning what it
 * is: the shader-graph package registers {@code shader-generated}, and the workbench opens a tab on it
 * knowing only that some scheme has a provider.</p>
 *
 * <h3>Global, and explicit</h3>
 *
 * <p>Global for the reason commands are: a scheme is a fact about the application, not about a window,
 * and a provider that varied per window would make the same URI mean different things in two places —
 * which is exactly what a URI exists to prevent.</p>
 *
 * <p>Explicit, for the reason everything else here is: no static initialisers, so a scheme's existence
 * cannot depend on class-loading order. The project scheme is deliberately <b>not</b> registered — it is
 * read through the workspace client, which needs a session, and pretending otherwise would put a
 * synchronous byte-returning method in front of a network round trip.</p>
 */
public final class ResourceRegistry {

    private ResourceRegistry() {
    }

    private static final Map<String, ResourceContentProvider> PROVIDERS = new ConcurrentHashMap<>();

    public static void register(String scheme, ResourceContentProvider provider) {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(provider, "provider");
        if (Resource.SCHEME_PROJECT.equals(scheme)) {
            throw new IllegalArgumentException(
                    "the project scheme is read through the workspace client, not a content provider");
        }
        PROVIDERS.put(scheme, provider);
    }

    /** Null when nothing has registered this scheme — an ordinary answer, not a failure. */
    @Nullable
    public static ResourceContentProvider providerFor(Resource resource) {
        return resource == null ? null : PROVIDERS.get(resource.scheme());
    }

    /**
     * Whether this resource may be edited.
     *
     * <p>A project file is writable; anything else is read-only unless its provider says otherwise, and
     * an <b>unregistered</b> scheme is read-only too — refusing to write something nobody claims is the
     * safe direction.</p>
     */
    public static boolean isReadOnly(Resource resource) {
        if (resource == null) return true;
        if (resource.isProject()) return false;
        ResourceContentProvider provider = providerFor(resource);
        return provider == null || provider.isReadOnly(resource);
    }

    /** Empties the registry. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        PROVIDERS.clear();
    }
}
