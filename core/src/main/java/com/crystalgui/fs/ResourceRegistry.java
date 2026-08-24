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
        PROVIDERS.put(scheme, provider);
    }

    /**
     * The provider allowed to serve this resource's BYTES — never the project scheme.
     *
     * <h3>The guard moved here, and it is now in the right place</h3>
     *
     * <p>{@code register} used to THROW for {@code project://}, on the true statement that a project
     * file is read through the workspace client. But a provider answers two questions, and only one of
     * them is about bytes: {@link ResourceContentProvider#symbolOf} says what a resource IS, which is
     * how a library tab draws an enum glyph. Refusing at registration refused both, so the identical
     * question about the author's own file had nobody to ask — and the refusal arrived as an exception
     * out of a language's {@code register()}, which is the worst place for it.</p>
     *
     * <p>So the invariant is enforced where it is actually about to be broken: at the read. A project
     * resource answers null here however many providers claim the scheme, and the workspace client stays
     * the one way a project file's content is obtained.</p>
     */
    @Nullable
    public static ResourceContentProvider contentProviderFor(Resource resource) {
        if (resource == null || Resource.SCHEME_PROJECT.equals(resource.scheme())) return null;
        return providerFor(resource);
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
