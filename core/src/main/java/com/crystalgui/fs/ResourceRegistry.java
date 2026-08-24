package com.crystalgui.fs;

import com.crystalgui.core.async.UiBudget;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;

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
        if (resource == null) return null;
        ResourceContentProvider provider = PROVIDERS.get(resource.scheme());
        return provider == null ? null : TIMED.computeIfAbsent(provider, Timed::new);
    }

    /**
     * The timing wrappers, by the provider they wrap.
     *
     * <p>Cached rather than allocated per call: {@link #providerFor} is reached from a row bind and a tab
     * presentation, which is a hot path, and a wrapper per call would trade one frame cost for another.
     * Keyed on identity by {@code ConcurrentHashMap}'s default equality for these — a provider is a
     * singleton per scheme and does not implement {@code equals}.</p>
     */
    private static final Map<ResourceContentProvider, ResourceContentProvider> TIMED =
            new ConcurrentHashMap<>();

    /**
     * Times every provider call and names the ones that cost a frame. @see UiBudget
     *
     * <p><b>Here rather than at the call sites</b>, because the call sites are not where the mistake is
     * made. {@code symbolOf(Resource)} reads like a property getter from every one of them and was a
     * 761ms compile; instrumenting callers would mean remembering to, in every widget that ever asks a
     * provider anything. This is the one door they all go through.</p>
     */
    private static final class Timed implements ResourceContentProvider {

        private final ResourceContentProvider delegate;

        Timed(ResourceContentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] read(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.read(resource);
            } finally {
                UiBudget.end(started, "read " + resource);
            }
        }

        @Override
        public SymbolInfo symbolOf(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.symbolOf(resource);
            } finally {
                UiBudget.end(started, "symbolOf " + resource);
            }
        }

        @Override
        public TextPoint locate(Resource resource, String member) {
            long started = UiBudget.begin();
            try {
                return delegate.locate(resource, member);
            } finally {
                UiBudget.end(started, "locate " + member + " in " + resource);
            }
        }

        @Override
        public boolean isReadOnly(Resource resource) {
            return delegate.isReadOnly(resource);
        }

        /**
         * Timed like the rest, because a tab TITLE is not obviously cheap either.
         *
         * <p>{@code LibrarySources.displayName} decides between {@code .java} and {@code .class} by asking
         * whether a source archive holds the type — a classpath probe and an archive lookup, behind a
         * method the dock calls to letter a tab.</p>
         */
        @Override
        public String displayName(Resource resource) {
            long started = UiBudget.begin();
            try {
                return delegate.displayName(resource);
            } finally {
                UiBudget.end(started, "displayName " + resource);
            }
        }
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

    /**
     * A provider now knows something about a resource that it did not when it was last asked.
     *
     * <h3>Because {@code symbolOf} is allowed to answer "not yet"</h3>
     *
     * <p>Working out what a library class IS means compiling against the classpath, which is far too
     * expensive to do on the thread that draws frames — so a provider may answer null, compute in the
     * background, and say here when the answer has landed. Whoever drew a glyph from the earlier null is
     * then the one that has to ask again; nothing else can know it needs to.</p>
     *
     * <p>The same shape the workbench already uses for a project file's declaration arriving after its
     * tab did, and it coalesces the same way: several answers landing in one frame all mean one "ask
     * again". @see UiThread</p>
     */
    public static final Signal.Value<Resource> onSymbolResolved = new Signal.Value<>();

    /** Called by a provider whose deferred answer has arrived. On the UI thread. */
    public static void symbolResolved(Resource resource) {
        if (resource != null) onSymbolResolved.emit(resource);
    }

    /** Empties the registry. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        PROVIDERS.clear();
        TIMED.clear();
    }
}
