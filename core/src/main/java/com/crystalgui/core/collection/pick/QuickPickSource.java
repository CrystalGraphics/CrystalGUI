package com.crystalgui.core.collection.pick;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.text.TextRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * Where a {@link QuickPick}'s rows come from — asked afresh for every query.
 *
 * <h3>The source filters; the widget never does</h3>
 *
 * <p>This is the one shape in the quick-pick layer that is genuinely hard to change later, so it is worth
 * stating why it is a <em>function of the query</em> rather than a list the widget filters.</p>
 *
 * <p>A command palette has its whole candidate set in memory and could be filtered by the widget. A file
 * picker over {@code com.crystalgui.fs} cannot: the files live on the server, the query goes over RPC, and
 * the answer arrives later. A widget that owns the filtering has no seam for that — the async provider
 * would have to fake a synchronous list, or the widget would grow a second code path. Asking a source
 * means both look identical from here, and the difference stays inside the implementation where it
 * belongs.</p>
 *
 * <p>It also means <b>ranking and highlighting stay in the same place</b>. A source that ranks by one rule
 * while the widget lights up characters by another produces rows sorted by relevance and highlighted by
 * something else — which reads as a highlighting bug and is really a disagreement about matching.</p>
 *
 * <h3>A source PUSHES; it does not return a list</h3>
 *
 * <p>It used to return {@code List<QuickPickEntry>}, and the paragraph above explains why that could not
 * last: a list is an answer that is already complete, and the case this seam exists for is the one where
 * it is not. IntelliJ's equivalent is {@code fetchElements(pattern, indicator, consumer)} for the same
 * reason — a provider streams into a consumer and is cancelled by an indicator, and the popup renders what
 * has arrived so far.</p>
 *
 * <p>Changed while there were four implementations rather than a dozen. The shape is what matters now;
 * today's only consumer drains it synchronously ({@link #drain}), and an asynchronous source can hold its
 * {@link ResultSink} and push later without the widget or any other source noticing.</p>
 *
 * <h3>Ordering is the source's answer, not a suggestion</h3>
 *
 * <p>{@link QuickPick} renders the pushed rows in order and pre-selects the first, so the source decides
 * what Enter does on an untouched query. That is the same contract the node create menu has, and the same
 * reason its ranking bug — pressing Enter created the wrong node — was worth a matcher rewrite.</p>
 */
@FunctionalInterface
public interface QuickPickSource {

    /**
     * Pushes the rows for this query, best first, until the sink stops wanting them.
     *
     * @param query never null; may be {@link SearchQuery#isEmpty() empty}, which conventionally means
     *              "show everything" rather than "show nothing" — though a source over something unbounded
     *              may reasonably read it as the latter
     * @param sink  where rows go. <b>Stop as soon as {@link ResultSink#accept} answers false</b>; carrying
     *              on is not merely wasteful, it is how a source over sixty thousand entries stalls a
     *              keystroke
     */
    void fetch(SearchQuery query, ResultSink sink);

    /**
     * Where a source's rows go.
     *
     * <p>Deliberately not a {@code Consumer}: a consumer cannot say <em>stop</em>, and the two things this
     * adds beyond accepting a row are both about stopping. {@link #accept} answers false when no more are
     * wanted, and {@link #markTruncated} is how a source that stopped early says so — which the widget
     * cannot infer, because a source that pushed exactly the cap and a source that had exactly that many
     * look identical from here.</p>
     */
    interface ResultSink {

        /**
         * Takes one row.
         *
         * @return false when no more are wanted. A source that keeps pushing after a false is not wrong so
         *         much as pointless — the rows are dropped — but it pays for every one of them.
         */
        boolean accept(QuickPickEntry entry);

        /**
         * True once nothing more will be taken — a cap reached, or a newer query having superseded this
         * one. Worth checking inside a loop that does real work per candidate.
         */
        boolean isCancelled();

        /**
         * Says there was more than was pushed.
         *
         * <p><b>The single most important thing on this interface</b>, and the easiest to leave out. Every
         * index behind a picker is bounded, and a truncated list is indistinguishable from a complete one
         * unless it says so — so a row that exists but fell past a cap looks exactly like a row that does
         * not exist. That is the worst answer a search can give: it is wrong, and it is wrong in the
         * direction that stops the user looking.</p>
         */
        void markTruncated();
    }

    /** A drained source: the rows it pushed, and whether there were more. */
    record Batch(List<QuickPickEntry> entries, boolean truncated) {

        public Batch {
            entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
        }

        public static final Batch EMPTY = new Batch(Collections.emptyList(), false);
    }

    /**
     * Collects a source's answer into a list, stopping at {@code limit}.
     *
     * <p>What a synchronous consumer wants, and the only thing {@link QuickPick} does today. It is a static
     * helper rather than a default method on purpose: a source must not be able to <em>override</em> how it
     * is drained, or the cap becomes advisory.</p>
     */
    static Batch drain(QuickPickSource source, SearchQuery query, int limit) {
        if (source == null || limit <= 0) return Batch.EMPTY;
        Collector collector = new Collector(limit);
        source.fetch(query == null ? SearchQuery.EMPTY : query, collector);
        return new Batch(collector.entries, collector.truncated);
    }

    /**
     * {@link #drain}'s sink. Caps, and learns from the <b>one extra row</b> whether the cap bit.
     *
     * <h3>Why it accepts up to the limit and then asks for one more</h3>
     *
     * <p>The obvious version returns false on the row that fills the list — and then can never tell a
     * source that had exactly {@code limit} rows from one that had ten thousand, because a well-behaved
     * source stops the moment it is refused and both look identical from here. Truncation would be
     * unreportable for precisely the sources that need it most.</p>
     *
     * <p>So the limit-th row is accepted with a {@code true}, the source offers one more, and <em>that</em>
     * refusal is the evidence. It is the same fetch-{@code n+1}-to-know-there-is-a-next-page trick a
     * paginated query uses, and it costs one extra candidate per query.</p>
     *
     * <p>A source that stops for a reason of its own — polling {@link #isCancelled}, or having narrowed
     * before it answered — must call {@link #markTruncated} itself. It knows there was more and this
     * cannot: nothing was ever refused.</p>
     */
    final class Collector implements ResultSink {

        private final List<QuickPickEntry> entries = new ArrayList<>();
        private final int limit;
        private boolean truncated;

        Collector(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean accept(QuickPickEntry entry) {
            if (entries.size() >= limit) {
                // THE ROW PAST THE END. Not stored, and its existence is the whole answer.
                truncated = true;
                return false;
            }
            if (entry != null) entries.add(entry);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return entries.size() >= limit;
        }

        @Override
        public void markTruncated() {
            truncated = true;
        }
    }

    /**
     * A source over a fixed list, matched with {@link SearchMatcher}.
     *
     * <p>The list is captured by reference and re-read on every query, so a caller that mutates it between
     * opens does not need to rebuild the source. It is <b>not</b> re-read mid-query.</p>
     */
    static QuickPickSource of(List<QuickPickItem> items) {
        return new StaticSource(items);
    }

    /** {@link #of(List)}'s implementation, named so a stack trace says which source produced a row. */
    final class StaticSource implements QuickPickSource {

        /** Alphabetical within category — so an empty query is a stable, readable list rather than
         * whatever order the registry happened to enumerate in. */
        private static final Comparator<QuickPickItem> ALPHABETICAL =
                Comparator.comparing((QuickPickItem item) -> item.category() == null ? "" : item.category())
                        .thenComparing(QuickPickItem::label);

        /**
         * <b>What you can use, and what belongs to where you are</b> — before anything alphabetical.
         *
         * <h3>Two signals, in this order, and both are the item's own</h3>
         *
         * <p><b>Available first.</b> A row that cannot be chosen is still listed — that is settled, and
         * for a good reason recorded on {@link QuickPickItem#enabled} — but listing it <em>among</em> the
         * rows that work makes the whole list read as a lottery: typing {@code re} put Remove, Replace,
         * Rename and Restore, none of them choosable, above and between the four commands that were. The
         * dimming says which is which and the eye still has to do the sorting.</p>
         *
         * <p><b>Then contextual.</b> Of the rows that do work, the ones that work <em>because of where
         * the picker was opened</em> come first, so a palette summoned from an editor leads with the
         * editor's verbs rather than with whatever else happens to be live. @see QuickPickItem#contextual</p>
         *
         * <p>Booleans are negated so that {@code true} sorts first — {@code Boolean}'s natural order puts
         * false before true, and the two things worth having are the true ones.</p>
         */
        private static final Comparator<QuickPickItem> PREFERENCE =
                Comparator.comparing((QuickPickItem item) -> !item.enabled())
                        .thenComparing(item -> !item.contextual());

        /** {@link #PREFERENCE}, then the alphabet. The whole order for a query that ranks nothing. */
        private static final Comparator<QuickPickItem> BY_PREFERENCE = PREFERENCE.thenComparing(ALPHABETICAL);

        private final List<QuickPickItem> items;

        StaticSource(List<QuickPickItem> items) {
            this.items = items == null ? List.of() : items;
        }

        @Override
        public void fetch(SearchQuery query, ResultSink sink) {
            if (query.isEmpty()) {
                List<QuickPickItem> sorted = new ArrayList<>(items);
                // THE SAME PREFERENCE AS A QUERY'S, and one rule rather than two on purpose: an untouched
                // palette is browsed by the same eye that reads a filtered one, and a list whose ordering
                // rule changed the moment you typed a character would be the harder thing to learn.
                sorted.sort(BY_PREFERENCE);
                for (QuickPickItem item : sorted) {
                    if (!sink.accept(QuickPickEntry.plain(item))) return;
                }
                return;
            }

            List<Scored> scored = new ArrayList<>();
            for (QuickPickItem item : items) {
                SearchMatch onLabel = SearchMatcher.match(query, item.label(), SearchMatch.FIELD_PRIMARY);
                SearchMatch onCategory =
                        SearchMatcher.match(query, item.category(), SearchMatch.FIELD_CONTEXT);
                SearchMatch best = SearchMatch.best(onLabel, onCategory);
                if (best == null) continue;
                // Highlight the field that WON, not every field that matched. Lighting up both would
                // claim the category contributed to the ranking when a label hit outranks it outright.
                boolean labelWon = best == onLabel;
                scored.add(new Scored(item, best,
                        labelWon ? toRanges(best) : List.of(),
                        labelWon ? List.of() : toRanges(best)));
            }

            // MATCHED IN FULL BEFORE ANYTHING IS PUSHED, and that is not a missed streaming opportunity.
            // Ranking is a property of the whole set, so a source that pushed as it matched would emit an
            // order it then wanted to change -- which is precisely the case the sink cannot express.
            // Streaming pays off where the CANDIDATES arrive over time; here they are already in memory.
            // TIER, THEN PREFERENCE, THEN THE SCORE'S OWN TIEBREAKS.
            //
            // This used to sort on the whole SearchMatch, which is descending `score` -- and `score` is
            // the tier PLUS earliness and brevity. Comparing on it leaves no room between "matched
            // better" and "two characters shorter" for anything a consumer knows, so a disabled row two
            // characters shorter than an enabled one still won. Splitting the tier out is the same repair
            // the completion list already made for proximity, and for the same reason: the fine-grained
            // bonuses exist to order rows that matched EQUALLY well, so they belong below whatever the
            // consumer has to say and not above it.
            //
            // A better tier still wins outright. That is deliberate and is the counter-assertion the
            // covering test makes: an exact hit on an unavailable row must not sink beneath a scattered
            // hit on an available one, or searching for a command by its full name stops finding it.
            scored.sort(Comparator.comparingInt((Scored s) -> -s.match.tier())
                    .thenComparing(s -> s.item, PREFERENCE)
                    .thenComparing(s -> s.match)
                    .thenComparing(s -> s.item, ALPHABETICAL));

            for (Scored s : scored) {
                if (!sink.accept(new QuickPickEntry(s.item, s.labelRanges, s.categoryRanges))) return;
            }
        }

        private static List<TextRange> toRanges(SearchMatch match) {
            List<TextRange> ranges = new ArrayList<>(match.ranges().size());
            for (SearchMatch.Range range : match.ranges()) {
                ranges.add(TextRange.of(range.start(), range.end()));
            }
            return ranges;
        }

        private record Scored(QuickPickItem item, SearchMatch match,
                              List<TextRange> labelRanges, List<TextRange> categoryRanges) {
        }
    }
}
