package com.crystalgui.widget.texteditor.suggest;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionRecency;
import com.crystalgui.text.lang.SymbolKind;

import java.util.Comparator;

/**
 * What order a completion list comes in — IntelliJ's weigher chain, §18.3.
 *
 * <h3>A chain, not a score</h3>
 *
 * <p>The obvious design is one number per item, and it is the one that cannot express this. The criteria are
 * <b>lexicographic</b>: a better match always wins, and proximity only decides between items that matched
 * equally well. Collapsed into a sum, a large enough proximity bonus outranks a better match — and the
 * threshold at which it starts doing so depends on the query, so it is right in testing and wrong for some
 * user's identifier. IntelliJ layers weighers for exactly this reason and LSP's single {@code sortText}
 * famously cannot express it.</p>
 *
 * <h3>The order, and what each step is for</h3>
 *
 * <ol>
 *   <li><b>Match quality.</b> What the user typed is the strongest statement of intent there is.</li>
 *   <li><b>Deprecation.</b> A deprecated member sinks below a live one it ties with. Cheap, and it is the
 *       difference between offering the replacement and offering the thing being replaced.</li>
 *   <li><b>Proximity.</b> A local beats a field beats a static import beats an unimported type. Nearer
 *       things are more likely meant, and an unimported type additionally costs an import to accept.</li>
 *   <li><b>The provider's own {@code sortText}.</b> The engine knows things this layer does not — that a
 *       member is inherited from {@code Object}, that a type is in {@code java.lang}.</li>
 *   <li><b>Alphabetical.</b> Not a preference, a <em>stability</em> requirement: without a total order the
 *       sort is free to permute equal items between keystrokes, and a list whose rows move while the
 *       selection stays at index 0 accepts a different item than the one that was under the cursor.</li>
 * </ol>
 *
 * <p><b>Expected-type conformance is not here yet.</b> §18.3 names it as the single best behaviour in either
 * IDE and it belongs between quality and proximity — but it needs {@code Resolver.expectedTypeAt} threaded
 * through the provider, so it is the provider that must express it, as {@code sortText}. Recorded rather
 * than half-built: a step that reads a field nobody fills is a step that looks implemented.</p>
 */
final class CompletionRanking {

    private CompletionRanking() {
    }

    /**
     * The chain, as a comparator over rows.
     *
     * <p>It takes no query. An exact hit is already {@link SearchMatch.Kind#EXACT} and outranks a prefix hit
     * by a whole tier, so the boost a caller would be tempted to add here is one the matcher has applied
     * already — adding it twice is how a tier gap gets crossed by accident.</p>
     */
    static Comparator<CompletionSession.Row> byQuality() {
        if (sortByName) return byNameOnly();
        return Comparator
                .comparingInt(CompletionRanking::tierOf)
                .thenComparingInt(row -> row.item().deprecated() ? 1 : 0)
                .thenComparingInt(row -> proximityOf(row.item().kind()))
                // OBJECT'S MEMBERS LAST, within their kind. Every type has them, so they are never what the
                // list was opened for -- and they crowd the top of an alphabetical tail where `equals`,
                // `getClass` and `hashCode` all sort early. After proximity rather than before, so a field
                // still outranks a method: this orders WITHIN a category, not across them.
                .thenComparingInt(row -> row.item().inheritedFromObject() ? 1 : 0)
                // RECENCY, after proximity and before the positional bonuses -- §18.3's ordering. It only
                // ever separates items that already matched and are already equally near, which is the
                // whole reason it can be this crude without being disruptive.
                .thenComparingInt(row -> -CompletionRecency.shared().rankOf(row.item()))
                .thenComparingInt(row -> -matchScore(row))
                .thenComparing(row -> row.item().sortKey())
                .thenComparing(row -> row.item().label());
    }

    /**
     * Plain alphabetical — IntelliJ's "Sort by Name", offered from the popup's own menu.
     *
     * <p>It deliberately drops <em>everything</em> else, relevance included. That is the point of the
     * option: a ranked list is better nearly always and impossible to predict when you already know the
     * name you want and just need to find it. Keeping the tier as a first key would make it "sort by name
     * within relevance bands", which is neither thing and would be the version nobody asked for.</p>
     *
     * <p>Case-insensitive, so {@code PRECISION_LIMIT} files beside {@code precision} rather than in a
     * separate upper-case block — the sorted-by-name view exists to be scanned by eye.</p>
     */
    private static Comparator<CompletionSession.Row> byNameOnly() {
        return Comparator
                .comparing((CompletionSession.Row row) -> row.item().filterKey(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.item().label());
    }

    /**
     * Whether the list is ordered by name rather than by relevance.
     *
     * <p>Application-wide and in memory, matching where the toggle lives: it is offered from the popup's
     * menu and applies to the next popup too, because a sort order you chose once and had to choose again
     * is worse than not offering it.</p>
     */
    private static volatile boolean sortByName;

    static boolean isSortByName() {
        return sortByName;
    }

    static void setSortByName(boolean value) {
        sortByName = value;
    }

    /**
     * The match's <b>tier</b> — exact, prefix, acronym, substring, subsequence — and nothing else.
     *
     * <h3>Why the tier and not the score, which was the first version</h3>
     *
     * <p>{@link SearchMatch#score()} folds the tier together with positional bonuses for earliness and
     * <em>brevity</em>. Comparing on it makes those bonuses outrank proximity outright, because they are
     * arithmetic inside the same number — and the effect is not subtle. Typing {@code pr} with a local
     * {@code precision} in scope ranked it <b>below</b> a class called {@code Printer}, purely because
     * {@code Printer} is two characters shorter. Seen in the harness log, where a list of eleven rows makes
     * the order legible in a way a unit test asserting on the top row does not.</p>
     *
     * <p>Brevity is still a real signal, so it stays — as a tiebreak <em>after</em> proximity, which is
     * §18.3's ordering read literally: match quality, then proximity, then everything else. Quality means
     * "how well did it match", and "the candidate is short" is not that.</p>
     */
    private static int tierOf(CompletionSession.Row row) {
        SearchMatch match = row.match();
        // No match means an unfiltered list, where every row ties here and the provider's order carries
        // through -- which is what an empty prefix should do.
        return match == null ? 0 : match.kind().ordinal();
    }

    private static int matchScore(CompletionSession.Row row) {
        SearchMatch match = row.match();
        return match == null ? 0 : match.score();
    }

    /**
     * How near a kind is to the caret. Lower sorts first.
     *
     * <p>Keyed on {@link SymbolKind} because that is what an item already carries — a separate proximity
     * field would be a second thing for a provider to fill in and forget. The consequence is that this is a
     * <em>kind</em>-shaped approximation of a genuinely positional question: a local variable is always
     * nearer than a class, which is true, but two locals at different depths tie. Refining it needs a real
     * proximity field, and that is a provider change rather than a change here.</p>
     */
    private static int proximityOf(SymbolKind kind) {
        if (kind == null) return 50;
        switch (kind) {
            case LOCAL_VARIABLE:
            case PARAMETER:
                return 0;
            case FIELD:
            case PROPERTY:
            case CONSTANT:
            case ENUM_MEMBER:
                return 10;
            case METHOD:
            case FUNCTION:
            case CONSTRUCTOR:
                return 20;
            case CLASS:
            case INTERFACE:
            case ENUM:
            case RECORD:
            case ANNOTATION:
            case TYPE_PARAMETER:
                return 30;
            case KEYWORD:
                return 40;
            default:
                return 50;
        }
    }
}
