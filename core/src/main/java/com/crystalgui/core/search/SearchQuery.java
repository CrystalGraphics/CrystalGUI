package com.crystalgui.core.search;

import java.util.Locale;

/**
 * A search query, normalised once.
 *
 * <h3>Why this is a type and not a {@code String}</h3>
 * <p>A query is matched against <em>every</em> candidate — a node library is hundreds — and normalising
 * is per-query work, not per-candidate work. Lower-casing inside the matcher would redo it on every
 * comparison, which is the kind of cost that only shows up once a library gets big and is then
 * attributed to "search is slow" rather than to the loop.</p>
 *
 * <p>It also gives {@link #isEmpty()} one definition. A blank query means "everything matches", and that
 * is a decision every consumer would otherwise re-make — usually as {@code text.isEmpty()}, which is not
 * the same thing as {@code text.isBlank()} the moment someone types a space.</p>
 */
public final class SearchQuery {

    /** The empty query — matches everything. */
    public static final SearchQuery EMPTY = new SearchQuery("");

    private final String normalised;

    private SearchQuery(String normalised) {
        this.normalised = normalised;
    }

    /** Trimmed and lower-cased, or {@link #EMPTY} for null/blank. */
    public static SearchQuery of(String raw) {
        if (raw == null) return EMPTY;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return EMPTY;
        return new SearchQuery(trimmed.toLowerCase(Locale.ROOT));
    }

    /** The normalised text — lower-case and trimmed. Never null. */
    public String text() {
        return normalised;
    }

    public int length() {
        return normalised.length();
    }

    /** Whether this asks for nothing, in which case a caller should show everything rather than nothing. */
    public boolean isEmpty() {
        return normalised.isEmpty();
    }

    @Override
    public String toString() {
        return "SearchQuery[" + normalised + "]";
    }
}
