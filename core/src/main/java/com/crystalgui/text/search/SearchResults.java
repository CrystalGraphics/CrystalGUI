package com.crystalgui.text.search;

import com.crystalgui.text.TextRange;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The occurrences of a search, the cursor into them, and which of them are excluded.
 *
 * <p>IntelliJ's {@code SearchResults}, and the reason it is a type rather than three fields on a widget is
 * the third of those: <b>Exclude</b> marks a match out of Replace All, and that decision has to survive the
 * bar being re-focused, the view being scrolled and the query being re-run — and the renderer has to be
 * able to see it, since an excluded span is struck through.</p>
 *
 * <p>Headless, like everything in {@code com.crystalgui.text}: the cursor's wrapping, "which match is after
 * this offset" and the exclusion bookkeeping are all decidable without a frame.</p>
 */
public final class SearchResults {

    /** No matches, no cursor, nothing excluded. */
    public static final SearchResults EMPTY = new SearchResults(List.of());

    private final List<TextRange> matches;

    /**
     * Excluded by <b>range</b>, not by index.
     *
     * <p>Indices are renumbered by every edit and every re-query; the range is what the user pointed at.
     * Keeping indices meant a Replace All after one more keystroke skipped whichever match had inherited
     * the number.</p>
     */
    private final Set<TextRange> excluded = new HashSet<>();

    private int current = -1;

    private SearchResults(List<TextRange> matches) {
        this.matches = List.copyOf(matches);
    }

    public static SearchResults of(@Nullable List<TextRange> matches) {
        return matches == null || matches.isEmpty() ? EMPTY : new SearchResults(matches);
    }

    /**
     * The same matches, carrying forward the exclusions that still exist.
     *
     * <p>What a re-query wants: typing another character usually keeps most matches, and an exclusion the
     * user made a moment ago should not evaporate because the list was rebuilt. Anything whose range is
     * gone drops out on its own.</p>
     */
    public SearchResults withMatches(@Nullable List<TextRange> next) {
        SearchResults results = of(next);
        if (results == EMPTY) return results;
        for (TextRange range : excluded) {
            if (results.matches.contains(range)) results.excluded.add(range);
        }
        return results;
    }

    public List<TextRange> matches() {
        return matches;
    }

    public int size() {
        return matches.size();
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    /** The selected match's index, or -1 when none is. */
    public int current() {
        return current;
    }

    /** The selected match, 1-based for display, or 0 when none is. */
    public int currentNumber() {
        return current < 0 ? 0 : current + 1;
    }

    @Nullable
    public TextRange currentMatch() {
        return current < 0 || current >= matches.size() ? null : matches.get(current);
    }

    /**
     * Selects a match by index. False when there is no such match.
     *
     * <p>Here because the alternative is what the editor was doing: {@code while (current() != index &&
     * next())} — stepping the cursor round the ring to land where it was already being told to go, in
     * order to keep a second copy of this index in sync with this one. A model with a cursor should be
     * askable to put the cursor somewhere.</p>
     */
    public boolean moveTo(int index) {
        if (index < 0 || index >= matches.size()) return false;
        current = index;
        return true;
    }

    /** Moves to the next match, wrapping. False when there are none. */
    public boolean next() {
        if (matches.isEmpty()) return false;
        current = current < 0 ? 0 : (current + 1) % matches.size();
        return true;
    }

    /** Moves to the previous match, wrapping. False when there are none. */
    public boolean previous() {
        if (matches.isEmpty()) return false;
        current = current <= 0 ? matches.size() - 1 : current - 1;
        return true;
    }

    /**
     * Selects the first match at or after {@code offset}, wrapping to the first when there is none.
     *
     * <p>What typing a query should do: land on the match nearest the caret rather than at the top of the
     * file, which is what both references do and what makes a query answer itself before you stop typing.</p>
     */
    public boolean moveToFirstAtOrAfter(int offset) {
        if (matches.isEmpty()) return false;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start() >= offset) {
                current = i;
                return true;
            }
        }
        current = 0;
        return true;
    }

    // ── Exclusions ──────────────────────────────────────────────────────────────────────────────

    public boolean isExcluded(int index) {
        return index >= 0 && index < matches.size() && excluded.contains(matches.get(index));
    }

    /** Excludes the selected match, or puts it back. No-op when nothing is selected. */
    public boolean toggleExcludeCurrent() {
        TextRange range = currentMatch();
        if (range == null) return false;
        if (!excluded.remove(range)) excluded.add(range);
        return true;
    }

    public boolean isCurrentExcluded() {
        TextRange range = currentMatch();
        return range != null && excluded.contains(range);
    }

    /** What Replace All should act on — every match the user has not struck out. */
    public List<TextRange> included() {
        if (excluded.isEmpty()) return matches;
        List<TextRange> kept = new ArrayList<>(matches.size());
        for (TextRange range : matches) {
            if (!excluded.contains(range)) kept.add(range);
        }
        return kept;
    }

    /** What the editor should strike through. */
    public List<TextRange> excludedRanges() {
        if (excluded.isEmpty()) return List.of();
        List<TextRange> struck = new ArrayList<>(excluded.size());
        for (TextRange range : matches) {
            if (excluded.contains(range)) struck.add(range);
        }
        return Collections.unmodifiableList(struck);
    }
}
