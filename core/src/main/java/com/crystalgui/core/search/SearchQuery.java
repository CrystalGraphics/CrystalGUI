package com.crystalgui.core.search;

import javax.annotation.Nullable;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
    public static final SearchQuery EMPTY = new SearchQuery("", Options.DEFAULT, null);

    private final String normalised;
    private final Options options;

    /** Compiled once per query, never per candidate. Null unless {@link Options#regex()} is on. */
    @Nullable
    private final Pattern pattern;

    private SearchQuery(String normalised, Options options, @Nullable Pattern pattern) {
        this.normalised = normalised;
        this.options = options;
        this.pattern = pattern;
    }

    /** Trimmed and lower-cased, or {@link #EMPTY} for null/blank. */
    public static SearchQuery of(String raw) {
        return of(raw, Options.DEFAULT);
    }

    /**
     * As above, honouring {@code options}.
     *
     * <p><b>The options live on the query, not on the matcher's parameter list.</b> This is the type every
     * caller already passes, so putting them here makes the matcher impossible to call without them —
     * whereas extra parameters at each call site are the shape that lets one caller silently keep the old
     * behaviour, which is how the Problems panel ended up with a second private matcher (29.11).</p>
     *
     * <p>An invalid regex is a <b>state, not an exception</b>: {@link #isInvalidPattern()} is true and the
     * query matches nothing. It is compiled inside a keystroke handler, so throwing would take the whole
     * frame down over a half-typed {@code (}. IntelliJ reds the field and reports no results.</p>
     */
    public static SearchQuery of(String raw, Options options) {
        Options opts = options == null ? Options.DEFAULT : options;
        if (raw == null) return opts == Options.DEFAULT ? EMPTY : new SearchQuery("", opts, null);
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return opts == Options.DEFAULT ? EMPTY : new SearchQuery("", opts, null);
        // NOT LOWER-CASED WHEN MATCHING CASE. Normalising once per query is the reason this type exists,
        // and that argument is unchanged -- what changes is which normal form is correct.
        String text = opts.matchCase() ? trimmed : trimmed.toLowerCase(Locale.ROOT);
        Pattern pattern = null;
        if (opts.regex()) {
            try {
                pattern = Pattern.compile(text, opts.matchCase() ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException invalid) {
                pattern = null;
            }
        }
        return new SearchQuery(text, opts, pattern);
    }

    /** The normalised text — trimmed, and lower-cased unless {@link Options#matchCase()}. Never null. */
    public String text() {
        return normalised;
    }

    public Options options() {
        return options;
    }

    /** The compiled pattern, or null when this is not a regex query or the pattern would not compile. */
    @Nullable
    public Pattern pattern() {
        return pattern;
    }

    /** A regex query whose pattern did not compile — matches nothing, and the field should say so. */
    public boolean isInvalidPattern() {
        return options.regex() && !normalised.isEmpty() && pattern == null;
    }

    /**
     * How a query is matched — IntelliJ's Cc / W / .* toggles.
     *
     * <p>Immutable and shared: {@link #DEFAULT} is what every existing caller gets, so nothing changes
     * behaviour by upgrading.</p>
     */
    public record Options(boolean matchCase, boolean wholeWords, boolean regex) {

        /** Case-insensitive, anywhere in the string, literal — what search has always done here. */
        public static final Options DEFAULT = new Options(false, false, false);

        public Options withMatchCase(boolean value) {
            return new Options(value, wholeWords, regex);
        }

        public Options withWholeWords(boolean value) {
            return new Options(matchCase, value, regex);
        }

        public Options withRegex(boolean value) {
            return new Options(matchCase, wholeWords, value);
        }
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
