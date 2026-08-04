package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.text.TextRange;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <h3>Ordering is the source's answer, not a suggestion</h3>
 *
 * <p>{@link QuickPick} renders the returned list in order and pre-selects the first row, so the source
 * decides what Enter does on an untouched query. That is the same contract the node create menu has, and
 * the same reason its ranking bug — pressing Enter created the wrong node — was worth a matcher rewrite.</p>
 */
@FunctionalInterface
public interface QuickPickSource {

    /**
     * The rows for this query, best first.
     *
     * @param query never null; may be {@link SearchQuery#isEmpty() empty}, which conventionally means
     *              "show everything" rather than "show nothing"
     */
    List<QuickPickEntry> query(SearchQuery query);

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

        private final List<QuickPickItem> items;

        StaticSource(List<QuickPickItem> items) {
            this.items = items == null ? List.of() : items;
        }

        @Override
        public List<QuickPickEntry> query(SearchQuery query) {
            if (query.isEmpty()) {
                List<QuickPickItem> sorted = new ArrayList<>(items);
                sorted.sort(ALPHABETICAL);
                List<QuickPickEntry> all = new ArrayList<>(sorted.size());
                for (QuickPickItem item : sorted) all.add(QuickPickEntry.plain(item));
                return all;
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

            // Descending by score (SearchMatch's natural order), then alphabetical. The matcher
            // deliberately leaves equal scores equal and documents the tiebreak as the caller's job.
            scored.sort(Comparator.comparing((Scored s) -> s.match)
                    .thenComparing(s -> s.item, ALPHABETICAL));

            List<QuickPickEntry> out = new ArrayList<>(scored.size());
            for (Scored s : scored) out.add(new QuickPickEntry(s.item, s.labelRanges, s.categoryRanges));
            return out;
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
