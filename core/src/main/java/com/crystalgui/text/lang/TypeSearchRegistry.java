package com.crystalgui.text.lang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where {@link TypeSearch} providers register, and the one place a picker asks.
 *
 * <h3>A list, not a slot</h3>
 *
 * <p>One provider is what ships today — the Java engine over its classpath index — and it would have been
 * simpler to hold exactly one. But "which types exist" is already not a Java-only question:
 * {@code TypeIndex}'s own visibility note records that it was widened from package-private the moment a
 * {@code .js} file wanted {@code Java.type("a.b.C")} completion. A slot makes the second contributor
 * <em>replace</em> the first silently, which is the same class of bug as a registry that answers about
 * whichever language registered last.</p>
 *
 * <h3>Absent is a supported state, not an error</h3>
 *
 * <p>A deployment with no engine registers nothing and this answers empty — the same three-tier degradation
 * the whole language stack is built on. The picker then opens and lists nothing, which is honest; it does
 * not fail, and it is not gated on a flag that could disagree with what actually loaded.</p>
 */
public final class TypeSearchRegistry {

    private static final List<TypeSearch> PROVIDERS = new CopyOnWriteArrayList<>();

    private TypeSearchRegistry() {
    }

    /**
     * Adds a provider. Idempotent by identity, so a language's {@code register()} may run twice — which
     * it does: a host that opens two languages calls each one's registration, and both lend to the other.
     */
    public static void contribute(TypeSearch provider) {
        if (provider == null || PROVIDERS.contains(provider)) return;
        PROVIDERS.add(provider);
    }

    public static boolean remove(TypeSearch provider) {
        return PROVIDERS.remove(provider);
    }

    /** True when anything can answer at all — for a caller that wants to not offer the affordance. */
    public static boolean hasProvider() {
        return !PROVIDERS.isEmpty();
    }

    /**
     * Every provider's answer, concatenated and capped.
     *
     * <p><b>Deduplicated by qualified name</b>, insertion-ordered. Two engines over one classpath answer
     * about the same types — that is the whole point of sharing the index — so without this the Java and
     * JavaScript providers would list {@code java.util.ArrayList} twice, and the reason would be invisible
     * from the row. IntelliJ needed four classes for the general version of this problem
     * ({@code SEResultsEqualityProvider} and friends); one qualified name is enough while every provider
     * answers about types.</p>
     *
     * <p>Ranking is <b>not</b> done here. Results come back in provider order and the caller sorts them with
     * the one matcher — see {@link TypeSearch}'s note on why a provider does not rank.</p>
     */
    public static TypeSearch.Results search(String query, int limit) {
        if (query == null || query.isEmpty() || limit <= 0) return TypeSearch.Results.EMPTY;
        if (PROVIDERS.isEmpty()) return TypeSearch.Results.EMPTY;

        List<TypeSearch.Result> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean truncated = false;

        for (TypeSearch provider : PROVIDERS) {
            TypeSearch.Results answer;
            try {
                answer = provider.search(query, limit);
            } catch (RuntimeException failed) {
                // A provider that throws is a broken engine, not a broken picker. The others still answer,
                // and a search that returns fewer rows is survivable in a way one that throws out of a
                // keystroke handler is not.
                continue;
            }
            if (answer == null) continue;
            truncated |= answer.truncated();
            for (TypeSearch.Result result : answer.results()) {
                if (!seen.add(result.qualifiedName())) continue;
                if (merged.size() >= limit) {
                    // THE CAP ITSELF TRUNCATES, and saying so is the point -- see Results#truncated.
                    return new TypeSearch.Results(merged, true);
                }
                merged.add(result);
            }
        }
        return new TypeSearch.Results(merged, truncated);
    }

    /** Drops every provider. Tests only — statics outlive a test class, and this one is shared. */
    public static void resetForTesting() {
        PROVIDERS.clear();
    }
}
