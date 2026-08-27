package com.crystalgui.core.search;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One successful match: how good it was, what kind it was, and — the part that earns this type —
 * <b>exactly which characters matched</b>.
 *
 * <h3>The ranges are the whole reason this is not a boolean</h3>
 * <p>Two separate features are downstream of knowing <em>where</em> a hit landed, and both were missing
 * from the create menu because the old matcher answered yes/no:</p>
 * <ul>
 *   <li><b>Highlighting</b> — tinting the matched substring needs offsets, and this engine already has
 *       the mechanism for styling ranges without wrapping them in elements
 *       ({@code UIText.highlights()} + {@code ::highlight(name)}).</li>
 *   <li><b>Explaining the result</b> — a node matching only on its <em>category</em> looks arbitrary
 *       unless the category is shown and the matching part of it is marked. Ten of thirteen rows for
 *       the query {@code vec} were exactly that case.</li>
 * </ul>
 *
 * <p>A matcher that returns a boolean forces every consumer to re-derive the offsets, separately and
 * differently. Producing them once, here, is both cheaper and the only way they can agree.</p>
 *
 * <h3>Ordering</h3>
 * <p>{@link #compareTo} sorts <b>best first</b>, so a natural sort of a result list is already ranked.
 * Equal scores compare equal and are left to the caller to break — the intended tiebreak is
 * alphabetical, which this type has no business knowing about.</p>
 */
public record SearchMatch(int score, Kind kind, int fieldWeight, List<Range> ranges)
        implements Comparable<SearchMatch> {

    /**
     * A half-open character range, in the candidate's own coordinates.
     *
     * <p>Deliberately its own type rather than {@code ui.text.TextRange}: this package is headless and
     * has no business depending on the UI layer, and the highlight API's range is the UI's vocabulary.
     * A consumer converts, which is one map call at the one place the two meet.</p>
     */
    public record Range(int start, int end) {
        public Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Bad range " + start + ".." + end);
            }
        }

        public int length() {
            return end - start;
        }
    }

    /**
     * How the query matched, strongest first.
     *
     * <p>Ported in spirit from VS Code's {@code vs/base/common/filters.ts}, which separates
     * {@code matchesPrefix}, {@code matchesCamelCase} and {@code matchesContiguousSubString} for the same
     * reason: they are different qualities of match, not different amounts of one.</p>
     */
    public enum Kind {
        /** The candidate is the query. */
        EXACT(400),
        /** The candidate starts with the query — {@code vec} → {@code Vector 2}. */
        PREFIX(300),
        /** The query hits word starts — {@code cp} → {@code Cross Product}. */
        ACRONYM(200),
        /** The query appears somewhere inside. */
        SUBSTRING(100),

        /**
         * The query's characters appear in order but not together — {@code fMS} → {@code fooMethodStuff}.
         *
         * <p><b>Opt-in per consumer</b>, via {@link SearchMatcher#match(SearchQuery, String, int, boolean)},
         * because the two consumers genuinely disagree and both are right. A completion list wants it: it is
         * the headline behaviour of every modern editor and the list is already scoped to what is in scope
         * at the caret. A create menu does not: over a few hundred short labels a subsequence matcher returns
         * a long tail nobody meant, which is the failure a search box exists to avoid — and Unity's own menu
         * refuses it too.</p>
         *
         * <p>Lowest tier by a wide margin, so any real substring hit outranks any subsequence hit. That
         * ordering is what stops {@code set} from ranking {@code sELECTED_tEXT} above {@code setText}.</p>
         */
        SUBSEQUENCE(20);

        private final int score;

        Kind(int score) {
            this.score = score;
        }

        public int score() {
            return score;
        }
    }

    /**
     * Weight of the field a match was found in. <b>Field dominates kind</b> — see
     * {@link SearchMatcher} — so these are spaced far enough apart that no kind bonus can cross them.
     */
    public static final int FIELD_PRIMARY = 3000;
    /** An alternative name — a synonym. Below any match on the primary name. */
    public static final int FIELD_ALIAS = 2000;
    /** Surrounding context — a category, a path. Below any match on a name, which is the whole fix for
     * "a category-only hit outranked an exact name hit". */
    public static final int FIELD_CONTEXT = 1000;

    /**
     * How <b>well</b> this matched, without the within-tier tiebreaks — field weight plus kind.
     *
     * <p>{@link #score} is this plus earliness, brevity and the subsequence matcher's quality bonus. Those
     * exist to order rows that matched equally well, and folding them into the ranking makes them
     * outrank anything a consumer wants to say for itself: the completion list found that comparing on
     * {@code score} ranked a local {@code precision} below a class {@code Printer} purely because
     * {@code Printer} is two characters shorter.</p>
     *
     * <p>So a consumer with a signal of its own — proximity for completion, availability for the command
     * palette — sorts by this first, then by its own signal, then by {@code score}. A consumer with no
     * such signal can go on comparing {@link SearchMatch}es directly and gets the same answer, since
     * within one tier the score <em>is</em> the tiebreak.</p>
     */
    public int tier() {
        return fieldWeight + kind.score();
    }

    /** Best of two, either of which may be null. */
    @Nullable
    public static SearchMatch best(@Nullable SearchMatch a, @Nullable SearchMatch b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.score() >= b.score() ? a : b;
    }

    /** Descending by score, so a natural sort is already ranked. */
    @Override
    public int compareTo(SearchMatch other) {
        return Integer.compare(other.score, score);
    }
}
