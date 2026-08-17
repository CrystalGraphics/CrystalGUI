package com.crystalgui.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * "Did you mean" — which names are close enough to a misspelt one to be worth offering.
 *
 * <h3>Edit distance, not the completion matcher</h3>
 *
 * <p>Completion ranks by prefix and then by scattered subsequence, and its own javadoc names the consumer
 * that must refuse the subsequence tier: one that would otherwise return a long tail nobody meant. This is
 * that consumer. {@code Strimg} is not a subsequence of {@code String} and {@code Lst} is a subsequence of
 * dozens of things, so the question here is a different one — <em>how many keystrokes wrong is it</em> —
 * and Damerau–Levenshtein answers it, with a transposition ({@code Lsit}) costing one rather than two.</p>
 *
 * <h3>The cap is the feature</h3>
 *
 * <p>Distance two, or one for a name of three characters or fewer, and at most five results. Anything
 * looser turns a typo fix into a list of forty vaguely similar names, and IntelliJ's own "Change to" stays
 * this tight. Case is ignored for the distance and then used as the tie-break, so {@code string} offers
 * {@code String} first among equals.</p>
 *
 * <p>Pure Java with no engine types, so both classloaders may load it — the host's type index ranks with
 * it and the engine's corrections do too. Two copies of a stateless utility cost nothing and share
 * nothing.</p>
 */
public final class SimilarNames {

    // ── WHY THIS IS IN core/, NOT BESIDE AN ENGINE ─────────────────────────────────────────────
    //
    // It began in `language.java` and the JavaScript catalog imported it -- which quietly defined a
    // SECOND copy of the class, because the band loader is child-first over `com.crystalgui.language.*`
    // and `JsQuickFixes` lives on the far side of the bridge. Two classes with one name, one per loader,
    // for a utility that is nothing but strings in and strings out.
    //
    // `com.crystalgui.text.*` is parent-first and shared with every engine by construction, which is
    // where a language-neutral utility belongs: it sits beside WordClassifier and WordOperations, which
    // are the same kind of thing. Two implementations of "how close is close enough" would drift, and the
    // first divergence reads as one engine being broken rather than as two tolerances -- so the ranking is
    // shared and the CANDIDATES are each language's to supply.

    private static final int MAX_RESULTS = 5;

    private SimilarNames() {
    }

    /** How far off {@code typed} may be for a candidate of its length to count. */
    static int tolerance(String typed) {
        return typed.length() <= 3 ? 1 : 2;
    }

    /**
     * The candidates within tolerance of {@code typed}, closest first, capped, without duplicates.
     *
     * <p>{@code typed} itself is never returned — a name that resolves to nothing is not fixed by
     * offering it again — and the ordering is total, so two hovers over the same problem list the same
     * things in the same order.</p>
     */
    public static List<String> rank(String typed, Collection<String> candidates) {
        if (typed == null || typed.isEmpty() || candidates == null) return List.of();
        String needle = typed.toLowerCase(Locale.ROOT);
        int tolerance = tolerance(typed);

        List<Ranked> ranked = new ArrayList<>();
        for (String candidate : new LinkedHashSet<>(candidates)) {
            if (candidate == null || candidate.isEmpty() || candidate.equals(typed)) continue;
            // Cheap first: a length gap larger than the tolerance cannot be within it.
            if (Math.abs(candidate.length() - typed.length()) > tolerance) continue;
            int distance = distance(needle, candidate.toLowerCase(Locale.ROOT), tolerance);
            if (distance > tolerance) continue;
            ranked.add(new Ranked(candidate, distance, caseDifferences(typed, candidate)));
        }
        ranked.sort(Comparator.comparingInt((Ranked r) -> r.distance)
                .thenComparingInt(r -> r.casePenalty)
                .thenComparingInt(r -> r.name.length())
                .thenComparing(r -> r.name));

        List<String> out = new ArrayList<>();
        for (Ranked r : ranked) {
            if (out.size() >= MAX_RESULTS) break;
            out.add(r.name);
        }
        return out;
    }

    private record Ranked(String name, int distance, int casePenalty) {
    }

    /**
     * How many positions differ only in case — the tie-break, never the filter.
     *
     * <p>Counted rather than boolean so {@code string} prefers {@code String} (one letter's case away) to
     * {@code STRING} (six), where a same-case-or-not test would call them equal and let the alphabet pick.</p>
     */
    private static int caseDifferences(String typed, String candidate) {
        int n = Math.min(typed.length(), candidate.length());
        int differing = 0;
        for (int i = 0; i < n; i++) {
            char a = typed.charAt(i), b = candidate.charAt(i);
            if (Character.toLowerCase(a) == Character.toLowerCase(b) && a != b) differing++;
        }
        return differing;
    }

    /**
     * Optimal-string-alignment distance, cut off at {@code limit + 1}.
     *
     * <p>The cut-off is what makes walking a fifty-thousand-entry index affordable: a row whose minimum
     * already exceeds the limit cannot come back down, so the rest of the table is never filled in.</p>
     *
     * <p>Public only because {@code DidYouMeanTest} pins the metric itself — the transposition case is the
     * one thing about it that is a choice rather than a formula, and asserting it through {@link #rank}
     * would be asserting it through the tolerance and the ordering as well.</p>
     */
    public static int distance(String a, String b, int limit) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, prev2[j - 2] + 1);
                }
                cur[j] = value;
                if (value < rowMin) rowMin = value;
            }
            if (rowMin > limit) return limit + 1;
            int[] spare = prev2;
            prev2 = prev;
            prev = cur;
            cur = spare;
        }
        return prev[m];
    }
}
