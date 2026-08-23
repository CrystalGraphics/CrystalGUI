package com.crystalgui.text.lang;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where {@link ProjectSources} providers register, and the one place an engine asks.
 *
 * <p>Shaped like {@link TypeSearchRegistry}, and for the same reasons: a list rather than a slot, so a
 * second contributor cannot silently replace the first; and absent is a supported state rather than an
 * error, because a host with no workspace open has no project sources and must behave as it always did.</p>
 *
 * <h3>First answer wins, deliberately</h3>
 *
 * <p>Two workspaces can declare the same qualified name, exactly as two jars on a classpath can. A
 * classpath resolves that by order and so does this — the alternative is to refuse an ambiguous name,
 * which would make one project's file stop resolving because an unrelated one was opened.</p>
 */
public final class ProjectSourcesRegistry {

    private static final List<ProjectSources> PROVIDERS = new CopyOnWriteArrayList<>();

    private ProjectSourcesRegistry() {
    }

    /** Adds a provider. Idempotent by identity — a workbench may re-register on reload. */
    public static void contribute(ProjectSources provider) {
        if (provider == null || PROVIDERS.contains(provider)) return;
        PROVIDERS.add(provider);
    }

    public static boolean remove(ProjectSources provider) {
        return PROVIDERS.remove(provider);
    }

    public static boolean hasProvider() {
        return !PROVIDERS.isEmpty();
    }

    /**
     * A view over every registered provider, safe to hand across the engine bridge.
     *
     * <p>A live view rather than a snapshot: it is built once and held by an analysis that may outlive
     * several edits, and a provider registered after that point must still be visible. Also the reason
     * this returns a {@link ProjectSources} rather than the list — the band-side caller may not name a
     * {@code List} of one of our types any more safely than it may name a {@code CgPath}.</p>
     */
    public static ProjectSources view() {
        return VIEW;
    }

    private static final ProjectSources VIEW = new ProjectSources() {
        @Override
        @Nullable
        public String sourceOf(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isEmpty()) return null;
            for (ProjectSources provider : PROVIDERS) {
                try {
                    String source = provider.sourceOf(qualifiedName);
                    if (source != null) return source;
                } catch (RuntimeException failed) {
                    // A broken provider costs its own answers and nothing else. This runs inside a
                    // compile; an exception here would surface as the file failing to resolve, which
                    // reads as the user's code being wrong.
                }
            }
            return null;
        }

        @Override
        @Nullable
        public String pathOf(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isEmpty()) return null;
            // FIRST ANSWER WINS, exactly as sourceOf does -- the two must agree about which project owns
            // a name, or a hover would quote one file and Ctrl+B would open another.
            for (ProjectSources provider : PROVIDERS) {
                try {
                    String path = provider.pathOf(qualifiedName);
                    if (path != null) return path;
                } catch (RuntimeException failed) {
                    // as below
                }
            }
            return null;
        }

        @Override
        public boolean declaresPackage(String packageName) {
            if (packageName == null) return false;
            for (ProjectSources provider : PROVIDERS) {
                try {
                    if (provider.declaresPackage(packageName)) return true;
                } catch (RuntimeException failed) {
                    // as above
                }
            }
            return false;
        }

        @Override
        public List<String> declaredTypes() {
            // MERGED and deduplicated, unlike sourceOf: "what exists" wants everything, and two projects
            // declaring different types is not the ambiguity one NAME resolving twice is.
            Set<String> merged = new LinkedHashSet<>();
            for (ProjectSources provider : PROVIDERS) {
                try {
                    merged.addAll(provider.declaredTypes());
                } catch (RuntimeException failed) {
                    // as below
                }
            }
            return merged.isEmpty() ? List.of() : new ArrayList<>(merged);
        }
    };

    /** Drops every provider. Tests only — this is a static and it outlives a test class. */
    public static void resetForTesting() {
        PROVIDERS.clear();
    }
}
