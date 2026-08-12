package com.crystalgui.core.search;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Locale;

/**
 * Scores a query against a candidate string, and reports where it hit.
 *
 * <h3>Ported, not invented</h3>
 * <p>Which qualities of match beat which is a <b>convention</b>, not a derivable answer — the same
 * reasoning {@code com.crystalgui.text.cursor} records for editing behaviour. The tiers here come from
 * VS Code's {@code vs/base/common/filters.ts} (MIT), which separates prefix, camel-case and contiguous
 * substring matching rather than treating them as one fuzzy score.</p>
 *
 * <h3>Field dominates kind, and that ordering is the bug fix</h3>
 * <p>A hit anywhere in a name outranks any hit in a category. That is not an aesthetic preference: the
 * create menu ranked purely alphabetically, so the query {@code vec} pre-selected <b>Cross Product</b> —
 * a node that does not contain the string at all, matched only through its {@code Math/Vector} category —
 * while {@code Vector 2} sat twelfth. Pressing Enter created the wrong node. Field weights are spaced
 * ({@link SearchMatch#FIELD_PRIMARY} and friends are 1000 apart) so no kind bonus can ever cross them.</p>
 *
 * <p>Within a field, {@link SearchMatch.Kind} orders the match, and a small positional bonus breaks ties
 * toward earlier and more complete hits — so {@code vec} prefers {@code Vector 2} over
 * {@code Normal Vector}, and prefers {@code Vector 2} over {@code Vector Rotate About Axis}. Equal scores
 * are left equal: the intended tiebreak is alphabetical, which belongs to the caller's sort, not here.</p>
 *
 * <h3>Not a general fuzzy matcher</h3>
 * <p>There is deliberately no arbitrary-subsequence match ({@code vt} matching {@code VecTor}). Unity's
 * own menu does not do it, and a subsequence matcher over a few hundred short labels returns a long tail
 * of results nobody meant — which is the failure mode a search box exists to avoid. {@link
 * SearchMatch.Kind#ACRONYM} covers the useful part (word starts) with none of that. Adding a real fuzzy
 * tier later is additive; it does not disturb the ordering above.</p>
 */
public final class SearchMatcher {

    /** Enough to order ties within one kind, never enough to reach the next kind (100 apart). */
    private static final int MAX_POSITION_BONUS = 40;

    private SearchMatcher() {
    }

    /**
     * Matches {@code query} against {@code candidate} in a field of the given weight.
     *
     * @param fieldWeight one of {@link SearchMatch#FIELD_PRIMARY} / {@link SearchMatch#FIELD_ALIAS} /
     *                    {@link SearchMatch#FIELD_CONTEXT}
     * @return the match, or {@code null} when it does not match at all. An {@link SearchQuery#isEmpty()
     *         empty} query returns null too — "everything matches" is a decision about what to SHOW, and
     *         a caller that skips the matcher entirely for a blank query is clearer than one that reads
     *         a zero score back out of it
     */
    @Nullable
    public static SearchMatch match(SearchQuery query, @Nullable String candidate, int fieldWeight) {
        return match(query, candidate, fieldWeight, false);
    }

    /**
     * As {@link #match(SearchQuery, String, int)}, with the scattered-subsequence tier switched on.
     *
     * <p>{@code allowSubsequence} is a property of the <b>consumer</b>, not of what the user typed, which is
     * why it is an argument here rather than a fourth {@link SearchQuery.Options} field. A completion list
     * wants {@code fMS} to reach {@code fooMethodStuff}; a create menu deliberately does not, because over a
     * few hundred short labels the same rule returns a long tail nobody meant. See
     * {@link SearchMatch.Kind#SUBSEQUENCE}.</p>
     *
     * <p>It is the <b>last</b> tier tried, so nothing above it changes behaviour: an exact, prefix, acronym
     * or substring hit is found and returned before this is reached. Switching it on can only add matches
     * that would otherwise have been {@code null}, never re-rank existing ones.</p>
     */
    @Nullable
    public static SearchMatch match(SearchQuery query, @Nullable String candidate, int fieldWeight,
                                    boolean allowSubsequence) {
        if (query.isEmpty() || candidate == null || candidate.isEmpty()) return null;

        // REGEX IS ITS OWN PATH and returns early: none of the ladder below means anything for a pattern,
        // and an invalid one matches nothing rather than throwing -- see SearchQuery.isInvalidPattern.
        if (query.options().regex()) return matchPattern(query, candidate, fieldWeight);

        String needle = query.text();
        // CONDITIONALLY, now. The query is normalised to the same case, so the two agree by construction;
        // lower-casing here unconditionally is what made Match Case unimplementable.
        String haystack = query.options().matchCase() ? candidate : candidate.toLowerCase(Locale.ROOT);
        if (needle.length() > haystack.length()) return null;

        // WHOLE WORDS IS A BOUNDARY TEST AROUND AN ORDINARY HIT, not a search of its own -- which is what
        // lets it compose with match case for free. It also collapses the ladder: EXACT is the only kind a
        // whole-word match can be at the start of a string, and an acronym is by definition not one word.
        if (query.options().wholeWords()) {
            int at = indexOfWord(haystack, needle);
            if (at < 0) return null;
            SearchMatch.Kind kind = at == 0 && needle.length() == haystack.length()
                    ? SearchMatch.Kind.EXACT
                    : at == 0 ? SearchMatch.Kind.PREFIX : SearchMatch.Kind.SUBSTRING;
            return scored(kind, fieldWeight, candidate, at,
                    List.of(new SearchMatch.Range(at, at + needle.length())));
        }

        if (haystack.equals(needle)) {
            return scored(SearchMatch.Kind.EXACT, fieldWeight, candidate, 0,
                    List.of(new SearchMatch.Range(0, candidate.length())));
        }
        if (haystack.startsWith(needle)) {
            return scored(SearchMatch.Kind.PREFIX, fieldWeight, candidate, 0,
                    List.of(new SearchMatch.Range(0, needle.length())));
        }

        // Acronym before substring: "cp" naming Cross Product is a stronger statement of intent than
        // "cp" happening to appear inside some longer word.
        List<SearchMatch.Range> acronym = matchAcronym(needle, candidate);
        if (acronym != null) {
            int firstAt = acronym.get(0).start();
            return scored(SearchMatch.Kind.ACRONYM, fieldWeight, candidate, firstAt, acronym);
        }

        int at = haystack.indexOf(needle);
        if (at >= 0) {
            return scored(SearchMatch.Kind.SUBSTRING, fieldWeight, candidate, at,
                    List.of(new SearchMatch.Range(at, at + needle.length())));
        }

        if (allowSubsequence) {
            Subsequence scattered = matchSubsequence(needle, candidate, query.options().matchCase());
            if (scattered != null) {
                return scored(SearchMatch.Kind.SUBSEQUENCE, fieldWeight, candidate,
                        scattered.ranges().get(0).start(), scattered.ranges(), scattered.quality());
            }
        }
        return null;
    }

    /** A scattered match and how well its characters landed. @see #matchSubsequence */
    private record Subsequence(List<SearchMatch.Range> ranges, int quality) {
    }

    /**
     * The scattered-subsequence match — VS Code's {@code fuzzyScore}, in the shape this codebase needs.
     *
     * <h3>Optimal rather than greedy, and the reason is the highlight</h3>
     *
     * <p>A greedy leftmost walk finds <em>a</em> subsequence and usually the wrong one: {@code fMS} against
     * {@code fooMethodStuff} greedily takes the {@code f} of {@code foo}, then the first {@code M} — fine —
     * then the {@code s} of… there is none before {@code Stuff}, so it works here and fails on the next
     * name along. The visible symptom is not a missing match but a <b>wrong band</b>: the highlighted
     * characters are not the ones a reader would say matched, which reads as the highlighting being broken
     * rather than the matching.</p>
     *
     * <p>So this is a small dynamic program over (pattern index, candidate index) maximising a score that
     * rewards word-start hits and contiguity, exactly the two bonuses VS Code's version has. Bounded
     * because it is quadratic: both a query and an identifier are short, and anything longer falls back to
     * no match rather than to a slow one — a completion list is redrawn on every keystroke.</p>
     */
    @Nullable
    private static Subsequence matchSubsequence(String needle, String candidate, boolean matchCase) {
        int patternLength = needle.length();
        int candidateLength = candidate.length();
        if (patternLength == 0 || patternLength > candidateLength) return null;
        if (patternLength > MAX_SUBSEQUENCE_PATTERN || candidateLength > MAX_SUBSEQUENCE_CANDIDATE) return null;

        // best[p][c] = the best score for matching needle[p..] starting the search at candidate[c..],
        // or MIN_VALUE for "impossible". Walked backwards so each cell only reads cells already filled.
        int[][] best = new int[patternLength + 1][candidateLength + 1];
        int[][] take = new int[patternLength + 1][candidateLength + 1];
        for (int c = 0; c <= candidateLength; c++) best[patternLength][c] = 0;
        for (int p = patternLength - 1; p >= 0; p--) {
            best[p][candidateLength] = Integer.MIN_VALUE;
            for (int c = candidateLength - 1; c >= 0; c--) {
                int skip = best[p][c + 1];
                int match = Integer.MIN_VALUE;
                if (equalsAt(needle.charAt(p), candidate.charAt(c), matchCase)) {
                    int rest = best[p + 1][c + 1];
                    if (rest != Integer.MIN_VALUE) {
                        match = rest + characterBonus(candidate, c)
                                // Contiguity: the next pattern character landing immediately after this one.
                                + (p + 1 < patternLength && take[p + 1][c + 1] == c + 1 ? CONTIGUOUS_BONUS : 0);
                    }
                }
                if (match >= skip) {
                    best[p][c] = match;
                    take[p][c] = c;
                } else {
                    best[p][c] = skip;
                    take[p][c] = take[p][c + 1];
                }
            }
        }
        if (best[0][0] == Integer.MIN_VALUE) return null;

        // Walk the decisions back out into ranges, merging adjacent hits so a contiguous run is one band
        // rather than four one-character ones -- which is what a reader sees as "it matched this word".
        List<SearchMatch.Range> ranges = new ArrayList<>();
        int cursor = 0;
        int rangeStart = -1;
        int rangeEnd = -1;
        for (int p = 0; p < patternLength; p++) {
            int hit = take[p][cursor];
            if (hit == rangeEnd) {
                rangeEnd = hit + 1;
            } else {
                if (rangeStart >= 0) ranges.add(new SearchMatch.Range(rangeStart, rangeEnd));
                rangeStart = hit;
                rangeEnd = hit + 1;
            }
            cursor = hit + 1;
        }
        if (rangeStart >= 0) ranges.add(new SearchMatch.Range(rangeStart, rangeEnd));
        return new Subsequence(ranges, best[0][0]);
    }

    /** Rewards a hit at a word start — index 0, a camel hump, or just after a separator. */
    private static int characterBonus(String candidate, int index) {
        if (index == 0) return WORD_START_BONUS;
        char previous = candidate.charAt(index - 1);
        char here = candidate.charAt(index);
        if (!isWordChar(previous)) return WORD_START_BONUS;
        if (Character.isLowerCase(previous) && Character.isUpperCase(here)) return WORD_START_BONUS;
        return 0;
    }

    private static boolean equalsAt(char a, char b, boolean matchCase) {
        return matchCase ? a == b
                : Character.toLowerCase(a) == Character.toLowerCase(b);
    }

    /** Quadratic, and both inputs are identifiers. Past these it declines rather than stalls a keystroke. */
    private static final int MAX_SUBSEQUENCE_PATTERN = 32;
    private static final int MAX_SUBSEQUENCE_CANDIDATE = 256;

    /** Deliberately smaller than {@link #MAX_POSITION_BONUS}'s tier gap: these order ties WITHIN
     * {@link SearchMatch.Kind#SUBSEQUENCE} and must never let a scattered hit reach the substring tier. */
    private static final int WORD_START_BONUS = 3;
    private static final int CONTIGUOUS_BONUS = 2;

    /** The first occurrence of {@code needle} with a non-word character (or an end) on both sides. */
    private static int indexOfWord(String haystack, String needle) {
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            if (isWord(haystack, at, at + needle.length())) return at;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The regex path.
     *
     * <p>Scored as {@link SearchMatch.Kind#SUBSTRING} with the usual positional bonus. A pattern has no
     * meaningful place on the EXACT/PREFIX/ACRONYM ladder, and inventing a fourth ordering for it would
     * change how {@code QuickPick} ranks everything else; degrading to the weakest kind keeps ranking
     * sane without pretending the pattern said something about intent.</p>
     *
     * <p>A <b>zero-width</b> match is refused. {@code a*} matches the empty string at position 0 of every
     * candidate, which would report everything as a hit and paint a zero-width band on each.</p>
     */
    @Nullable
    private static SearchMatch matchPattern(SearchQuery query, String candidate, int fieldWeight) {
        Pattern pattern = query.pattern();
        if (pattern == null) return null;          // invalid pattern: a state, not an exception
        Matcher matcher = pattern.matcher(candidate);
        boolean words = query.options().wholeWords();
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) continue;
            // WHOLE WORDS COMPOSES WITH REGEX, which is what both references do -- they wrap the pattern in
            // word boundaries. Applied to the match rather than to the pattern so a user's own anchors and
            // alternations are left alone: `foo|bar` means something different once it is wrapped again.
            if (!words || isWord(candidate, matcher.start(), matcher.end())) {
                return scored(SearchMatch.Kind.SUBSTRING, fieldWeight, candidate, matcher.start(),
                        List.of(new SearchMatch.Range(matcher.start(), matcher.end())));
            }
        }
        return null;
    }

    /**
     * Whether {@code [start, end)} has a non-word character (or an end) on both sides.
     *
     * <p>Public because the editor scans for <b>every</b> match rather than the best one, so it cannot go
     * through {@link #match} — and a second definition of "a word" is a second answer to Whole Words. An
     * underscore counts as part of the word, which is what a reader of code expects.</p>
     */
    public static boolean isWholeWordAt(String candidate, int start, int end) {
        return isWord(candidate, start, end);
    }

    private static boolean isWord(String candidate, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(candidate.charAt(start - 1));
        boolean rightOk = end == candidate.length() || !isWordChar(candidate.charAt(end));
        return leftOk && rightOk;
    }


    /**
     * The best match across several candidates in one field — a synonym list, say.
     *
     * @return the strongest, or {@code null} when none matched
     */
    @Nullable
    public static SearchMatch matchAny(SearchQuery query, List<String> candidates, int fieldWeight) {
        SearchMatch best = null;
        if (candidates == null) return null;
        for (String candidate : candidates) {
            best = SearchMatch.best(best, match(query, candidate, fieldWeight));
        }
        return best;
    }

    /**
     * Every query character landing on a word start, in order — {@code cp} → <b>C</b>ross <b>P</b>roduct.
     *
     * <p>A word starts at index 0, after a non-alphanumeric, or at a lower→upper transition, so this
     * reads {@code camelCase} and {@code snake_case} as well as spaced words. Restricted to word starts
     * on purpose: an unrestricted subsequence would make {@code cp} match {@code Clam<b>p</b>} too, which
     * is the long tail this deliberately does not have.</p>
     *
     * @return one range per matched character, or {@code null} if the query does not fit the word starts
     */
    @Nullable
    private static List<SearchMatch.Range> matchAcronym(String needle, String candidate) {
        List<SearchMatch.Range> ranges = new ArrayList<>(needle.length());
        int next = 0;
        for (int i = 0; i < candidate.length() && next < needle.length(); i++) {
            if (!isWordStart(candidate, i)) continue;
            if (Character.toLowerCase(candidate.charAt(i)) != needle.charAt(next)) continue;
            ranges.add(new SearchMatch.Range(i, i + 1));
            next++;
        }
        return next == needle.length() ? ranges : null;
    }

    private static boolean isWordStart(String text, int index) {
        if (index == 0) return true;
        char previous = text.charAt(index - 1);
        char current = text.charAt(index);
        if (!Character.isLetterOrDigit(previous)) return true;
        return Character.isLowerCase(previous) && Character.isUpperCase(current);
    }

    /**
     * Field weight, plus the kind, plus a small bonus for hitting early in a short candidate.
     *
     * <p>The bonus is capped below the gap between kinds, so it orders ties and never promotes a
     * substring hit above a prefix one. Length is part of it because a query that covers most of a short
     * label is a better answer than the same query buried in a long one — {@code vec} means
     * {@code Vector 2} rather than {@code Vector Rotate About Axis}.</p>
     */
    private static SearchMatch scored(SearchMatch.Kind kind, int fieldWeight, String candidate,
                                      int firstMatchAt, List<SearchMatch.Range> ranges) {
        return scored(kind, fieldWeight, candidate, firstMatchAt, ranges, 0);
    }

    /**
     * @param qualityBonus an extra within-tier bonus — the subsequence matcher's own score for how well
     *                     the characters landed. <b>Clamped</b>, because the tier gaps are the ranking's
     *                     load-bearing property: {@link SearchMatch.Kind#SUBSEQUENCE} sits 80 below
     *                     {@link SearchMatch.Kind#SUBSTRING} and the positional bonuses already spend 40 of
     *                     that, so anything able to spend the other 40 would let a scattered hit outrank a
     *                     real substring — silently, and only for some inputs
     */
    private static SearchMatch scored(SearchMatch.Kind kind, int fieldWeight, String candidate,
                                      int firstMatchAt, List<SearchMatch.Range> ranges, int qualityBonus) {
        int earliness = Math.max(0, MAX_POSITION_BONUS / 2 - firstMatchAt);
        int brevity = Math.max(0, MAX_POSITION_BONUS / 2 - candidate.length() / 4);
        int quality = Math.max(0, Math.min(MAX_QUALITY_BONUS, qualityBonus));
        return new SearchMatch(fieldWeight + kind.score() + earliness + brevity + quality,
                kind, fieldWeight, ranges);
    }

    /** See {@link #scored(SearchMatch.Kind, int, String, int, List, int)} — the remaining headroom below
     * the next tier, minus one so the arithmetic is visibly not exactly on the boundary. */
    private static final int MAX_QUALITY_BONUS = 39;
}
